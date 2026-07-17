package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.common.network.WaypointPayloads;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Banner waypoints, world-shared and READ-ONLY from the network's point of
 * view: unlike {@link PublicWaypointService}, no client ever requests an
 * upsert/removal here — the server alone detects them
 * ({@code BannerWaypointEvents}: a NAMED banner, renamed at an anvil
 * before being placed) and broadcasts to everyone. Persisted in the world
 * folder so they survive a restart without re-scanning every chunk.
 */
public final class BannerWaypointService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<UUID, WaypointPayloads.BannerWaypointPayload> WAYPOINTS = new ConcurrentHashMap<>();

    private static Path file;

    private BannerWaypointService() {}

    // ------------------------------------------------------------------ lifecycle

    public static void init(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("sharedjourney");
        file = root.resolve("waypoints_banners.json");
        load();
    }

    public static void shutdown() {
        WAYPOINTS.clear();
        file = null;
    }

    /** Full send at login: one upsert per known banner waypoint. */
    public static void sendAllTo(ServerPlayer player) {
        for (WaypointPayloads.BannerWaypointPayload wp : WAYPOINTS.values()) {
            PacketDistributor.sendToPlayer(player, wp);
        }
    }

    // ------------------------------------------------------------------ world events

    /** A NAMED banner was placed: register/update its waypoint and broadcast it. */
    public static void onPlace(ResourceKey<Level> dimension, BlockPos pos, String name, int colorRgb) {
        if (name == null || name.isBlank()) {
            return;
        }

        WaypointPayloads.BannerWaypointPayload wp = new WaypointPayloads.BannerWaypointPayload(
                bannerId(dimension, pos),
                name,
                dimension.location(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                colorRgb & 0xFFFFFF);
        WAYPOINTS.put(wp.id(), wp);
        save();
        PacketDistributor.sendToAllPlayers(wp);
    }

    /** A tracked banner was broken: drop its waypoint and broadcast the removal. */
    public static void onBreak(ResourceKey<Level> dimension, BlockPos pos) {
        UUID id = bannerId(dimension, pos);
        if (WAYPOINTS.remove(id) == null) {
            return;
        }

        save();
        PacketDistributor.sendToAllPlayers(new WaypointPayloads.BannerWaypointRemovePayload(id));
    }

    /** Deterministic id from the banner's position: stable across restarts. */
    private static UUID bannerId(ResourceKey<Level> dimension, BlockPos pos) {
        String key = "banner:" + dimension.location() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
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

                WaypointPayloads.BannerWaypointPayload wp = new WaypointPayloads.BannerWaypointPayload(
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
            LOGGER.error("Failed to read banner waypoints", e);
        }
    }

    private static void save() {
        if (file == null) {
            return;
        }

        JsonArray arr = new JsonArray();
        for (WaypointPayloads.BannerWaypointPayload wp : WAYPOINTS.values()) {
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
            LOGGER.error("Failed to save banner waypoints", e);
        }
    }
}
