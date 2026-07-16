package fr.cheesegrinder.sharedjourney.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Public SERVER-side read/actions facade over the map engine. The engine
 * lives in {@code server.service.MapManager}, which {@code api} cannot
 * reference (layering: api has no dependencies) — same static-hook
 * indirection as {@link WaypointApi}, wired by the common entry point
 * ({@code SharedJourney}) at startup.
 *
 * <p>Every call is safe before the engine is initialized (world still
 * loading) or after shutdown: queries return their "unknown" value
 * (false / -1), actions are dropped.
 *
 * <p>Layers are addressed by their string id: {@code "day"}, {@code
 * "night"}, {@code "topo"}, {@code "biome"}, {@code "cave"}, or the id of
 * a custom layer registered through {@link
 * fr.cheesegrinder.sharedjourney.api.event.LayerRegisterEvent}.
 *
 * <p>Player positions are deliberately NOT exposed: a server-side consumer
 * already has the player list; what the map adds is only the "hidden from
 * the map" preference, exposed as {@link #isHiddenFromMap}.
 */
public final class MapApi {

    /** Hook indirection, wired by {@code SharedJourney} at startup. */
    public static final class Hooks {

        @FunctionalInterface
        public interface ChunkQuery {
            boolean test(ServerLevel level, int chunkX, int chunkZ);
        }

        @FunctionalInterface
        public interface ChunkAction {
            void accept(ServerLevel level, int chunkX, int chunkZ);
        }

        @FunctionalInterface
        public interface RegionVersionQuery {
            long get(ResourceKey<Level> dimension, String layerId, int caveBand, int rx, int rz);
        }

        public static ChunkQuery isChunkRendered = (level, cx, cz) -> false;
        public static ChunkAction rerenderChunk = (level, cx, cz) -> {};
        public static RegionVersionQuery regionVersion = (dim, layer, band, rx, rz) -> -1L;
        public static Predicate<UUID> isHiddenFromMap = id -> false;

        private Hooks() {}
    }

    private MapApi() {}

    /** Has this chunk ever been rendered on the map (any layer)? */
    public static boolean isChunkRendered(ServerLevel level, int chunkX, int chunkZ) {
        return Hooks.isChunkRendered.test(level, chunkX, chunkZ);
    }

    /**
     * Queues a re-render of a chunk (all its layers), as if a block had
     * changed there. Asynchronous and throttled by the engine; the result
     * reaches the clients through the normal delta sync.
     */
    public static void rerenderChunk(ServerLevel level, int chunkX, int chunkZ) {
        Hooks.rerenderChunk.accept(level, chunkX, chunkZ);
    }

    /**
     * Version of a region tile (grows on every rewrite), or -1 if the
     * region was never rendered. {@code caveBand} is ignored for layers
     * other than {@code "cave"}; regions are 512x512 blocks ({@code rx =
     * floorDiv(blockX, 512)}).
     */
    public static long regionVersion(ResourceKey<Level> dimension, String layerId, int caveBand, int rx, int rz) {
        return Hooks.regionVersion.get(dimension, layerId, caveBand, rx, rz);
    }

    /**
     * Has this player asked to be hidden from the other players' maps?
     * Combine with the server's player list to reproduce what the map
     * radar shows.
     */
    public static boolean isHiddenFromMap(UUID playerId) {
        return Hooks.isHiddenFromMap.test(playerId);
    }
}
