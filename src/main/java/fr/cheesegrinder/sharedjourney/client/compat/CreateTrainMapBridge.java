package fr.cheesegrinder.sharedjourney.client.compat;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.event.ClientInputEvents;
import fr.cheesegrinder.sharedjourney.client.gui.FullMapScreen;

import net.minecraft.client.Minecraft;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Pompe de synchronisation de la carte des trains Create : le tick de Create
 * (JourneyTrainMap.tick) ne demande les données trains au serveur que si
 * l'écran ouvert est le Fullscreen INTERNE de JourneyMap — jamais le nôtre.
 * On reproduit donc son travail quand NOTRE carte plein écran est ouverte :
 * TrainMapManager.tick() + TrainMapSyncClient.requestData() chaque tick,
 * puis stopRequesting() à la fermeture. Réflexion pure : aucune dépendance
 * de compilation à Create ; inactif si Create est absent.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class CreateTrainMapBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean resolved;
    private static boolean available;
    private static Method managerTick;
    private static Method requestData;
    private static Method stopRequesting;
    private static boolean requesting;

    private CreateTrainMapBridge() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean fullMapOpen = mc.screen instanceof FullMapScreen;
        // La minimap affiche aussi les overlays trains : il lui faut les données.
        boolean minimapShown = mc.screen == null
                && !mc.options.hideGui
                && ClientInputEvents.minimapVisible
                && ClientConfig.MINIMAP_ENABLED.get();
        boolean mapOpen = mc.level != null && (fullMapOpen || minimapShown);
        if (mapOpen && JourneyMapBridge.bridgeActive() && resolve()) {
            try {
                managerTick.invoke(null);
                requestData.invoke(null);
                requesting = true;
            } catch (Throwable t) {
                warnAndDisable(t);
            }
            return;
        }

        if (requesting) {
            requesting = false;
            try {
                stopRequesting.invoke(null);
            } catch (Throwable t) {
                warnAndDisable(t);
            }
        }
    }

    private static void warnAndDisable(Throwable t) {
        LOGGER.warn("[Bridge JM] Sync de la carte des trains Create en échec, désactivée : {}", t.toString());
        available = false;
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }

        resolved = true;
        try {
            Class<?> manager = Class.forName("com.simibubi.create.compat.trainmap.TrainMapManager");
            Class<?> sync = Class.forName("com.simibubi.create.compat.trainmap.TrainMapSyncClient");
            managerTick = manager.getMethod("tick");
            requestData = sync.getMethod("requestData");
            stopRequesting = sync.getMethod("stopRequesting");
            available = true;
            LOGGER.info("[Bridge JM] Carte des trains Create détectée : sync branchée sur la carte SharedJourney.");
        } catch (ClassNotFoundException e) {
            // Create absent (ou sans carte des trains) : rien à faire.
            available = false;
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Carte des trains Create incompatible : {}", t.toString());
            available = false;
        }
        return available;
    }
}
