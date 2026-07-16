package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.client.MapMarker;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws declarative API markers ({@link MapMarker}) with SharedJourney's
 * own primitives. Single entry point shared by the minimap and the
 * fullscreen map so both surfaces stay pixel-identical — and so the
 * internal technique (vector today, textures/shaders later) can change
 * without touching the callers or the API.
 */
public final class MapMarkerRenderer {

    private MapMarkerRenderer() {}

    /** Draws one marker at screen position (sx, sy) at the given scale. */
    public static void draw(GuiGraphics gg, MapMarker marker, float sx, float sy, float scale) {
        switch (marker.shape()) {
            case DIAMOND -> EntityDots.drawWaypointDiamond(gg, sx, sy, marker.colorRgb(), scale);
            case DOT -> EntityDots.draw(gg, Math.round(sx), Math.round(sy), 0xFF000000 | marker.colorRgb());
        }
    }
}
