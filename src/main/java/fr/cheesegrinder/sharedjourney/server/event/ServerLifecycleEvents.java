package fr.cheesegrinder.sharedjourney.server.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.event.LayerRegisterEvent;
import fr.cheesegrinder.sharedjourney.server.command.MapCommands;
import fr.cheesegrinder.sharedjourney.server.service.CaveTracker;
import fr.cheesegrinder.sharedjourney.server.service.MapManager;
import fr.cheesegrinder.sharedjourney.server.service.PublicWaypointService;
import fr.cheesegrinder.sharedjourney.server.service.RegenService;
import fr.cheesegrinder.sharedjourney.server.service.SyncService;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server lifecycle: map engine init/shutdown, service ticking, periodic
 * saves and command registration.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID)
public final class ServerLifecycleEvents {

    private ServerLifecycleEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Collect custom layers declared by other mods (MOD bus).
        LayerRegisterEvent layerEvent = new LayerRegisterEvent();
        ModLoader.postEvent(layerEvent);
        MapManager.init(event.getServer(), layerEvent.getCustomLayers());
        PublicWaypointService.init(event.getServer());

        // Replay chunks loaded before the init (spawn chunks).
        ChunkEvents.replayEarlyChunks(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        RegenService.cancel();
        PublicWaypointService.shutdown();
        MapManager.shutdown();
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            var mgr = MapManager.get();
            if (mgr != null) {
                mgr.saveAllAsync();
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        CaveTracker.tick(event.getServer());
        RegenService.tick(event.getServer());
        SyncService.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MapCommands.register(event.getDispatcher());
    }
}
