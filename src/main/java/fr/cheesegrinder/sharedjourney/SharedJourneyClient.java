package fr.cheesegrinder.sharedjourney;

import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.net.ClientNetHandler;
import fr.cheesegrinder.sharedjourney.client.compat.JourneyMapBridge;
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
 * Point d'entrée CLIENT (jamais chargé sur serveur dédié).
 * Câble les handlers réseau client, l'écran de configuration NeoForge, et
 * initialise le bridge JourneyMap une fois TOUS les mods construits (pour que
 * le scan d'annotations voie les plugins des mods tiers).
 */
@Mod(value = SharedJourney.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = SharedJourney.MODID, value = Dist.CLIENT)
public class SharedJourneyClient {

    public SharedJourneyClient(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // Câblage des handlers côté client.
        Payloads.Hooks.clientLayerSettings = ClientNetHandler::handleLayerSettings;
        Payloads.Hooks.clientRegionData = ClientNetHandler::handleRegionData;
    }

    @SubscribeEvent
    static void onLoadComplete(FMLLoadCompleteEvent event) {
        // Spec §9 : bridge de compatibilité JourneyMap (Waystones & co).
        event.enqueueWork(JourneyMapBridge::init);
    }
}
