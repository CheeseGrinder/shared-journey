package fr.cheesegrinder.sharedjourney.client.event;

import com.mojang.blaze3d.platform.InputConstants;
import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.render.MinimapRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.lwjgl.glfw.GLFW;

/**
 * Enregistrements du bus MOD : raccourcis clavier et couche HUD de la
 * minimap. Les touches sont consommées dans {@link ClientInputEvents}.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetupEvents {

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

    private ClientSetupEvents() {}

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
