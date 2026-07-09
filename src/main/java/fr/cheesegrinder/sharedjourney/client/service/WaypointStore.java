package fr.cheesegrinder.sharedjourney.client.service;

import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.api.event.WaypointEvent;
import fr.cheesegrinder.sharedjourney.client.config.WaypointClientConfig;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import net.neoforged.neoforge.common.NeoForge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local waypoints (spec §6.2): saved as JSON in the current server's cache
 * folder. Posts the API's WaypointEvents so that other mods (or the
 * JourneyMap bridge) can react/intercept.
 */
public final class WaypointStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<UUID, Waypoint> WAYPOINTS = new ConcurrentHashMap<>();
    private static Path file;

    private WaypointStore() {}

    // ------------------------------------------------------------------ session

    /**
     * Opens the session's waypoint file, in the same per-server cache folder
     * as the tiles. Requires {@link DiskCache#openSession()} to have run
     * first (see ClientSessionEvents).
     */
    public static void openSession() {
        Path root = DiskCache.sessionRoot();
        file = root == null ? null : root.resolve("waypoints.json");
        load();
    }

    public static void closeSession() {
        save();
        WAYPOINTS.clear();
        file = null;
    }

    // ------------------------------------------------------------------ CRUD

    public static List<Waypoint> all() {
        return new ArrayList<>(WAYPOINTS.values());
    }

    /** Waypoints displayable in a dimension: its own + the global ones. */
    public static List<Waypoint> forDimension(ResourceLocation dim) {
        return WAYPOINTS.values().stream()
                .filter(w -> w.type() == Waypoint.Type.GLOBAL || w.dimension().equals(dim))
                .toList();
    }

    public static Waypoint get(UUID id) {
        return WAYPOINTS.get(id);
    }

    /** Adds a waypoint. Returns false if a listener cancelled the addition. */
    public static boolean add(Waypoint wp) {
        WaypointEvent.Added event = new WaypointEvent.Added(wp);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return false;
        }

        WAYPOINTS.put(wp.id(), wp);
        save();
        return true;
    }

    public static void update(Waypoint wp) {
        WAYPOINTS.put(wp.id(), wp);
        NeoForge.EVENT_BUS.post(new WaypointEvent.Updated(wp));
        save();
    }

    public static void remove(UUID id) {
        Waypoint wp = WAYPOINTS.remove(id);
        if (wp != null) {
            NeoForge.EVENT_BUS.post(new WaypointEvent.Removed(wp));
            save();
        }
    }

    /** Forces the visibility of every waypoint of a dimension (+ globals). */
    public static void setAllVisible(ResourceLocation dim, boolean visible) {
        for (Waypoint wp : forDimension(dim)) {
            if (wp.visible() != visible) {
                update(wp.withVisible(visible));
            }
        }
    }

    /**
     * Removes the temp waypoints the player has reached (configurable
     * radius). Called periodically from the client tick.
     */
    public static void removeReachedTemp(Player player) {
        ResourceLocation dim = player.level().dimension().location();
        int radius = WaypointClientConfig.TEMP_WAYPOINT_RADIUS.get();
        long radiusSq = (long) radius * radius;
        for (Waypoint wp : all()) {
            if (wp.type() != Waypoint.Type.TEMP || !wp.dimension().equals(dim)) {
                continue;
            }

            double dx = player.getX() - (wp.x() + 0.5);
            double dy = player.getY() - wp.y();
            double dz = player.getZ() - (wp.z() + 0.5);
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                remove(wp.id());
            }
        }
    }

    /** Removes every waypoint of a given source (used by the bridge). */
    public static void removeBySource(String source) {
        List<UUID> ids = WAYPOINTS.values().stream()
                .filter(w -> w.source().equals(source))
                .map(Waypoint::id)
                .toList();
        ids.forEach(WaypointStore::remove);
    }

    // ------------------------------------------------------------------ IO

    private static void load() {
        WAYPOINTS.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }

        try {
            JsonArray arr = GSON.fromJson(Files.readString(file), JsonArray.class);
            if (arr == null) {
                return;
            }

            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                // Only user waypoints are persisted: those of bridged mods
                // (Waystones...) are resynchronized every session by their
                // mod. Also purges duplicates from older versions.
                if (o.has("source")
                        && !Waypoint.SOURCE_USER.equals(o.get("source").getAsString())) {
                    continue;
                }

                ResourceLocation dim =
                        ResourceLocation.tryParse(o.get("dimension").getAsString());
                if (dim == null) {
                    continue;
                }

                Waypoint wp = new Waypoint(
                        UUID.fromString(o.get("id").getAsString()),
                        o.get("name").getAsString(),
                        dim,
                        o.get("x").getAsInt(),
                        o.get("y").getAsInt(),
                        o.get("z").getAsInt(),
                        o.get("color").getAsInt(),
                        o.has("source") ? o.get("source").getAsString() : Waypoint.SOURCE_USER,
                        !o.has("visible") || o.get("visible").getAsBoolean(),
                        readType(o));
                WAYPOINTS.put(wp.id(), wp);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read waypoints", e);
        }
    }

    /** Waypoint type, DIMENSION by default (files from older versions). */
    private static Waypoint.Type readType(JsonObject o) {
        if (!o.has("type")) {
            return Waypoint.Type.DIMENSION;
        }

        try {
            return Waypoint.Type.valueOf(o.get("type").getAsString());
        } catch (IllegalArgumentException e) {
            return Waypoint.Type.DIMENSION;
        }
    }

    private static void save() {
        if (file == null) {
            return;
        }

        JsonArray arr = new JsonArray();
        for (Waypoint wp : WAYPOINTS.values()) {
            // Bridged mods' waypoints are volatile (see load()).
            if (!Waypoint.SOURCE_USER.equals(wp.source())) {
                continue;
            }

            JsonObject o = new JsonObject();
            o.addProperty("id", wp.id().toString());
            o.addProperty("name", wp.name());
            o.addProperty("dimension", wp.dimension().toString());
            o.addProperty("x", wp.x());
            o.addProperty("y", wp.y());
            o.addProperty("z", wp.z());
            o.addProperty("color", wp.colorRgb());
            o.addProperty("source", wp.source());
            o.addProperty("visible", wp.visible());
            o.addProperty("type", wp.type().name());
            arr.add(o);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(arr));
        } catch (IOException e) {
            LOGGER.error("Failed to save waypoints", e);
        }
    }
}
