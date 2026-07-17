package fr.cheesegrinder.sharedjourney.client.compat;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.config.MapClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.MinimapClientConfig;
import fr.cheesegrinder.sharedjourney.client.event.ClientInputEvents;
import fr.cheesegrinder.sharedjourney.client.gui.FullMapScreen;
import fr.cheesegrinder.sharedjourney.common.network.TrainPathPayloads;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bridge for Create's train map, by reflection (no compile-time dependency
 * on Create; inactive if absent). Two jobs:
 *
 * <p>1. Sync pump: Create's tick (JourneyTrainMap.tick) only requests
 * train data from the server if the open screen is JourneyMap's INTERNAL
 * Fullscreen — never ours. So we reproduce its work when OUR map is
 * shown: TrainMapManager.tick() + TrainMapSyncClient.requestData() every
 * tick, then stopRequesting() on close.
 *
 * <p>2. Overlay rendering ({@link #renderMap}): Create's own JourneyMap
 * render path (JourneyTrainMap.onRender) computes the hover pick in
 * blocks RELATIVE TO THE MAP CENTER while the picking code compares
 * against ABSOLUTE world coordinates — the "+ mapCenter" step is missing
 * (a Create bug; its Xaero path does add it), so station/train name
 * tooltips can never show. We therefore keep the FullscreenRenderEvent
 * away from Create and reproduce its rendering ourselves, pick fixed.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class CreateTrainMapBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Position of the toggle widget drawn by the train map (Create's
     * constants): Create's native look is deliberately kept — a bridged
     * mod with its own widget reads as an addon at a glance. API TOP_LEFT
     * buttons start after it (see FullMapScreen.buildApiToolbar).
     */
    private static final int TOGGLE_WIDGET_X = 3;

    private static final int TOGGLE_WIDGET_Y = 30;

    /** Max tooltip text width, as used by Create's drawHoveringText calls. */
    private static final int TOOLTIP_MAX_WIDTH = 256;

    /** Hover pick radius around a carriage (blocks, like Create's stations). */
    private static final int TRAIN_PICK_RADIUS_BLOCKS = 3;

    /** Min delay between two path requests for the hovered train (ms). */
    private static final long PATH_REQUEST_INTERVAL_MS = 300;

    /** Path rail tint: opaque gold, repainting the rail's own block cells. */
    private static final int PATH_COLOR = 0xFFFFB733;

    private static boolean resolved;
    private static boolean available;
    private static Method managerTick;
    private static Method requestData;
    private static Method stopRequesting;
    private static Method handleToggleWidgetClick;
    private static Method drawHoveringText;
    private static Method allConfigsClient;
    private static Field showTrainMapOverlayField;
    private static Method configBoolGet;
    private static boolean requesting;

    // Per-frame reflective calls (render, hover pick, rail pixel reads):
    // typed MethodHandles invoked via invokeExact — a Method.invoke here
    // boxes every int/boolean/double argument on each of the hundreds of
    // calls a frame can make (pickTrain per carriage, getPixel per
    // corridor cell). Cold paths (tick pump, clicks, tooltips) keep
    // plain reflection above.
    private static MethodHandle renderAndPick;
    private static MethodHandle renderToggleWidget;
    private static MethodHandle isToggleWidgetHovered;
    private static MethodHandle syncCurrentData;
    private static MethodHandle entryDimensions;
    private static MethodHandle entryPositions;
    private static MethodHandle entryGetPosition;
    private static MethodHandle rendererGetPixel;

    /** Corridor (blocks) around the route in which rail pixels are recolored. */
    private static final int PATH_CORRIDOR_RADIUS = 1;

    /** Refresh period of the painted-cell set (rail sections draw lazily). */
    private static final long PATH_PAINT_REFRESH_MS = 500;

    // Path of the hovered/followed train (client thread): the route cells
    // (rasterized polyline), then the actual RAIL pixels found around them
    // in Create's own map buffer — painting those repaints the rail itself
    // instead of overlaying a separate, possibly offset line.
    private static UUID pathTrainId;
    private static int[] pathCellXs;
    private static int[] pathCellZs;
    private static int[] paintedXs;
    private static int[] paintedZs;
    private static int[] paintedColors;
    private static long paintedComputedAt;
    private static long lastPathRequestAt;

    private CreateTrainMapBridge() {}

    /** Stores the requested train's path and rasterizes it to block cells. */
    public static void acceptPath(UUID trainId, int[] xs, int[] zs) {
        pathTrainId = trainId;
        List<long[]> cells = new ArrayList<>();
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < xs.length - 1; i++) {
            double dx = xs[i + 1] - xs[i];
            double dz = zs[i + 1] - zs[i];
            int steps = Math.max(1, (int) (Math.max(Math.abs(dx), Math.abs(dz)) * 2));
            for (int s = 0; s <= steps; s++) {
                int cx = (int) Math.floor(xs[i] + dx * s / steps);
                int cz = (int) Math.floor(zs[i] + dz * s / steps);
                long packed = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                if (packed != previous) {
                    previous = packed;
                    cells.add(new long[] {cx, cz});
                }
            }
        }

        int[] cellXs = new int[cells.size()];
        int[] cellZs = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            cellXs[i] = (int) cells.get(i)[0];
            cellZs[i] = (int) cells.get(i)[1];
        }
        pathCellXs = cellXs;
        pathCellZs = cellZs;
        paintedComputedAt = 0;
    }

    /**
     * Matches the route cells against Create's rail pixel buffer: every
     * BRIGHT rail pixel within the corridor gets painted — the buffer also
     * stores the rail's dark outline pixels, which must keep their color.
     * This follows Create's own rasterization exactly (offsets, rail
     * width). Route cells with no drawn rail yet fall back to themselves.
     */
    private static void recomputePaintedCells() throws Throwable {
        Set<Long> seen = new HashSet<>();
        List<long[]> painted = new ArrayList<>();
        for (int i = 0; i < pathCellXs.length; i++) {
            boolean railFound = false;
            for (int dx = -PATH_CORRIDOR_RADIUS; dx <= PATH_CORRIDOR_RADIUS; dx++) {
                for (int dz = -PATH_CORRIDOR_RADIUS; dz <= PATH_CORRIDOR_RADIUS; dz++) {
                    int cx = pathCellXs[i] + dx;
                    int cz = pathCellZs[i] + dz;
                    int pixel = (int) rendererGetPixel.invokeExact(cx, cz);
                    if (!isRailCorePixel(pixel)) {
                        continue;
                    }

                    railFound = true;
                    if (seen.add(((long) cx << 32) | (cz & 0xFFFFFFFFL))) {
                        painted.add(new long[] {cx, cz, brightnessOf(pixel)});
                    }
                }
            }
            if (!railFound && seen.add(((long) pathCellXs[i] << 32) | (pathCellZs[i] & 0xFFFFFFFFL))) {
                painted.add(new long[] {pathCellXs[i], pathCellZs[i], 255});
            }
        }

        // Create shades its rail pixels with the track's slope/curve
        // relief: reproduce it by modulating the gold with each replaced
        // pixel's brightness, normalized on the route's brightest one.
        int brightest = 1;
        for (long[] cell : painted) {
            brightest = Math.max(brightest, (int) cell[2]);
        }

        int[] xs = new int[painted.size()];
        int[] zs = new int[painted.size()];
        int[] colors = new int[painted.size()];
        for (int i = 0; i < painted.size(); i++) {
            xs[i] = (int) painted.get(i)[0];
            zs[i] = (int) painted.get(i)[1];
            colors[i] = shade(PATH_COLOR, (float) painted.get(i)[2] / brightest);
        }
        paintedXs = xs;
        paintedZs = zs;
        paintedColors = colors;
        paintedComputedAt = System.currentTimeMillis();
    }

    /** Brightest channel of a buffer pixel (channel order agnostic). */
    private static int brightnessOf(int pixel) {
        return Math.max((pixel >> 16) & 0xFF, Math.max((pixel >> 8) & 0xFF, pixel & 0xFF));
    }

    /** Scales an ARGB color's channels (alpha untouched). */
    private static int shade(int argb, float factor) {
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /**
     * Bright rail pixel = the rail's core; dark ones are its outline
     * (both live in the same buffer) and transparent ones are empty.
     */
    private static boolean isRailCorePixel(int pixel) {
        if ((pixel >>> 24) == 0) {
            return false;
        }

        int max = Math.max((pixel >> 16) & 0xFF, Math.max((pixel >> 8) & 0xFF, pixel & 0xFF));
        return max >= 64;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean fullMapOpen = mc.screen instanceof FullMapScreen;
        // The minimap also shows train overlays: it needs the data too.
        boolean minimapShown = mc.screen == null
                && !mc.options.hideGui
                && ClientInputEvents.minimapVisible
                && MinimapClientConfig.MINIMAP_ENABLED.get();
        boolean mapOpen = mc.level != null && (fullMapOpen || minimapShown) && MapClientConfig.SHOW_TRAIN_OVERLAY.get();
        if (mapOpen && JourneyMapBridge.bridgeActive() && resolve()) {
            try {
                managerTick.invoke(null);
                requestData.invoke(null);
                requesting = true;
            } catch (Throwable t) {
                warnAndDisable(t);
            }
            return;
        }

        if (requesting) {
            requesting = false;
            try {
                stopRequesting.invoke(null);
            } catch (Throwable t) {
                warnAndDisable(t);
            }
        }
    }

    /**
     * Renders the train map overlay (tracks, stations, trains) and, on the
     * fullscreen map, the toggle widget and the hover tooltip with the
     * station/train name. Reproduces JourneyTrainMap.onRender with the
     * pick converted to ABSOLUTE world coordinates (see the class doc:
     * Create's JourneyMap path forgets the "+ mapCenter").
     *
     * @param scale         GUI pixels per block (the map's zoom)
     * @param followedTrain train being followed by the view, if any: its
     *                      route stays painted without hovering it
     */
    public static void renderMap(
            GuiGraphics gg,
            int screenW,
            int screenH,
            double centerX,
            double centerZ,
            double scale,
            int mouseX,
            int mouseY,
            boolean fullscreen,
            UUID followedTrain) {
        if (!JourneyMapBridge.bridgeActive() || !resolve() || !MapClientConfig.SHOW_TRAIN_OVERLAY.get()) {
            return;
        }

        try {
            // Create's own toggle (the widget below): overlay hidden but
            // the widget stays clickable on the fullscreen map.
            List<?> tooltip = null;
            if (overlayEnabled()) {
                var pose = gg.pose();
                pose.pushPose();
                pose.translate(screenW / 2f, screenH / 2f, 0);
                pose.scale((float) scale, (float) scale, 1f);
                pose.translate(-centerX, -centerZ, 0);

                int pickX = Mth.floor((mouseX - screenW / 2f) / scale + centerX);
                int pickZ = Mth.floor((mouseY - screenH / 2f) / scale + centerZ);
                Rect2i bounds = new Rect2i(
                        Mth.floor(-screenW / 2f / scale + centerX),
                        Mth.floor(-screenH / 2f / scale + centerZ),
                        Mth.floor(screenW / scale),
                        Mth.floor(screenH / scale));
                // 4th argument = LINEAR filtering of the rail buffer.
                // Create's own FTBChunks path computes it the same way
                // (its JourneyMap path hardcodes false): smooth under
                // minification — a block smaller than one physical
                // pixel — and keep crisp NEAREST under magnification.
                boolean linearFiltering =
                        scale * Minecraft.getInstance().getWindow().getGuiScale() < 1.0;
                tooltip = (List<?>) renderAndPick.invokeExact(gg, pickX, pickZ, linearFiltering, bounds);

                // Hovered or followed train: its remaining navigation
                // route, drawn in the same world-coordinate pose (the
                // route only exists server-side — requested and cached,
                // see acceptPath).
                if (fullscreen) {
                    UUID hoveredTrain = pickTrain(pickX, pickZ);
                    UUID activeTrain = hoveredTrain != null ? hoveredTrain : followedTrain;
                    if (activeTrain != null) {
                        requestPath(activeTrain);
                        if (activeTrain.equals(pathTrainId) && pathCellXs != null && pathCellXs.length > 0) {
                            drawPath(gg);
                        }
                    }
                }
                pose.popPose();
            }

            if (!fullscreen) {
                return;
            }

            renderToggleWidget.invokeExact(gg, TOGGLE_WIDGET_X, TOGGLE_WIDGET_Y);
            Font font = Minecraft.getInstance().font;
            boolean toggleHovered =
                    (boolean) isToggleWidgetHovered.invokeExact(mouseX, mouseY, TOGGLE_WIDGET_X, TOGGLE_WIDGET_Y);
            if (toggleHovered) {
                List<Component> lines = List.of(Component.translatable("create.train_map.toggle"));
                drawHoveringText.invoke(
                        null, gg, lines, mouseX, mouseY + 20, screenW, screenH, TOOLTIP_MAX_WIDTH, font);
            } else if (tooltip != null) {
                drawHoveringText.invoke(null, gg, tooltip, mouseX, mouseY, screenW, screenH, TOOLTIP_MAX_WIDTH, font);
            }
        } catch (Throwable t) {
            warnAndDisable(t);
        }
    }

    /** Train under a world position on the map, or null (guarded pick for clicks). */
    public static UUID trainAt(double worldX, double worldZ) {
        if (!JourneyMapBridge.bridgeActive() || !resolve() || !MapClientConfig.SHOW_TRAIN_OVERLAY.get()) {
            return null;
        }

        try {
            return pickTrain(Mth.floor(worldX), Mth.floor(worldZ));
        } catch (Throwable t) {
            warnAndDisable(t);
            return null;
        }
    }

    /** Current map position of a train (first carriage in the player's dimension), or null. */
    public static Vec3 trainPosition(UUID trainId) {
        if (!JourneyMapBridge.bridgeActive() || !resolve()) {
            return null;
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            Map<?, ?> data = (Map<?, ?>) syncCurrentData.invokeExact();
            Object entry = data.get(trainId);
            if (mc.level == null || entry == null) {
                return null;
            }

            Object currentDim = mc.level.dimension();
            List<?> dims = (List<?>) entryDimensions.invokeExact(entry);
            Object[] positions = (Object[]) entryPositions.invokeExact(entry);
            int carriages = positions == null ? 0 : Math.min(dims.size(), positions.length / 6);
            for (int i = 0; i < carriages; i++) {
                if (currentDim.equals(dims.get(i))) {
                    return (Vec3) entryGetPosition.invokeExact(entry, i, true, 1.0);
                }
            }
            return null;
        } catch (Throwable t) {
            warnAndDisable(t);
            return null;
        }
    }

    /**
     * Train whose carriage sits within the pick radius of the hovered
     * block, from the map sync data ({@code TrainMapSyncClient}), or null.
     */
    private static UUID pickTrain(int pickX, int pickZ) throws Throwable {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }

        Object currentDim = mc.level.dimension();
        Map<?, ?> data = (Map<?, ?>) syncCurrentData.invokeExact();
        for (Map.Entry<?, ?> e : data.entrySet()) {
            Object entry = e.getValue();
            List<?> dims = (List<?>) entryDimensions.invokeExact(entry);
            Object[] positions = (Object[]) entryPositions.invokeExact(entry);
            int carriages = positions == null ? 0 : Math.min(dims.size(), positions.length / 6);
            for (int i = 0; i < carriages; i++) {
                if (!currentDim.equals(dims.get(i))) {
                    continue;
                }

                Vec3 pos = (Vec3) entryGetPosition.invokeExact(entry, i, true, 1.0);
                if (Math.max(Math.abs(pickX - pos.x), Math.abs(pickZ - pos.z)) <= TRAIN_PICK_RADIUS_BLOCKS) {
                    return (UUID) e.getKey();
                }
            }
        }
        return null;
    }

    /** Requests (throttled) the hovered train's path from the server. */
    private static void requestPath(UUID trainId) {
        long now = System.currentTimeMillis();
        if (now - lastPathRequestAt < PATH_REQUEST_INTERVAL_MS) {
            return;
        }

        lastPathRequestAt = now;
        PacketDistributor.sendToServer(new TrainPathPayloads.TrainPathRequestPayload(trainId));
    }

    /**
     * Repaints the route's rail pixels. Called with the world-coordinate
     * pose active (1 unit = 1 block): the painted set is the actual rail
     * pixels of Create's map buffer along the route (see
     * recomputePaintedCells) — the rail itself appears recolored.
     */
    private static void drawPath(GuiGraphics gg) throws Throwable {
        if (System.currentTimeMillis() - paintedComputedAt > PATH_PAINT_REFRESH_MS) {
            recomputePaintedCells();
        }

        var pose = gg.pose();
        pose.pushPose();
        pose.translate(0, 0, 4);
        for (int i = 0; i < paintedXs.length; i++) {
            int x = paintedXs[i];
            int z = paintedZs[i];
            gg.fill(x, z, x + 1, z + 1, paintedColors[i]);
        }
        pose.popPose();
    }

    /**
     * Click on the toggle widget the train map draws in the top-left
     * corner of the map: Create's handler is gated on JourneyMap's
     * internal screen, so we call its logic ourselves. Returns true if
     * the click was consumed.
     */
    public static boolean handleToggleClick(int mouseX, int mouseY) {
        if (!toggleWidgetActive()) {
            return false;
        }

        try {
            boolean consumed = Boolean.TRUE.equals(
                    handleToggleWidgetClick.invoke(null, mouseX, mouseY, TOGGLE_WIDGET_X, TOGGLE_WIDGET_Y));
            if (consumed) {
                // The click may have flipped Create's overlay config:
                // re-read it on the next frame instead of the next second.
                overlayCheckedAt = 0;
            }
            return consumed;
        } catch (Throwable t) {
            warnAndDisable(t);
            return false;
        }
    }

    /**
     * True when the toggle widget is drawn on the fullscreen map: the
     * addon zone reserves its footprint before placing API buttons.
     */
    public static boolean toggleWidgetActive() {
        return JourneyMapBridge.bridgeActive() && resolve() && MapClientConfig.SHOW_TRAIN_OVERLAY.get();
    }

    /** Refresh period of the cached Create overlay config value (ms). */
    private static final long OVERLAY_CONFIG_REFRESH_MS = 1000;

    private static boolean overlayEnabledCached;

    private static long overlayCheckedAt;

    /**
     * Create's own "show train map overlay" client config value. Read
     * through three reflective hops, so the value is cached and re-read
     * at most once per second (or on the next frame after a click on the
     * toggle widget — see handleToggleClick).
     */
    private static boolean overlayEnabled() {
        long now = System.currentTimeMillis();
        if (now - overlayCheckedAt < OVERLAY_CONFIG_REFRESH_MS) {
            return overlayEnabledCached;
        }

        try {
            Object cclient = allConfigsClient.invoke(null);
            overlayEnabledCached = Boolean.TRUE.equals(configBoolGet.invoke(showTrainMapOverlayField.get(cclient)));
            overlayCheckedAt = now;
            return overlayEnabledCached;
        } catch (Throwable t) {
            warnAndDisable(t);
            return false;
        }
    }

    private static void warnAndDisable(Throwable t) {
        LOGGER.warn("[Bridge JM] Create train map sync failed, disabling: {}", t.toString());
        available = false;
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }

        resolved = true;
        try {
            Class<?> manager = Class.forName("com.simibubi.create.compat.trainmap.TrainMapManager");
            Class<?> sync = Class.forName("com.simibubi.create.compat.trainmap.TrainMapSyncClient");
            Class<?> guiUtils = Class.forName("com.simibubi.create.foundation.gui.RemovedGuiUtils");
            Class<?> allConfigs = Class.forName("com.simibubi.create.infrastructure.config.AllConfigs");
            managerTick = manager.getMethod("tick");
            requestData = sync.getMethod("requestData");
            stopRequesting = sync.getMethod("stopRequesting");
            handleToggleWidgetClick =
                    manager.getMethod("handleToggleWidgetClick", int.class, int.class, int.class, int.class);
            drawHoveringText = guiUtils.getMethod(
                    "drawHoveringText",
                    GuiGraphics.class,
                    List.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    Font.class);
            allConfigsClient = allConfigs.getMethod("client");
            showTrainMapOverlayField = allConfigsClient.getReturnType().getField("showTrainMapOverlay");
            configBoolGet = showTrainMapOverlayField.getType().getMethod("get");
            Class<?> entry = Class.forName("com.simibubi.create.compat.trainmap.TrainMapSync$TrainMapSyncEntry");
            Class<?> renderer = Class.forName("com.simibubi.create.compat.trainmap.TrainMapRenderer");
            Object rendererInstance = renderer.getField("INSTANCE").get(null);

            // Hot-path handles, adapted (asType) to the exact call-site
            // signatures below so invokeExact never boxes an argument.
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            renderAndPick = lookup.unreflect(manager.getMethod(
                            "renderAndPick", GuiGraphics.class, int.class, int.class, boolean.class, Rect2i.class))
                    .asType(MethodType.methodType(
                            List.class, GuiGraphics.class, int.class, int.class, boolean.class, Rect2i.class));
            renderToggleWidget = lookup.unreflect(
                            manager.getMethod("renderToggleWidget", GuiGraphics.class, int.class, int.class))
                    .asType(MethodType.methodType(void.class, GuiGraphics.class, int.class, int.class));
            isToggleWidgetHovered = lookup.unreflect(
                            manager.getMethod("isToggleWidgetHovered", int.class, int.class, int.class, int.class))
                    .asType(MethodType.methodType(boolean.class, int.class, int.class, int.class, int.class));
            syncCurrentData =
                    lookup.unreflectGetter(sync.getField("currentData")).asType(MethodType.methodType(Map.class));
            entryDimensions = lookup.unreflectGetter(entry.getField("dimensions"))
                    .asType(MethodType.methodType(List.class, Object.class));
            entryPositions = lookup.unreflectGetter(entry.getField("positions"))
                    .asType(MethodType.methodType(Object[].class, Object.class));
            entryGetPosition = lookup.unreflect(entry.getMethod("getPosition", int.class, boolean.class, double.class))
                    .asType(MethodType.methodType(Vec3.class, Object.class, int.class, boolean.class, double.class));
            rendererGetPixel = lookup.unreflect(renderer.getMethod("getPixel", int.class, int.class))
                    .bindTo(rendererInstance)
                    .asType(MethodType.methodType(int.class, int.class, int.class));
            available = true;
            LOGGER.info("[Bridge JM] Create train map detected: sync and rendering wired to the SharedJourney map.");
        } catch (ClassNotFoundException e) {
            // Create absent (or without a train map): nothing to do.
            available = false;
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Create train map incompatible: {}", t.toString());
            available = false;
        }
        return available;
    }
}
