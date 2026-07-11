package fr.cheesegrinder.sharedjourney.client.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.RadarClientConfig;
import fr.cheesegrinder.sharedjourney.client.render.MinimapRenderer;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * MOD bus registrations: keyboard shortcuts and the minimap HUD layer.
 * The keys are consumed in {@link ClientInputEvents}.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class ClientSetupEvents {

    public static final KeyMapping OPEN_FULL_MAP = new KeyMapping(
            "key.sharedjourney.fullmap", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.sharedjourney");
    public static final KeyMapping TOGGLE_MINIMAP = new KeyMapping(
            "key.sharedjourney.toggle_minimap",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "key.categories.sharedjourney");
    public static final KeyMapping CYCLE_LAYER = new KeyMapping(
            "key.sharedjourney.cycle_layer",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_COMMA,
            "key.categories.sharedjourney");
    public static final KeyMapping ZOOM_IN = new KeyMapping(
            "key.sharedjourney.zoom_in",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_EQUAL,
            "key.categories.sharedjourney");
    public static final KeyMapping ZOOM_OUT = new KeyMapping(
            "key.sharedjourney.zoom_out",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_MINUS,
            "key.categories.sharedjourney");
    public static final KeyMapping OPEN_WAYPOINTS = new KeyMapping(
            "key.sharedjourney.waypoints", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.sharedjourney");
    public static final KeyMapping CREATE_WAYPOINT = new KeyMapping(
            "key.sharedjourney.create_waypoint",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.sharedjourney");

    private ClientSetupEvents() {}

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_FULL_MAP);
        event.register(TOGGLE_MINIMAP);
        event.register(CYCLE_LAYER);
        event.register(ZOOM_IN);
        event.register(ZOOM_OUT);
        event.register(OPEN_WAYPOINTS);
        event.register(CREATE_WAYPOINT);
    }

    /** On every client config save: re-send the visibility preference. */
    @SubscribeEvent
    public static void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != ClientConfig.SPEC) {
            return;
        }

        var mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PacketDistributor.sendToServer(new Payloads.MapVisibilityPayload(RadarClientConfig.HIDE_FROM_MAP.get()));
        }
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(
                VanillaGuiLayers.DEBUG_OVERLAY,
                ResourceLocation.fromNamespaceAndPath(SharedJourneyConstants.MOD_ID, "minimap"),
                MinimapRenderer::render);
    }
}
