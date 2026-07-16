package fr.cheesegrinder.sharedjourney.api.client;

import java.util.Objects;

/**
 * Declarative map marker: the caller describes WHERE and WHAT (position,
 * color, shape) and SharedJourney draws it on both surfaces (HUD minimap
 * and fullscreen map) with its own current rendering technique — vector
 * today, possibly textures or shaders later — which therefore stays
 * invisible to the API. Callers who need full drawing control use the
 * {@link fr.cheesegrinder.sharedjourney.api.client.event.MapRenderEvent}
 * callback instead and draw with their own GuiGraphics.
 *
 * <p>Position is in world blocks. Like waypoints, add 0.5 to a block
 * coordinate to center the marker on that block. Color is packed RGB
 * ({@code 0xRRGGBB}, alpha ignored). {@link #clampToEdge()} pins the
 * marker to the map edge when it is out of view (direction hint, same
 * behavior as waypoints on the minimap border); otherwise the marker is
 * simply culled when off-view.
 *
 * <p>Deliberately NOT exposed (YAGNI until a consumer needs them):
 * texture icons, labels, click handling (use the toolbar/context-menu
 * events for interactions), per-marker z-order, in-world beacons.
 *
 * @see MapMarkerApi
 * @see MapMarkerProvider
 */
public record MapMarker(double x, double z, int colorRgb, Shape shape, boolean clampToEdge) {

    /** Marker shape, mapped to SharedJourney's own drawing primitives. */
    public enum Shape {
        /** Waypoint-style diamond (45°-rotated square, dark outline). */
        DIAMOND,
        /** Radar-style dot (small filled square, dark outline). */
        DOT
    }

    public MapMarker {
        Objects.requireNonNull(shape, "shape");
        colorRgb = colorRgb & 0xFFFFFF;
    }

    /** Marker culled when off-view ({@code clampToEdge} = false). */
    public static MapMarker create(double x, double z, int colorRgb, Shape shape) {
        return new MapMarker(x, z, colorRgb, shape, false);
    }

    /** Copy with the given edge-pinning behavior. */
    public MapMarker withClampToEdge(boolean clamp) {
        return new MapMarker(x, z, colorRgb, shape, clamp);
    }
}
