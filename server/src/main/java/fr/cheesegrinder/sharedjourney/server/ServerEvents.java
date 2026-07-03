package fr.cheesegrinder.sharedjourney.server;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.event.LayerRegisterEvent;
import fr.cheesegrinder.sharedjourney.common.config.ServerConfig;
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
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Branche le moteur de carte sur les événements NeoForge. */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID)
public final class ServerEvents {

    // ---------------------------------------------------------------- cycle de vie

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Collecte des couches custom déclarées par d'autres mods (bus MOD).
        LayerRegisterEvent layerEvent = new LayerRegisterEvent();
        ModLoader.postEvent(layerEvent);
        MapManager.init(event.getServer(), layerEvent.getCustomLayers());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
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
        MapManager mgr = MapManager.get();
        if (mgr == null) return;
        ChunkPos pos = event.getChunk().getPos();
        if (event.isNewChunk() || !mgr.isChunkRendered(level, pos.x, pos.z)) {
            mgr.enqueueChunk(level, pos.x, pos.z);
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) { markDirty(event); }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) { markDirty(event); }

    private static void markDirty(BlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        MapManager mgr = MapManager.get();
        if (mgr == null) return;
        mgr.enqueueChunk(level, event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
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
