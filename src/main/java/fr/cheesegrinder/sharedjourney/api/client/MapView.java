package fr.cheesegrinder.sharedjourney.api.client;

import fr.cheesegrinder.sharedjourney.api.MapLayer;

import net.minecraft.resources.ResourceLocation;

/**
 * Read-only view of a map being rendered (HUD minimap or fullscreen map),
 * exposed to overlay renderers through
 * {@link fr.cheesegrinder.sharedjourney.api.client.event.MapRenderEvent}.
 *
 * Coordinate contract: screen coordinates are GUI pixels local to the view
 * (origin = top-left corner of the map area, so the view center is at
 * (viewWidth/2, viewHeight/2)). The world&lt;-&gt;screen conversions below
 * assume the pose active when the event is posted: on a rotating minimap the
 * pose is rotated around the view center, so drawing at
 * ({@link #screenX}, {@link #screenY}) lands on the right world anchor —
 * but the drawn content rotates with the map.
 */
public interface MapView {

    /** True for the HUD minimap, false for the fullscreen map. */
    boolean isMinimap();

    /** Dimension currently displayed. */
    ResourceLocation dimension();

    /** Layer currently displayed. */
    MapLayer currentLayer();

    /** Current CAVE band (floorDiv(y,16)); 0 when the layer is not CAVE. */
    int caveBand();

    /** World X at the center of the view (blocks). */
    double centerX();

    /** World Z at the center of the view (blocks). */
    double centerZ();

    /** Zoom in GUI pixels per block. */
    float zoomScale();

    /** View width in GUI pixels. */
    int viewWidth();

    /** View height in GUI pixels. */
    int viewHeight();

    /** World X -> view-local screen X. */
    default double screenX(double worldX) {
        return viewWidth() / 2.0 + (worldX - centerX()) * zoomScale();
    }

    /** World Z -> view-local screen Y. */
    default double screenY(double worldZ) {
        return viewHeight() / 2.0 + (worldZ - centerZ()) * zoomScale();
    }

    /** View-local screen X -> world X. */
    default double worldX(double screenX) {
        return centerX() + (screenX - viewWidth() / 2.0) / zoomScale();
    }

    /** View-local screen Y -> world Z. */
    default double worldZ(double screenY) {
        return centerZ() + (screenY - viewHeight() / 2.0) / zoomScale();
    }
}
