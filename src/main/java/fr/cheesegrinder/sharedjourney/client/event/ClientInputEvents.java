package fr.cheesegrinder.sharedjourney.client.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.gui.FullMapScreen;
import fr.cheesegrinder.sharedjourney.client.render.MinimapRenderer;

import net.minecraft.client.Minecraft;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Entrées clavier et actions différées : consommation des raccourcis
 * (déclarés dans {@link ClientSetupEvents}) et ouverture différée de la carte.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class ClientInputEvents {

    public static boolean minimapVisible = true;

    /**
     * Cible d'ouverture différée de la carte (/sj goto, clic sur un message
     * de position) : appliquée au tick suivant pour que la fermeture du chat
     * n'écrase pas l'écran qu'on vient d'ouvrir.
     */
    private static volatile double[] pendingMapOpen = null;

    private ClientInputEvents() {}

    public static void openMapAt(double x, double z) {
        pendingMapOpen = new double[] {x, z};
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        double[] target = pendingMapOpen;
        if (target != null && mc.player != null) {
            pendingMapOpen = null;
            mc.setScreen(new FullMapScreen(target[0], target[1]));
        }
        while (ClientSetupEvents.OPEN_FULL_MAP.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new FullMapScreen());
            }
        }
        while (ClientSetupEvents.TOGGLE_MINIMAP.consumeClick()) {
            minimapVisible = !minimapVisible;
        }
        while (ClientSetupEvents.CYCLE_LAYER.consumeClick()) {
            MinimapRenderer.cycleLayer();
        }
        while (ClientSetupEvents.ZOOM_IN.consumeClick()) {
            MinimapRenderer.zoomIn();
        }
        while (ClientSetupEvents.ZOOM_OUT.consumeClick()) {
            MinimapRenderer.zoomOut();
        }
    }
}
