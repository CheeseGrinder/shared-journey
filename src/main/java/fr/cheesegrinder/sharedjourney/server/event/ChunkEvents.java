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
 * Événements de chunks et de terrain : génération (exploration, prégénération
 * Chunky) et marquage "dirty" à chaque modification du monde (casse/pose,
 * pistons, explosions, pousse d'arbres).
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID)
public final class ChunkEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Chunks chargés AVANT l'init du moteur (chunks de spawn, chargés pendant
     * le démarrage du serveur) : mémorisés puis rejoués au ServerStarted.
     */
    private static final List<EarlyChunk> EARLY_CHUNKS = new ArrayList<>();

    private record EarlyChunk(ResourceKey<Level> dim, int cx, int cz) {}

    private ChunkEvents() {}

    /** Rejoue les chunks bufferisés avant l'init du moteur (appelé au ServerStarted). */
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
            // Moteur pas encore prêt (chunks de spawn) : mémorise pour rejeu.
            synchronized (EARLY_CHUNKS) {
                if (EARLY_CHUNKS.size() < 4096) {
                    EARLY_CHUNKS.add(new EarlyChunk(level.dimension(), pos.x, pos.z));
                }
            }
            return;
        }
        if (event.isNewChunk() && event.getChunk() instanceof LevelChunk fullChunk) {
            // Chunk fraîchement généré (exploration ou prégénération Chunky) :
            // rendu immédiat depuis la référence de l'événement. Les chunks
            // prégénérés sont déchargés sitôt générés — une résolution différée
            // au tick suivant (getChunkNow) les manquerait systématiquement.
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

    /** Pistons : le train de blocs déplacés peut traverser une frontière de chunk. */
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
        BlockPos far = pos.relative(event.getDirection(), 13); // portée max d'un piston
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

        for (BlockPos pos : event.getAffectedBlocks()) {
            mgr.enqueueChunk(level, pos.getX() >> 4, pos.getZ() >> 4); // dédupliqué
        }
    }

    /** Pousse d'arbre / gros champignon : la canopée peut déborder sur les voisins. */
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
        mgr.enqueueChunk(level, cx, cz);
        if (CommonConfig.DEBUG_LOGGING.get()) {
            LOGGER.info(
                    "SharedJourney : chunk {},{} marqué à re-rendre ({})",
                    cx,
                    cz,
                    level.dimension().location());
        }
    }
}
