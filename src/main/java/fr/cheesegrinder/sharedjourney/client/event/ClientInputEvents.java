package fr.cheesegrinder.sharedjourney.client;

import com.mojang.blaze3d.platform.InputConstants;
import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.gui.FullMapScreen;
import fr.cheesegrinder.sharedjourney.common.Payloads;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Touches, overlay minimap, cycle de session client (cache + handshake). */
public final class ClientEvents {

    public static boolean minimapVisible = true;

    /**
     * Cible d'ouverture différée de la carte (/sj goto, clic sur un message
     * de position) : appliquée au tick suivant pour que la fermeture du chat
     * n'écrase pas l'écran qu'on vient d'ouvrir.
     */
    private static volatile double[] pendingMapOpen = null;

    public static void openMapAt(double x, double z) {
        pendingMapOpen = new double[]{x, z};
    }

    public static final KeyMapping OPEN_FULL_MAP = new KeyMapping(
            "key.sharedjourney.fullmap", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M,
            "key.categories.sharedjourney");
    public static final KeyMapping TOGGLE_MINIMAP = new KeyMapping(
            "key.sharedjourney.toggle_minimap", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N,
            "key.categories.sharedjourney");
    public static final KeyMapping CYCLE_LAYER = new KeyMapping(
            "key.sharedjourney.cycle_layer", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_COMMA,
            "key.categories.sharedjourney");
    public static final KeyMapping ZOOM_IN = new KeyMapping(
            "key.sharedjourney.zoom_in", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_EQUAL,
            "key.categories.sharedjourney");
    public static final KeyMapping ZOOM_OUT = new KeyMapping(
            "key.sharedjourney.zoom_out", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_MINUS,
            "key.categories.sharedjourney");

    private ClientEvents() {}

    // ---------------------------------------------------------------- bus MOD

    @EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBus {

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_FULL_MAP);
            event.register(TOGGLE_MINIMAP);
            event.register(CYCLE_LAYER);
            event.register(ZOOM_IN);
            event.register(ZOOM_OUT);
        }

        @SubscribeEvent
        public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.DEBUG_OVERLAY,
                    ResourceLocation.fromNamespaceAndPath(SharedJourneyConstants.MOD_ID, "minimap"),
                    (gg, deltaTracker) -> MinimapRenderer.render(gg));
        }
    }

    // ---------------------------------------------------------------- bus GAME

    @EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
    public static final class GameBus {

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            double[] target = pendingMapOpen;
            if (target != null && mc.player != null) {
                pendingMapOpen = null;
                mc.setScreen(new FullMapScreen(target[0], target[1]));
            }
            while (OPEN_FULL_MAP.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new FullMapScreen());
            }
            while (TOGGLE_MINIMAP.consumeClick()) {
                minimapVisible = !minimapVisible;
            }
            while (CYCLE_LAYER.consumeClick()) {
                MinimapRenderer.cycleLayer();
            }
            while (ZOOM_IN.consumeClick()) {
                MinimapRenderer.zoomIn();
            }
            while (ZOOM_OUT.consumeClick()) {
                MinimapRenderer.zoomOut();
            }
        }

        /** Connexion : ouvre le cache local + handshake (spec §5.1). */
        @SubscribeEvent
        public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            ClientMapCache.clear();
            DiskCache.openSession();
            WaypointStore.openSession();
            // Handshake : envoi du résumé de l'index local. Le serveur ne
            // renverra que les régions manquantes ou plus récentes.
            byte[] encoded = Payloads.ClientIndexPayload.encodeIndex(DiskCache.index().snapshot());
            PacketDistributor.sendToServer(new Payloads.ClientIndexPayload(encoded));
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            DiskCache.closeSession();
            WaypointStore.closeSession();
            ClientMapCache.clear();
        }
    }
}
