package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.common.network.WaypointPayloads;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;

import net.neoforged.neoforge.network.PacketDistributor;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative public waypoints (shared with every player).
 * Clients send upserts/removals; the server validates, persists them in
 * the world folder and broadcasts the change to everyone. The full list
 * is sent to each player at login. Visibility is never part of the shared
 * state: hiding a public waypoint is a per-client choice.
 */
public final class PublicWaypointService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String FILE_NAME = "waypoints_public.json";
    private static final int MAX_NAME_LENGTH = 48;
    /** Sanity cap: a runaway client cannot fill the disk. */
    private static final int MAX_WAYPOINTS = 1024;

    /** The payload record carries exactly the shared fields: reused as storage. */
    private static final Map<UUID, WaypointPayloads.PublicWaypointPayload> WAYPOINTS = new ConcurrentHashMap<>();

    private static Path file;

    private PublicWaypointService() {}

    // ------------------------------------------------------------------ lifecycle

    public static void init(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("sharedjourney");
        file = root.resolve(FILE_NAME);
        load();
    }

    public static void shutdown() {
        save();
        WAYPOINTS.clear();
        file = null;
    }

    /** Full send at login: one upsert per waypoint. */
    public static void sendAllTo(ServerPlayer player) {
        for (WaypointPayloads.PublicWaypointPayload wp : WAYPOINTS.values()) {
            PacketDistributor.sendToPlayer(player, wp);
        }
    }

    // ------------------------------------------------------------------ client requests

    public static void handleUpsert(Player player, WaypointPayloads.PublicWaypointPayload payload) {
        WaypointPayloads.PublicWaypointPayload sanitized = sanitize(payload);
        if (sanitized == null) {
            return;
        }

        boolean isNew = !WAYPOINTS.containsKey(sanitized.id());
        if (isNew && WAYPOINTS.size() >= MAX_WAYPOINTS) {
            LOGGER.warn(
                    "Public waypoint limit reached ({}), upsert from {} ignored", MAX_WAYPOINTS, playerName(player));
            return;
        }

        WAYPOINTS.put(sanitized.id(), sanitized);
        save();
        PacketDistributor.sendToAllPlayers(sanitized);
    }

    public static void handleRemove(Player player, WaypointPayloads.PublicWaypointRemovePayload payload) {
        if (WAYPOINTS.remove(payload.id()) == null) {
            return;
        }

        save();
        PacketDistributor.sendToAllPlayers(payload);
    }

    /** Rejects unusable data, trims and caps what can be. */
    private static WaypointPayloads.PublicWaypointPayload sanitize(WaypointPayloads.PublicWaypointPayload p) {
        String name = p.name() == null ? "" : p.name().trim();
        if (name.isEmpty()) {
            return null;
        }

        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        return new WaypointPayloads.PublicWaypointPayload(
                p.id(), name, p.dimension(), p.x(), p.y(), p.z(), 0xFFFFFF & p.colorRgb());
    }

    private static String playerName(Player player) {
        return player == null ? "?" : player.getGameProfile().getName();
    }

    // ------------------------------------------------------------------ IO

    private static void load() {
        WAYPOINTS.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }

        try {
            JsonElement root = GSON.fromJson(Files.readString(file), JsonElement.class);
            if (root == null || !root.isJsonArray()) {
                return;
            }

            for (JsonElement el : root.getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                ResourceLocation dim =
                        ResourceLocation.tryParse(o.get("dimension").getAsString());
                if (dim == null) {
                    continue;
                }

                WaypointPayloads.PublicWaypointPayload wp = new WaypointPayloads.PublicWaypointPayload(
                        UUID.fromString(o.get("id").getAsString()),
                        o.get("name").getAsString(),
                        dim,
                        o.get("x").getAsInt(),
                        o.get("y").getAsInt(),
                        o.get("z").getAsInt(),
                        o.get("color").getAsInt());
                WAYPOINTS.put(wp.id(), wp);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read public waypoints", e);
        }
    }

    private static void save() {
        if (file == null) {
            return;
        }

        JsonArray arr = new JsonArray();
        for (WaypointPayloads.PublicWaypointPayload wp : WAYPOINTS.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", wp.id().toString());
            o.addProperty("name", wp.name());
            o.addProperty("dimension", wp.dimension().toString());
            o.addProperty("x", wp.x());
            o.addProperty("y", wp.y());
            o.addProperty("z", wp.z());
            o.addProperty("color", wp.colorRgb());
            arr.add(o);
        }

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(arr));
        } catch (IOException e) {
            LOGGER.error("Failed to save public waypoints", e);
        }
    }
}
