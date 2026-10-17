package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.common.waypoint.AutoWaypointKind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

/**
 * Textured waypoint icons shared by every map surface (minimap,
 * fullscreen map, waypoint list). The sprites are grayscale and tinted
 * at draw time with the waypoint's color: white pixels take the full
 * color, darker pixels shade it, the outline stays black.
 *
 * <p>The 16x16 sprites are drawn at arbitrary scales (0.75, 0.625...) on
 * a GUI whose own scale is 2-4: NEAREST sampling at those non-integer
 * ratios duplicates some texel columns and not others (a pixelated,
 * uneven diamond) and plain bilinear blurs. They go through
 * {@link WaypointIconShader}'s sharp bilinear sampling instead: flat
 * texel interiors, edges smoothed over one screen pixel.
 */
public final class WaypointIcons {

    /** Native sprite size in pixels (all sprites are 16x16). */
    public static final int SIZE = 16;

    /** Default icon of hand-created and bridged waypoints. */
    public static final ResourceLocation DIAMOND = texture("diamond");

    /** Icon of the automatic death waypoints ({@link Waypoint#GROUP_DEATHS}). */
    public static final ResourceLocation DEATH = texture("death");

    /** Icon of banner waypoints ({@link Waypoint#SOURCE_BANNER}). */
    public static final ResourceLocation BANNER = texture("banner");

    /** Icon of the automatic bed waypoints ({@link AutoWaypointKind#BED}). */
    public static final ResourceLocation BED = texture("bed");

    /** Icon of the automatic nether portal waypoints ({@link AutoWaypointKind#PORTAL}). */
    public static final ResourceLocation PORTAL = texture("portal");

    /** Icon of the automatic respawn anchor waypoints ({@link AutoWaypointKind#ANCHOR}). */
    public static final ResourceLocation ANCHOR = texture("anchor");

    private WaypointIcons() {}

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                SharedJourneyConstants.MOD_ID, "textures/gui/waypoint/" + name + ".png");
    }

    /**
     * Sprite for a waypoint: banner source, automatic kind (bed, portal,
     * anchor), death group, or the default diamond.
     */
    public static ResourceLocation textureFor(Waypoint wp) {
        if (Waypoint.SOURCE_BANNER.equals(wp.source())) {
            return BANNER;
        }
        if (Waypoint.SOURCE_AUTO.equals(wp.source())) {
            return autoTexture(AutoWaypointKind.forGroup(wp.group()));
        }
        if (Waypoint.GROUP_DEATHS.equals(wp.group())) {
            return DEATH;
        }
        return DIAMOND;
    }

    private static ResourceLocation autoTexture(AutoWaypointKind kind) {
        if (kind == null) {
            return DIAMOND;
        }

        return switch (kind) {
            case BED -> BED;
            case PORTAL -> PORTAL;
            case ANCHOR -> ANCHOR;
        };
    }

    /** Draws the waypoint's icon centered on (x, y), tinted with its color. */
    public static void draw(GuiGraphics gg, Waypoint wp, float x, float y, float scale) {
        draw(gg, textureFor(wp), x, y, wp.colorRgb(), scale);
    }

    /**
     * Draws one sprite centered on (x, y), tinted with the given RGB
     * color, at {@code scale} times the native 16x16 size.
     */
    public static void draw(GuiGraphics gg, ResourceLocation sprite, float x, float y, int rgb, float scale) {
        if (WaypointIconShader.gui() == null) {
            drawNearest(gg, sprite, x, y, rgb, scale);
            return;
        }

        drawSharp(gg, sprite, x, y, rgb, scale);
    }

    /** Sharp bilinear draw (see {@link WaypointIconShader}), one immediate quad. */
    private static void drawSharp(GuiGraphics gg, ResourceLocation sprite, float x, float y, int rgb, float scale) {
        var pose = gg.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1f);
        Matrix4f mat = pose.last().pose();
        pose.popPose();

        // The pending GUI batch must land first to keep the stacking order.
        gg.flush();
        // The shader needs LINEAR filtering to smooth the texel edges.
        Minecraft.getInstance().getTextureManager().getTexture(sprite).setFilter(true, false);
        RenderSystem.setShader(WaypointIconShader::gui);
        RenderSystem.setShaderTexture(0, sprite);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        float h = SIZE / 2f;
        // Half-texel UV inset: the quad edge sits on a texel center, so the
        // default REPEAT wrap never blends the opposite edge in.
        float uv0 = 0.5f / SIZE;
        float uv1 = 1f - uv0;
        BufferBuilder buf =
                Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buf.addVertex(mat, -h, -h, 0f).setUv(uv0, uv0).setColor(r, g, b, 255);
        buf.addVertex(mat, -h, h, 0f).setUv(uv0, uv1).setColor(r, g, b, 255);
        buf.addVertex(mat, h, h, 0f).setUv(uv1, uv1).setColor(r, g, b, 255);
        buf.addVertex(mat, h, -h, 0f).setUv(uv1, uv0).setColor(r, g, b, 255);
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    /** Fallback when the shader is unavailable: the plain NEAREST blit. */
    private static void drawNearest(GuiGraphics gg, ResourceLocation sprite, float x, float y, int rgb, float scale) {
        gg.pose().pushPose();
        gg.pose().translate(x, y, 0);
        gg.pose().scale(scale, scale, 1f);
        RenderSystem.enableBlend();
        gg.setColor(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);
        gg.blit(sprite, -SIZE / 2, -SIZE / 2, 0f, 0f, SIZE, SIZE, SIZE, SIZE);
        gg.setColor(1f, 1f, 1f, 1f);
        gg.pose().popPose();
    }
}
