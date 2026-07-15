package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.common.config.LayersServerConfig;
import fr.cheesegrinder.sharedjourney.common.config.PrivacyServerConfig;
import fr.cheesegrinder.sharedjourney.common.config.ServerConfig;
import fr.cheesegrinder.sharedjourney.common.config.SyncServerConfig;
import fr.cheesegrinder.sharedjourney.common.config.WaypointServerConfig;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import net.neoforged.neoforge.network.PacketDistributor;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server side of the in-game ops config tab (fullscreen settings screen):
 * answers snapshot requests and applies edits — both restricted to
 * permission level 2+ (same bar as /sj admin). An apply sanitizes every
 * value into its config bounds (a hostile client can send anything),
 * persists the spec and re-broadcasts the active layers to every player.
 */
public final class OpsConfigService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private OpsConfigService() {}

    // ------------------------------------------------------------------ payload handlers

    public static void handleRequest(Player playerRaw, Payloads.OpsConfigRequestPayload payload) {
        if (!(playerRaw instanceof ServerPlayer player) || !player.hasPermissions(2)) {
            return;
        }

        PacketDistributor.sendToPlayer(player, snapshot());
    }

    public static void handleUpdate(Player playerRaw, Payloads.OpsConfigPayload payload) {
        if (!(playerRaw instanceof ServerPlayer player) || !player.hasPermissions(2)) {
            LOGGER.warn("SharedJourney: ignored an ops config update from a non-op sender");
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        apply(payload);
        ServerConfig.SPEC.save();
        ServerConfig.invalidateCache();
        SyncService.broadcastLayerSettings(server);
        // Echo the authoritative state (post-sanitization) to the editor.
        PacketDistributor.sendToPlayer(player, snapshot());
        LOGGER.info(
                "SharedJourney: server config updated in-game by {}",
                player.getGameProfile().getName());
    }

    // ------------------------------------------------------------------ snapshot / apply

    /** Editable server config, as currently loaded. */
    public static Payloads.OpsConfigPayload snapshot() {
        return new Payloads.OpsConfigPayload(
                new ArrayList<>(LayersServerConfig.DEFAULT_LAYERS.get()),
                new ArrayList<>(LayersServerConfig.SHARED_LAYERS.get()),
                new ArrayList<>(LayersServerConfig.CAVE_BANDS.get()),
                SyncServerConfig.PUSH_RADIUS_REGIONS.get(),
                SyncServerConfig.MAX_KB_PER_SECOND_PER_PLAYER.get(),
                SyncServerConfig.SYNC_RATE_TICKS.get(),
                SyncServerConfig.ALLOW_ON_DEMAND_REQUESTS.get(),
                SyncServerConfig.RADAR_MAX_RADIUS.get(),
                WaypointServerConfig.DEATH_WAYPOINTS_ENABLED.get(),
                WaypointServerConfig.WAYPOINT_STORAGE.get() == WaypointServerConfig.Storage.SERVER,
                PrivacyServerConfig.HIDDEN_AREA_POLICY.get().name(),
                PrivacyServerConfig.QUARANTINE_RADIUS_CHUNKS.get(),
                PrivacyServerConfig.QUARANTINE_DRAIN_MINUTES.get());
    }

    /**
     * Applies an update, dropping invalid layer entries and clamping every
     * numeric value into the same bounds as the config definitions.
     */
    private static void apply(Payloads.OpsConfigPayload p) {
        List<String> defaultLayers = p.defaultLayers().stream()
                .filter(LayersServerConfig::isValidLayer)
                .toList();
        List<String> sharedLayers = p.sharedLayers().stream()
                .filter(LayersServerConfig::isValidMapping)
                .toList();
        List<Integer> caveBands =
                p.caveBands().stream().filter(b -> b >= -8 && b <= 20).toList();
        LayersServerConfig.DEFAULT_LAYERS.set(defaultLayers);
        LayersServerConfig.SHARED_LAYERS.set(sharedLayers);
        LayersServerConfig.CAVE_BANDS.set(caveBands);

        SyncServerConfig.PUSH_RADIUS_REGIONS.set(Math.clamp(p.pushRadiusRegions(), 0, 8));
        SyncServerConfig.MAX_KB_PER_SECOND_PER_PLAYER.set(Math.clamp(p.maxKbPerSecondPerPlayer(), 32, 8192));
        SyncServerConfig.SYNC_RATE_TICKS.set(Math.clamp(p.syncRateTicks(), 5, 1200));
        SyncServerConfig.ALLOW_ON_DEMAND_REQUESTS.set(p.allowOnDemandRequests());
        SyncServerConfig.RADAR_MAX_RADIUS.set(Math.clamp(p.radarMaxRadius(), 0, 128));

        WaypointServerConfig.DEATH_WAYPOINTS_ENABLED.set(p.deathWaypointsEnabled());
        WaypointServerConfig.WAYPOINT_STORAGE.set(
                p.waypointStorageServer() ? WaypointServerConfig.Storage.SERVER : WaypointServerConfig.Storage.CLIENT);

        PrivacyServerConfig.HIDDEN_AREA_POLICY.set(parsePolicy(p.hiddenAreaPolicy()));
        PrivacyServerConfig.QUARANTINE_RADIUS_CHUNKS.set(Math.clamp(p.quarantineRadiusChunks(), 1, 32));
        PrivacyServerConfig.QUARANTINE_DRAIN_MINUTES.set(Math.clamp(p.quarantineDrainMinutes(), 1, 120));
    }

    /** Policy by name; an unknown value keeps the current setting. */
    private static PrivacyServerConfig.HiddenAreaPolicy parsePolicy(String name) {
        try {
            return PrivacyServerConfig.HiddenAreaPolicy.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PrivacyServerConfig.HIDDEN_AREA_POLICY.get();
        }
    }
}
