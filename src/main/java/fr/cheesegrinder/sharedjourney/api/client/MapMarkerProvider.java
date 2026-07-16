package fr.cheesegrinder.sharedjourney.api.client;

import java.util.List;

/**
 * Supplies the {@link MapMarker}s of one registered source for one
 * rendered frame of one surface. Called on the render thread, once per
 * visible map (HUD minimap and/or fullscreen map) per frame, with that
 * map's {@link MapView}: filter here — e.g. return {@link List#of()}
 * when {@link MapView#dimension()} is not yours, or gate on
 * {@link MapView#zoomScale()} / {@link MapView#currentLayer()}.
 *
 * <p>Must be cheap (per frame!) and never return null. Positions can be
 * dynamic (moving entities): the provider is polled every frame, no
 * re-registration needed.
 *
 * @see MapMarkerApi#register
 */
@FunctionalInterface
public interface MapMarkerProvider {

    /** Markers to draw on this view, this frame. Never null. */
    List<MapMarker> markers(MapView view);
}
