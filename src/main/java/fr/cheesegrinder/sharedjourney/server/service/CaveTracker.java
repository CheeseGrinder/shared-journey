package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.config.ServerConfig;
import fr.cheesegrinder.sharedjourney.common.util.UndergroundCheck;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Anti-exploit des grottes : les bandes CAVE ne sont peintes que là où un
 * joueur est réellement allé sous terre. Scanne périodiquement la position
 * des joueurs et déverrouille la bande correspondante autour d'eux
 * (MapManager.unlockCave). Sans passage d'un joueur, une bande reste vide
 * (fond gris côté client), même après un /sj admin regen full.
 */
public final class CaveTracker {

    /** Un scan par seconde suffit : on ne traverse pas un chunk plus vite en grotte. */
    private static final int SCAN_INTERVAL_TICKS = 20;
    /** Rayon (en chunks) déverrouillé autour d'un joueur sous terre. */
    private static final int UNLOCK_RADIUS_CHUNKS = 2;

    private CaveTracker() {}

    /** Appelé chaque tick serveur (main thread). */
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
        if (!ServerConfig.layersFor(level.dimension()).contains(MapLayer.CAVE)) {
            return;
        }

        int band = Math.floorDiv(player.getBlockY(), 16);
        if (!ServerConfig.CAVE_BANDS.get().contains(band)) {
            return;
        }

        // Sous terre uniquement (bloc solide au-dessus des yeux — fluides et
        // feuilles exclus) : marcher en surface, nager ou plonger en mer
        // ouverte ne doit pas révéler la bande traversée. Même règle que la
        // bascule auto de la minimap côté client.
        if (!UndergroundCheck.isUnderground(level, player)) {
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
