package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.config.LayersServerConfig;
import fr.cheesegrinder.sharedjourney.common.util.UndergroundCheck;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Cave anti-exploit: CAVE bands are only painted where a player actually
 * went underground. Periodically scans player positions and unlocks the
 * matching band around them (MapManager.unlockCave). Without a player
 * passing through, a band stays empty (gray background client-side), even
 * after a /sj admin regen full.
 */
public final class CaveTracker {

    /** One scan per second is enough: you cannot cross a chunk faster in a cave. */
    private static final int SCAN_INTERVAL_TICKS = 20;
    /** Radius (in chunks) unlocked around an underground player. */
    private static final int UNLOCK_RADIUS_CHUNKS = 2;

    private CaveTracker() {}

    /** Called every server tick (main thread). */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % SCAN_INTERVAL_TICKS != 0) {
            return;
        }

        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            scanPlayer(mgr, player);
        }
    }

    private static void scanPlayer(MapManager mgr, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!LayersServerConfig.layersFor(level.dimension()).contains(MapLayer.CAVE)) {
            return;
        }

        int band = Math.floorDiv(player.getBlockY(), 16);
        if (!LayersServerConfig.CAVE_BANDS.get().contains(band)) {
            return;
        }

        // Underground only (solid block above the eyes — fluids and leaves
        // excluded): walking on the surface, swimming or diving in open sea
        // must not reveal the crossed band. Same rule as the client-side
        // minimap auto-switch. Exception: spectators (admins doing
        // cartography) unlock without this rule.
        if (!player.isSpectator() && !UndergroundCheck.isUnderground(level, player)) {
            return;
        }

        ChunkPos center = player.chunkPosition();
        for (int dx = -UNLOCK_RADIUS_CHUNKS; dx <= UNLOCK_RADIUS_CHUNKS; dx++) {
            for (int dz = -UNLOCK_RADIUS_CHUNKS; dz <= UNLOCK_RADIUS_CHUNKS; dz++) {
                mgr.unlockCave(level, band, center.x + dx, center.z + dz);
            }
        }
    }
}
