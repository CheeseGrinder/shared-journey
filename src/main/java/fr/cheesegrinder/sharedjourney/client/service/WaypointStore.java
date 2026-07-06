package fr.cheesegrinder.sharedjourney.client.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.api.event.WaypointEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.NeoForge;
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
 * Waypoints locaux (spec §6.2) : sauvegardés en JSON dans le dossier de cache
 * du serveur courant. Poste les WaypointEvent de l'API pour que d'autres mods
 * (ou le bridge JourneyMap) puissent réagir/intercepter.
 */
public final class WaypointStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<UUID, Waypoint> WAYPOINTS = new ConcurrentHashMap<>();
    private static Path file;

    private WaypointStore() {}

    // ------------------------------------------------------------------ session

    public static void openSession() {
        Minecraft mc = Minecraft.getInstance();
        String id;
        var server = mc.getCurrentServer();
        if (server != null) {
            id = server.ip.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
        } else if (mc.getSingleplayerServer() != null) {
            id = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName()
                    .toLowerCase().replaceAll("[^a-z0-9._-]", "_");
        } else {
            id = "unknown";
        }

        file = mc.gameDirectory.toPath().resolve("sharedjourney_cache").resolve(id).resolve("waypoints.json");
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

    public static List<Waypoint> forDimension(ResourceLocation dim) {
        return WAYPOINTS.values().stream().filter(w -> w.dimension().equals(dim)).toList();
    }

    public static Waypoint get(UUID id) {
        return WAYPOINTS.get(id);
    }

    /** Ajoute un waypoint. Retourne false si un listener a annulé l'ajout. */
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

    /** Supprime tous les waypoints d'une source donnée (utilisé par le bridge). */
    public static void removeBySource(String source) {
        List<UUID> ids = WAYPOINTS.values().stream()
                .filter(w -> w.source().equals(source)).map(Waypoint::id).toList();
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
                ResourceLocation dim = ResourceLocation.tryParse(o.get("dimension").getAsString());
                if (dim == null) {
                    continue;
                }

                Waypoint wp = new Waypoint(
                        UUID.fromString(o.get("id").getAsString()),
                        o.get("name").getAsString(), dim,
                        o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                        o.get("color").getAsInt(),
                        o.has("source") ? o.get("source").getAsString() : "user",
                        !o.has("visible") || o.get("visible").getAsBoolean());
                WAYPOINTS.put(wp.id(), wp);
            }
        } catch (Exception e) {
            LOGGER.error("Echec de lecture des waypoints", e);
        }
    }

    private static void save() {
        if (file == null) {
            return;
        }

        JsonArray arr = new JsonArray();
        for (Waypoint wp : WAYPOINTS.values()) {
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
            arr.add(o);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(arr));
        } catch (IOException e) {
            LOGGER.error("Echec de sauvegarde des waypoints", e);
        }
    }
}
