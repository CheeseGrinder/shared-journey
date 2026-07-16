package fr.cheesegrinder.sharedjourney.server.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.common.config.CommonConfig;
import fr.cheesegrinder.sharedjourney.server.service.MapManager;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunk and terrain events: generation (exploration, Chunky pregeneration)
 * and "dirty" marking on every world modification (break/place, pistons,
 * explosions, tree growth).
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID)
public final class ChunkEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Chunks loaded BEFORE the engine init (spawn chunks, loaded during
     * server startup): remembered, then replayed on ServerStarted.
     */
    private static final List<EarlyChunk> EARLY_CHUNKS = new ArrayList<>();

    private record EarlyChunk(ResourceKey<Level> dim, int cx, int cz) {}

    private ChunkEvents() {}

    /** Replays chunks buffered before the engine init (called on ServerStarted). */
    public static void replayEarlyChunks(MinecraftServer server) {
        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return;
        }

        synchronized (EARLY_CHUNKS) {
            for (EarlyChunk e : EARLY_CHUNKS) {
                ServerLevel level = server.getLevel(e.dim());
                if (level != null && !mgr.isChunkRendered(level, e.cx(), e.cz())) {
                    mgr.enqueueChunk(level, e.cx(), e.cz());
                }
            }
            EARLY_CHUNKS.clear();
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ChunkPos pos = event.getChunk().getPos();
        MapManager mgr = MapManager.get();
        if (mgr == null) {
            // Engine not ready yet (spawn chunks): remember for replay.
            synchronized (EARLY_CHUNKS) {
                if (EARLY_CHUNKS.size() < 4096) {
                    EARLY_CHUNKS.add(new EarlyChunk(level.dimension(), pos.x, pos.z));
                }
            }
            return;
        }
        if (event.isNewChunk() && event.getChunk() instanceof LevelChunk fullChunk) {
            // Freshly generated chunk (exploration or Chunky pregeneration):
            // immediate render from the event's reference. Pregenerated
            // chunks are unloaded as soon as they are generated — a deferred
            // resolution on the next tick (getChunkNow) would systematically
            // miss them.
            mgr.renderNow(level, fullChunk);
        } else if (event.isNewChunk() || !mgr.isChunkRendered(level, pos.x, pos.z)) {
            mgr.enqueueChunk(level, pos.x, pos.z);
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        markDirty(event);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        markDirty(event);
    }

    /**
     * Programmatic modifications (no entity): Create rails placed by
     * snap/extension, machines, mods... Break/EntityPlace do not see them;
     * the neighbor notification accompanies every setBlock with UPDATE. The
     * cost is absorbed by the dirty chunk queue's deduplication.
     */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        markDirty(event);
    }

    /** Pistons: the train of moved blocks can cross a chunk border. */
    @SubscribeEvent
    public static void onPistonMove(PistonEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return;
        }

        BlockPos pos = event.getPos();
        mgr.enqueueChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
        BlockPos far = pos.relative(event.getDirection(), 13); // max piston reach
        if ((far.getX() >> 4) != (pos.getX() >> 4) || (far.getZ() >> 4) != (pos.getZ() >> 4)) {
            mgr.enqueueChunk(level, far.getX() >> 4, far.getZ() >> 4);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return;
        }

        // 3x3 around each affected chunk: the blast relights its borders
        // (see markDirty); everything is deduplicated by the queue.
        for (BlockPos pos : event.getAffectedBlocks()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    mgr.enqueueChunk(level, (pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz);
                }
            }
        }
    }

    /** Tree / huge mushroom growth: the canopy can spill over onto neighbors. */
    @SubscribeEvent
    public static void onTreeGrow(BlockGrowFeatureEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return;
        }

        BlockPos pos = event.getPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                mgr.enqueueChunk(level, (pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz);
            }
        }
    }

    private static void markDirty(BlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return;
        }

        int cx = event.getPos().getX() >> 4;
        int cz = event.getPos().getZ() >> 4;
        // The whole 3x3 neighborhood: block light travels up to 15 blocks,
        // so a change near a border also relights the neighboring chunks'
        // pixels (NIGHT/CAVE shading) — placing a torch used to leave the
        // neighbors dark. The queue dedup and the unchanged-pixel skip in
        // MapManager.writeChunk absorb the extra renders.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                mgr.enqueueChunk(level, cx + dx, cz + dz);
            }
        }
        if (CommonConfig.DEBUG_LOGGING.get()) {
            LOGGER.info(
                    "SharedJourney: chunk {},{} (+neighbors) marked to re-render ({})",
                    cx,
                    cz,
                    level.dimension().location());
        }
    }
}
