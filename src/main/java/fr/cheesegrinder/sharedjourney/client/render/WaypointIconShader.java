package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * Core shader of the in-world waypoint icons: vanilla's see-through text
 * pipeline with sharp bilinear sampling (see {@code waypoint_icon.fsh}),
 * so the 16x16 sprites stay crisp when a beacon is close.
 *
 * <p>Registered on the mod bus through {@link #register}. If the shader
 * cannot be loaded (or is replaced by a shader pack), {@link #get} falls
 * back to vanilla's see-through shader: the icons then render exactly as
 * before, merely blurrier up close.
 */
public final class WaypointIconShader {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(SharedJourneyConstants.MOD_ID, "waypoint_icon");

    private static ShaderInstance shader;

    private WaypointIconShader() {}

    /** Loads the shader; the instance is handed back once the event applies it. */
    public static void register(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(), ID, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP),
                    loaded -> shader = loaded);
        } catch (IOException ex) {
            LOGGER.error("Could not load the waypoint icon shader, using the vanilla one", ex);
        }
    }

    /** The sharp bilinear shader, or vanilla's see-through shader as a fallback. */
    static ShaderInstance get() {
        if (shader != null) {
            return shader;
        }

        return GameRenderer.getRendertypeTextSeeThroughShader();
    }
}
