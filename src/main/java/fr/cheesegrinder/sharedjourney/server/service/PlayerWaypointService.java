package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative PRIVATE waypoints, opt-in via
 * {@code waypoints.waypointStorage=SERVER} (the default). Unlike
 * {@link PublicWaypointService}, nothing here is ever shared between
 * players: each player's DIMENSION waypoints are persisted in their own
 * file and synced back to them only. Kept simple on purpose — no
 * in-memory cache, straight read/write of the requesting player's file —
 * edits are rare user actions, not a hot path (same precedent as
 * {@link PublicWaypointService}, which already blocks the main thread on
 * every edit).
 */
public final class PlayerWaypointService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int MAX_NAME_LENGTH = 48;
    private static final int MAX_GROUP_LENGTH = 32;
    /** Sanity cap per player: a runaway client cannot fill the disk. */
    private static final int MAX_WAYPOINTS_PER_PLAYER = 512;

    private static Path root;

    private PlayerWaypointService() {}

    // ------------------------------------------------------------------ lifecycle

    public static void init(MinecraftServer server) {
        root = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("sharedjourney")
                .resolve("waypoints");
    }

    public static void shutdown() {
        root = null;
    }

    /** Full send at login: one upsert per waypoint the player owns. */
    public static void sendAllTo(ServerPlayer player) {
        for (Payloads.PlayerWaypointPayload wp : load(player.getUUID())) {
            PacketDistributor.sendToPlayer(player, wp);
        }
    }

    // ------------------------------------------------------------------ client requests

    public static void handleUpsert(Player player, Payloads.PlayerWaypointPayload payload) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }

        Payloads.PlayerWaypointPayload sanitized = sanitize(payload);
        if (sanitized == null) {
            return;
        }

        Map<UUID, Payloads.PlayerWaypointPayload> waypoints = indexById(load(sp.getUUID()));
        if (!waypoints.containsKey(sanitized.id()) && waypoints.size() >= MAX_WAYPOINTS_PER_PLAYER) {
            LOGGER.warn(
                    "Player waypoint limit reached ({}) for {}, upsert ignored",
                    MAX_WAYPOINTS_PER_PLAYER,
                    sp.getGameProfile().getName());
            return;
        }

        waypoints.put(sanitized.id(), sanitized);
        save(sp.getUUID(), waypoints.values());
        PacketDistributor.sendToPlayer(sp, sanitized);
    }

    public static void handleRemove(Player player, Payloads.PlayerWaypointRemovePayload payload) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }

        Map<UUID, Payloads.PlayerWaypointPayload> waypoints = indexById(load(sp.getUUID()));
        if (waypoints.remove(payload.id()) == null) {
            return;
        }

        save(sp.getUUID(), waypoints.values());
        PacketDistributor.sendToPlayer(sp, payload);
    }

    private static Map<UUID, Payloads.PlayerWaypointPayload> indexById(List<Payloads.PlayerWaypointPayload> list) {
        Map<UUID, Payloads.PlayerWaypointPayload> map = new LinkedHashMap<>();
        for (Payloads.PlayerWaypointPayload wp : list) {
            map.put(wp.id(), wp);
        }
        return map;
    }

    /** Rejects unusable data, trims and caps what can be. */
    private static Payloads.PlayerWaypointPayload sanitize(Payloads.PlayerWaypointPayload p) {
        String name = p.name() == null ? "" : p.name().trim();
        if (name.isEmpty()) {
            return null;
        }

        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }

        String group = p.group() == null || p.group().isBlank()
                ? Waypoint.GROUP_DEFAULT
                : p.group().trim();
        if (group.length() > MAX_GROUP_LENGTH) {
            group = group.substring(0, MAX_GROUP_LENGTH);
        }

        return new Payloads.PlayerWaypointPayload(
                p.id(), name, p.dimension(), p.x(), p.y(), p.z(), 0xFFFFFF & p.colorRgb(), group);
    }

    // ------------------------------------------------------------------ IO

    private static Path fileFor(UUID playerId) {
        return root.resolve(playerId + ".json");
    }

    private static List<Payloads.PlayerWaypointPayload> load(UUID playerId) {
        List<Payloads.PlayerWaypointPayload> result = new ArrayList<>();
        if (root == null) {
            return result;
        }

        Path file = fileFor(playerId);
        if (!Files.exists(file)) {
            return result;
        }

        try {
            JsonElement root = GSON.fromJson(Files.readString(file), JsonElement.class);
            if (root == null || !root.isJsonArray()) {
                return result;
            }

            for (JsonElement el : root.getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                ResourceLocation dim =
                        ResourceLocation.tryParse(o.get("dimension").getAsString());
                if (dim == null) {
                    continue;
                }

                result.add(new Payloads.PlayerWaypointPayload(
                        UUID.fromString(o.get("id").getAsString()),
                        o.get("name").getAsString(),
                        dim,
                        o.get("x").getAsInt(),
                        o.get("y").getAsInt(),
                        o.get("z").getAsInt(),
                        o.get("color").getAsInt(),
                        o.has("group") ? o.get("group").getAsString() : Waypoint.GROUP_DEFAULT));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read waypoints of player {}", playerId, e);
        }
        return result;
    }

    private static void save(UUID playerId, Collection<Payloads.PlayerWaypointPayload> waypoints) {
        if (root == null) {
            return;
        }

        JsonArray arr = new JsonArray();
        for (Payloads.PlayerWaypointPayload wp : waypoints) {
            JsonObject o = new JsonObject();
            o.addProperty("id", wp.id().toString());
            o.addProperty("name", wp.name());
            o.addProperty("dimension", wp.dimension().toString());
            o.addProperty("x", wp.x());
            o.addProperty("y", wp.y());
            o.addProperty("z", wp.z());
            o.addProperty("color", wp.colorRgb());
            o.addProperty("group", wp.group());
            arr.add(o);
        }

        try {
            Files.createDirectories(root);
            Files.writeString(fileFor(playerId), GSON.toJson(arr));
        } catch (IOException e) {
            LOGGER.error("Failed to save waypoints of player {}", playerId, e);
        }
    }
}
