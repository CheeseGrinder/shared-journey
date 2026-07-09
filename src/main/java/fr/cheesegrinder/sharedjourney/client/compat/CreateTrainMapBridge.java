package fr.cheesegrinder.sharedjourney.client.compat;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.config.MapClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.MinimapClientConfig;
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
 * Sync pump for Create's train map: Create's tick (JourneyTrainMap.tick)
 * only requests train data from the server if the open screen is
 * JourneyMap's INTERNAL Fullscreen — never ours. So we reproduce its work
 * when OUR fullscreen map is open: TrainMapManager.tick() +
 * TrainMapSyncClient.requestData() every tick, then stopRequesting() on
 * close. Pure reflection: no compile-time dependency on Create; inactive
 * if Create is absent.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class CreateTrainMapBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Position of the toggle widget drawn by the train map (Create's constants). */
    private static final int TOGGLE_WIDGET_X = 3;

    private static final int TOGGLE_WIDGET_Y = 30;

    private static boolean resolved;
    private static boolean available;
    private static Method managerTick;
    private static Method requestData;
    private static Method stopRequesting;
    private static Method handleToggleWidgetClick;
    private static boolean requesting;

    private CreateTrainMapBridge() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean fullMapOpen = mc.screen instanceof FullMapScreen;
        // The minimap also shows train overlays: it needs the data too.
        boolean minimapShown = mc.screen == null
                && !mc.options.hideGui
                && ClientInputEvents.minimapVisible
                && MinimapClientConfig.MINIMAP_ENABLED.get();
        boolean mapOpen = mc.level != null && (fullMapOpen || minimapShown) && MapClientConfig.SHOW_TRAIN_OVERLAY.get();
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

    /**
     * Click on the toggle widget the train map draws in the top-left
     * corner of the map: Create's handler is gated on JourneyMap's
     * internal screen, so we call its logic ourselves. Returns true if
     * the click was consumed.
     */
    public static boolean handleToggleClick(int mouseX, int mouseY) {
        if (!JourneyMapBridge.bridgeActive() || !resolve() || !MapClientConfig.SHOW_TRAIN_OVERLAY.get()) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(
                    handleToggleWidgetClick.invoke(null, mouseX, mouseY, TOGGLE_WIDGET_X, TOGGLE_WIDGET_Y));
        } catch (Throwable t) {
            warnAndDisable(t);
            return false;
        }
    }

    private static void warnAndDisable(Throwable t) {
        LOGGER.warn("[Bridge JM] Create train map sync failed, disabling: {}", t.toString());
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
            handleToggleWidgetClick =
                    manager.getMethod("handleToggleWidgetClick", int.class, int.class, int.class, int.class);
            available = true;
            LOGGER.info("[Bridge JM] Create train map detected: sync wired to the SharedJourney map.");
        } catch (ClassNotFoundException e) {
            // Create absent (or without a train map): nothing to do.
            available = false;
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Create train map incompatible: {}", t.toString());
            available = false;
        }
        return available;
    }
}
