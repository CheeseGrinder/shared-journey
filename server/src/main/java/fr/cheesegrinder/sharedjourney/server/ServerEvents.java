package fr.cheesegrinder.sharedjourney.server;

import com.mojang.logging.LogUtils;
import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.event.LayerRegisterEvent;
import fr.cheesegrinder.sharedjourney.common.config.CommonConfig;
import fr.cheesegrinder.sharedjourney.common.config.ServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/** Branche le moteur de carte sur les événements NeoForge. */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID)
public final class ServerEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Chunks chargés AVANT l'init du moteur (chunks de spawn, chargés pendant
     * le démarrage du serveur) : mémorisés puis rejoués au ServerStarted.
     */
    private static final List<EarlyChunk> EARLY_CHUNKS = new ArrayList<>();
    private record EarlyChunk(ResourceKey<Level> dim, int cx, int cz) {}

    // ---------------------------------------------------------------- cycle de vie

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Collecte des couches custom déclarées par d'autres mods (bus MOD).
        LayerRegisterEvent layerEvent = new LayerRegisterEvent();
        ModLoader.postEvent(layerEvent);
        MapManager.init(event.getServer(), layerEvent.getCustomLayers());

        // Rejoue les chunks chargés avant l'init (chunks de spawn).
        MapManager mgr = MapManager.get();
        synchronized (EARLY_CHUNKS) {
            for (EarlyChunk e : EARLY_CHUNKS) {
                ServerLevel level = event.getServer().getLevel(e.dim());
                if (level != null && !mgr.isChunkRendered(level, e.cx(), e.cz())) {
                    mgr.enqueueChunk(level, e.cx(), e.cz());
                }
            }
            EARLY_CHUNKS.clear();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        RegenService.cancel();
        MapManager.shutdown();
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            MapManager mgr = MapManager.get();
            if (mgr != null) mgr.saveAllAsync();
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        RegenService.tick(event.getServer());
        SyncService.tick(event.getServer());
    }

    // ---------------------------------------------------------------- joueurs

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) SyncService.onPlayerJoin(sp);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) SyncService.onPlayerLeave(sp);
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) SyncService.sendLayerSettings(sp);
    }

    // ---------------------------------------------------------------- génération & dirty marking

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
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
        if (event.isNewChunk() || !mgr.isChunkRendered(level, pos.x, pos.z)) {
            mgr.enqueueChunk(level, pos.x, pos.z);
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) { markDirty(event); }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) { markDirty(event); }

    /** Pistons : le train de blocs déplacés peut traverser une frontière de chunk. */
    @SubscribeEvent
    public static void onPistonMove(PistonEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        MapManager mgr = MapManager.get();
        if (mgr == null) return;
        BlockPos pos = event.getPos();
        mgr.enqueueChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
        BlockPos far = pos.relative(event.getDirection(), 13); // portée max d'un piston
        if ((far.getX() >> 4) != (pos.getX() >> 4) || (far.getZ() >> 4) != (pos.getZ() >> 4)) {
            mgr.enqueueChunk(level, far.getX() >> 4, far.getZ() >> 4);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        MapManager mgr = MapManager.get();
        if (mgr == null) return;
        for (BlockPos pos : event.getAffectedBlocks()) {
            mgr.enqueueChunk(level, pos.getX() >> 4, pos.getZ() >> 4); // dédupliqué
        }
    }

    /** Pousse d'arbre / gros champignon : la canopée peut déborder sur les voisins. */
    @SubscribeEvent
    public static void onTreeGrow(BlockGrowFeatureEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        MapManager mgr = MapManager.get();
        if (mgr == null) return;
        BlockPos pos = event.getPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                mgr.enqueueChunk(level, (pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz);
            }
        }
    }

    private static void markDirty(BlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        MapManager mgr = MapManager.get();
        if (mgr == null) return;
        int cx = event.getPos().getX() >> 4;
        int cz = event.getPos().getZ() >> 4;
        mgr.enqueueChunk(level, cx, cz);
        if (CommonConfig.DEBUG_LOGGING.get()) {
            LOGGER.info("SharedJourney : chunk {},{} marqué à re-rendre ({})",
                    cx, cz, level.dimension().location());
        }
    }

    // ---------------------------------------------------------------- commandes & config

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MapCommands.register(event.getDispatcher());
    }

    @EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class ConfigEvents {
        @SubscribeEvent
        public static void onConfigReload(ModConfigEvent.Reloading event) {
            if (event.getConfig().getSpec() == ServerConfig.SPEC) {
                ServerConfig.invalidateCache();
                var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    server.execute(() -> SyncService.broadcastLayerSettings(server));
                }
            }
        }
    }
}
