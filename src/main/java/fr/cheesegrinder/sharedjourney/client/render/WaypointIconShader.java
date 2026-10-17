package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Core shaders of the waypoint icons: vanilla's see-through text pipeline
 * with sharp bilinear sampling (see {@code waypoint_icon.fsh}), so the
 * 16x16 sprites stay crisp at any size. Two instances share the same
 * programs and differ only by vertex format: the in-world one (beacon
 * icons) and the GUI one (minimap, fullscreen map, lists).
 *
 * <p>Registered on the mod bus through {@link #register}. If a shader
 * cannot be loaded (or is replaced by a shader pack), {@link #get} falls
 * back to vanilla's see-through shader and {@link #gui} returns null (the
 * caller then draws the plain NEAREST blit): the icons render as before,
 * merely less crisp.
 */
public final class WaypointIconShader {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(SharedJourneyConstants.MOD_ID, "waypoint_icon");

    private static final ResourceLocation GUI_ID =
            ResourceLocation.fromNamespaceAndPath(SharedJourneyConstants.MOD_ID, "waypoint_icon_gui");

    private static ShaderInstance shader;
    private static ShaderInstance guiShader;

    private WaypointIconShader() {}

    /** Loads the shaders; each instance is handed back once the event applies it. */
    public static void register(RegisterShadersEvent event) {
        load(event, ID, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, loaded -> shader = loaded);
        load(event, GUI_ID, DefaultVertexFormat.POSITION_TEX_COLOR, loaded -> guiShader = loaded);
    }

    private static void load(
            RegisterShadersEvent event, ResourceLocation id, VertexFormat format, Consumer<ShaderInstance> sink) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), id, format), sink);
        } catch (IOException ex) {
            LOGGER.error("Could not load the {} shader, using the fallback", id, ex);
        }
    }

    /** The in-world sharp bilinear shader, or vanilla's see-through shader as a fallback. */
    static ShaderInstance get() {
        if (shader != null) {
            return shader;
        }

        return GameRenderer.getRendertypeTextSeeThroughShader();
    }

    /** The GUI sharp bilinear shader (POSITION_TEX_COLOR), or null when it is not available. */
    static ShaderInstance gui() {
        return guiShader;
    }
}
