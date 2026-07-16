package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.api.MapApi;
import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * Server-side backing of the public {@link MapApi} facade: null-safe
 * adapters over {@link MapManager} (the engine may not be initialized —
 * world still loading — or already shut down) and {@link SyncService}.
 * Wired into {@link MapApi.Hooks} by {@code SharedJourney} at startup.
 */
public final class MapApiService {

    private MapApiService() {}

    /** {@link MapApi#isChunkRendered}: false while the engine is down. */
    public static boolean isChunkRendered(ServerLevel level, int chunkX, int chunkZ) {
        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return false;
        }

        return mgr.isChunkRendered(level, chunkX, chunkZ);
    }

    /** {@link MapApi#rerenderChunk}: dropped while the engine is down. */
    public static void rerenderChunk(ServerLevel level, int chunkX, int chunkZ) {
        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return;
        }

        mgr.enqueueChunk(level, chunkX, chunkZ);
    }

    /** {@link MapApi#regionVersion}: -1 on unknown layer id or engine down. */
    public static long regionVersion(ResourceKey<Level> dimension, String layerId, int caveBand, int rx, int rz) {
        MapManager mgr = MapManager.get();
        MapLayer layer = parseLayer(layerId);
        if (mgr == null || layer == null) {
            return -1L;
        }

        int band = layer == MapLayer.CAVE ? caveBand : 0;
        return mgr.versionOf(new RegionKey(dimension, layer, band, rx, rz));
    }

    /** Layer string id ("day", "cave"...) to the internal enum, or null. */
    private static MapLayer parseLayer(String layerId) {
        if (layerId == null) {
            return null;
        }

        try {
            return MapLayer.valueOf(layerId.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
