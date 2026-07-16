package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.Waypoint;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Textured waypoint icons shared by every map surface (minimap,
 * fullscreen map, waypoint list). The sprites are grayscale and tinted
 * at draw time with the waypoint's color: white pixels take the full
 * color, darker pixels shade it, the outline stays black.
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

    private WaypointIcons() {}

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                SharedJourneyConstants.MOD_ID, "textures/gui/waypoint/" + name + ".png");
    }

    /** Sprite for a waypoint: banner source, death group, or the default diamond. */
    public static ResourceLocation textureFor(Waypoint wp) {
        if (Waypoint.SOURCE_BANNER.equals(wp.source())) {
            return BANNER;
        }
        if (Waypoint.GROUP_DEATHS.equals(wp.group())) {
            return DEATH;
        }
        return DIAMOND;
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
