package fr.cheesegrinder.sharedjourney;

import fr.cheesegrinder.sharedjourney.api.WaypointApi;
import fr.cheesegrinder.sharedjourney.api.client.MapMarkerApi;
import fr.cheesegrinder.sharedjourney.client.compat.JourneyMapBridge;
import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.net.ClientNetHandler;
import fr.cheesegrinder.sharedjourney.client.service.MapMarkerStore;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * CLIENT entry point (never loaded on a dedicated server).
 * Wires the client network handlers and the NeoForge config screen, and
 * initializes the JourneyMap bridge once ALL mods are constructed (so that
 * the annotation scan sees third-party mods' plugins).
 */
@Mod(value = SharedJourney.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = SharedJourney.MODID, value = Dist.CLIENT)
public class SharedJourneyClient {

    public SharedJourneyClient(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // Client-side handler wiring.
        Payloads.Hooks.clientLayerSettings = ClientNetHandler::handleLayerSettings;
        Payloads.Hooks.clientRegionData = ClientNetHandler::handleRegionData;
        Payloads.Hooks.clientHiddenPlayers = ClientNetHandler::handleHiddenPlayers;
        Payloads.Hooks.clientPlayerPositions = ClientNetHandler::handlePlayerPositions;
        Payloads.Hooks.clientRegenState = ClientNetHandler::handleRegenState;
        Payloads.Hooks.clientRegenChunks = ClientNetHandler::handleRegenChunks;
        Payloads.Hooks.clientRegenProgress = ClientNetHandler::handleRegenProgress;
        Payloads.Hooks.clientTrainPath = ClientNetHandler::handleTrainPath;
        Payloads.Hooks.clientPublicWaypoint = ClientNetHandler::handlePublicWaypoint;
        Payloads.Hooks.clientPublicWaypointRemove = ClientNetHandler::handlePublicWaypointRemove;
        Payloads.Hooks.clientPlayerWaypoint = ClientNetHandler::handlePlayerWaypoint;
        Payloads.Hooks.clientPlayerWaypointRemove = ClientNetHandler::handlePlayerWaypointRemove;
        Payloads.Hooks.clientBannerWaypoint = ClientNetHandler::handleBannerWaypoint;
        Payloads.Hooks.clientBannerWaypointRemove = ClientNetHandler::handleBannerWaypointRemove;
        Payloads.Hooks.clientOpsConfig = ClientNetHandler::handleOpsConfig;

        // Public API facade (spec: published once the waypoint model is stable).
        WaypointApi.Hooks.all = WaypointStore::all;
        WaypointApi.Hooks.forDimension = WaypointStore::forDimension;
        WaypointApi.Hooks.get = WaypointStore::get;
        WaypointApi.Hooks.add = WaypointStore::add;
        WaypointApi.Hooks.update = WaypointStore::update;
        WaypointApi.Hooks.remove = WaypointStore::remove;
        WaypointApi.Hooks.isShown = WaypointStore::isShown;
        WaypointApi.Hooks.groups = WaypointStore::groups;

        // Declarative map markers (screen-render API v2).
        MapMarkerApi.Hooks.register = MapMarkerStore::register;
        MapMarkerApi.Hooks.unregister = MapMarkerStore::unregister;
    }

    @SubscribeEvent
    static void onLoadComplete(FMLLoadCompleteEvent event) {
        // Spec §9: JourneyMap compatibility bridge (Waystones & co).
        event.enqueueWork(JourneyMapBridge::init);
    }
}
