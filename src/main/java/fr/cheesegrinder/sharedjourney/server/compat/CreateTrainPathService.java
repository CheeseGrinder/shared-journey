package fr.cheesegrinder.sharedjourney.server.compat;

import fr.cheesegrinder.sharedjourney.common.network.Payloads;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.network.PacketDistributor;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the train path hover feature: answers the client's
 * request with the remaining navigation route of a Create train, as a
 * polyline of block coordinates.
 *
 * <p>Create's {@code Navigation.currentPath} only stores the BRANCH
 * DECISIONS (the edges chosen at junctions, consumed by navigateOptions),
 * not the route geometry. The actual route is therefore SIMULATED here:
 * walk the track graph from the train's front, sampling each edge's
 * geometry (smooth on curves), consuming the pending decisions at
 * junctions, until the navigation's remaining distance is spent — which
 * ends the line exactly at the destination station. Pure reflection: no
 * compile-time dependency on Create; inactive if absent.
 */
public final class CreateTrainPathService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Per-player request throttle (the client hovers at frame rate). */
    private static final long REQUEST_MIN_INTERVAL_MS = 250;

    /** Hard caps: walked edges and polyline points. */
    private static final int MAX_EDGES = 512;

    private static final int MAX_POINTS = 2048;

    /** Sampling step (blocks) along curved edges; straights use endpoints. */
    private static final double CURVE_STEP_BLOCKS = 3.0;

    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private static boolean resolved;
    private static boolean available;
    private static Object railways;
    private static Field trainsField;
    private static Field carriagesField;
    private static Field graphField;
    private static Field navigationField;
    private static Field destinationField;
    private static Field distanceToDestinationField;
    private static Field destinationBehindTrainField;
    private static Field currentPathField;
    private static Method getLeadingPoint;
    private static Method getTrailingPoint;
    private static Field pointNode1;
    private static Field pointNode2;
    private static Field pointPosition;
    private static Method graphGetConnectionsFrom;
    private static Method edgeGetLength;
    private static Method edgeGetPosition;
    private static Method edgeIsTurn;
    private static Method coupleGetFirst;
    private static Method coupleGetSecond;
    private static Method nodeGetLocation;
    private static Method locationGetDimension;

    private CreateTrainPathService() {}

    /** Handles a hovered-train path request; replies with the polyline (or nothing). */
    public static void handleRequest(Player playerRaw, Payloads.TrainPathRequestPayload payload) {
        if (!(playerRaw instanceof ServerPlayer player)) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST.get(player.getUUID());
        if (last != null && now - last < REQUEST_MIN_INTERVAL_MS) {
            return;
        }

        LAST_REQUEST.put(player.getUUID(), now);
        if (!resolve()) {
            return;
        }

        try {
            Object train = ((Map<?, ?>) trainsField.get(railways)).get(payload.trainId());
            if (train == null) {
                return;
            }

            List<int[]> points = walkRoute(train, player.level().dimension());
            int[] xs = new int[points.size()];
            int[] zs = new int[points.size()];
            for (int i = 0; i < points.size(); i++) {
                xs[i] = points.get(i)[0];
                zs[i] = points.get(i)[1];
            }
            PacketDistributor.sendToPlayer(player, new Payloads.TrainPathPayload(payload.trainId(), xs, zs));
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Create train path lookup failed, disabling: {}", t.toString());
            available = false;
        }
    }

    /**
     * Simulates the train's route: from its front point, follow the
     * track graph edge by edge (branch decisions from currentPath) until
     * the remaining navigation distance is spent.
     */
    private static List<int[]> walkRoute(Object train, ResourceKey<?> playerDim) throws ReflectiveOperationException {
        List<int[]> points = new ArrayList<>();
        Object navigation = navigationField.get(train);
        double budget = (double) distanceToDestinationField.get(navigation);
        if (destinationField.get(navigation) == null || budget <= 0) {
            return points;
        }

        Object graph = graphField.get(train);
        List<?> carriages = (List<?>) carriagesField.get(train);
        if (graph == null || carriages == null || carriages.isEmpty()) {
            return points;
        }

        // The train's front depends on its travel direction.
        boolean backwards = (boolean) destinationBehindTrainField.get(navigation);
        Object point =
                backwards ? getTrailingPoint.invoke(carriages.getLast()) : getLeadingPoint.invoke(carriages.getFirst());
        Object from = backwards ? pointNode2.get(point) : pointNode1.get(point);
        Object to = backwards ? pointNode1.get(point) : pointNode2.get(point);
        if (from == null || to == null || !inDimension(from, playerDim)) {
            return points;
        }

        // Pending branch decisions, consumed as junctions are crossed.
        Deque<Object> decisions = new ArrayDeque<>();
        List<?> currentPath = (List<?>) currentPathField.get(navigation);
        if (currentPath != null) {
            decisions.addAll(currentPath);
        }

        double startDistance = (double) pointPosition.get(point);
        for (int step = 0; step < MAX_EDGES && points.size() < MAX_POINTS; step++) {
            Map<?, ?> connections = (Map<?, ?>) graphGetConnectionsFrom.invoke(graph, from);
            Object edge = connections == null ? null : connections.get(to);
            if (edge == null) {
                break;
            }

            double length = (double) edgeGetLength.invoke(edge);
            // First edge: start at the train's position along it. On a
            // reversed edge the geometry is the forward twin's mirrored
            // copy, so the distance simply flips.
            double from0 = step == 0 ? (backwards ? Math.max(0, length - startDistance) : startDistance) : 0;
            double take = Math.min(length - from0, budget);
            if (take > 0) {
                sampleEdge(points, graph, edge, length, from0, take);
                budget -= take;
            }
            if (budget <= 0) {
                break;
            }

            Object previous = from;
            from = to;
            if (!inDimension(from, playerDim)) {
                break;
            }

            to = nextNode(graph, from, previous, decisions);
            if (to == null) {
                break;
            }
        }
        return points;
    }

    /** Samples [from0, from0+take] of an edge (endpoints only on straights). */
    private static void sampleEdge(
            List<int[]> points, Object graph, Object edge, double length, double from0, double take)
            throws ReflectiveOperationException {
        double step = Boolean.TRUE.equals(edgeIsTurn.invoke(edge)) ? CURVE_STEP_BLOCKS : take;
        for (double d = 0; ; d += step) {
            double clamped = Math.min(d, take);
            Vec3 pos = (Vec3) edgeGetPosition.invoke(edge, graph, (from0 + clamped) / length);
            addPoint(points, pos);
            if (clamped >= take || points.size() >= MAX_POINTS) {
                break;
            }
        }
    }

    /**
     * Next node after a junction: the single continuation when there is
     * no choice, otherwise the pending branch decision for this node —
     * without one, the walk stops (the line resumes once the train has
     * crossed the junction; accepted limitation).
     */
    private static Object nextNode(Object graph, Object node, Object cameFrom, Deque<Object> decisions)
            throws ReflectiveOperationException {
        Map<?, ?> connections = (Map<?, ?>) graphGetConnectionsFrom.invoke(graph, node);
        if (connections == null) {
            return null;
        }

        Object single = null;
        int candidates = 0;
        for (Object next : connections.keySet()) {
            if (!next.equals(cameFrom)) {
                single = next;
                candidates++;
            }
        }
        if (candidates <= 1) {
            return single;
        }

        // Real branch: consume decisions until one matches this node.
        while (!decisions.isEmpty()) {
            Object couple = decisions.poll();
            if (node.equals(coupleGetFirst.invoke(couple))) {
                return coupleGetSecond.invoke(couple);
            }
        }
        return null;
    }

    private static boolean inDimension(Object trackNode, ResourceKey<?> playerDim) throws ReflectiveOperationException {
        Object location = nodeGetLocation.invoke(trackNode);
        return playerDim.equals(locationGetDimension.invoke(location));
    }

    private static void addPoint(List<int[]> points, Vec3 pos) {
        // Floor, like Create's rail raster: the client repaints these cells.
        int x = (int) Math.floor(pos.x);
        int z = (int) Math.floor(pos.z);
        if (!points.isEmpty()) {
            int[] last = points.get(points.size() - 1);
            if (last[0] == x && last[1] == z) {
                return;
            }
        }
        points.add(new int[] {x, z});
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }

        resolved = true;
        try {
            Class<?> create = Class.forName("com.simibubi.create.Create");
            Class<?> train = Class.forName("com.simibubi.create.content.trains.entity.Train");
            Class<?> carriage = Class.forName("com.simibubi.create.content.trains.entity.Carriage");
            Class<?> travellingPoint = Class.forName("com.simibubi.create.content.trains.entity.TravellingPoint");
            Class<?> navigation = Class.forName("com.simibubi.create.content.trains.entity.Navigation");
            Class<?> trackGraph = Class.forName("com.simibubi.create.content.trains.graph.TrackGraph");
            Class<?> trackEdge = Class.forName("com.simibubi.create.content.trains.graph.TrackEdge");
            Class<?> trackNode = Class.forName("com.simibubi.create.content.trains.graph.TrackNode");
            Class<?> nodeLocation = Class.forName("com.simibubi.create.content.trains.graph.TrackNodeLocation");
            Class<?> couple = Class.forName("net.createmod.catnip.data.Couple");

            railways = create.getField("RAILWAYS").get(null);
            trainsField = railways.getClass().getField("trains");
            carriagesField = train.getField("carriages");
            graphField = train.getField("graph");
            navigationField = train.getField("navigation");
            destinationField = navigation.getField("destination");
            distanceToDestinationField = navigation.getField("distanceToDestination");
            destinationBehindTrainField = navigation.getField("destinationBehindTrain");
            // Package-private: the decisions never leave the server in Create.
            currentPathField = navigation.getDeclaredField("currentPath");
            currentPathField.setAccessible(true);
            getLeadingPoint = carriage.getMethod("getLeadingPoint");
            getTrailingPoint = carriage.getMethod("getTrailingPoint");
            pointNode1 = travellingPoint.getField("node1");
            pointNode2 = travellingPoint.getField("node2");
            pointPosition = travellingPoint.getField("position");
            graphGetConnectionsFrom = trackGraph.getMethod("getConnectionsFrom", trackNode);
            edgeGetLength = trackEdge.getMethod("getLength");
            edgeGetPosition = trackEdge.getMethod("getPosition", trackGraph, double.class);
            edgeIsTurn = trackEdge.getMethod("isTurn");
            coupleGetFirst = couple.getMethod("getFirst");
            coupleGetSecond = couple.getMethod("getSecond");
            nodeGetLocation = trackNode.getMethod("getLocation");
            locationGetDimension = nodeLocation.getMethod("getDimension");
            available = true;
            LOGGER.info("[Bridge JM] Create train path service wired.");
        } catch (ClassNotFoundException e) {
            // Create absent: nothing to do.
            available = false;
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Create train path service incompatible: {}", t.toString());
            available = false;
        }
        return available;
    }
}
