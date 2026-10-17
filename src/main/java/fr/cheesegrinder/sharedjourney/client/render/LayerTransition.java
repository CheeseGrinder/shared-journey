package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.Util;
import net.minecraft.network.chat.Component;

/**
 * Smooths the visual change of a map view (minimap or fullscreen) when its
 * displayed layer or cave band changes:
 *
 * <ul>
 *   <li>for a short window after the change, a region texture of the new
 *       layer that is not available yet (asynchronous disk load, or still
 *       on its way from the server) is replaced by the same region of the
 *       previous layer, instead of the grey background flashing;
 *   <li>a layer name label that stays visible for about a second and fades
 *       out ({@link #labelAlpha()}).
 * </ul>
 *
 * One instance per view; all calls happen on the render thread.
 */
public final class LayerTransition {

    /**
     * How long after a change the previous layer's textures may stand in for
     * missing ones. Bounded so a region that really has nothing on the new
     * layer ends up as plain background instead of showing the old layer
     * forever.
     */
    static final long FALLBACK_WINDOW_MS = 1500;

    /** Total time the layer name stays visible, fade included. */
    static final long LABEL_DURATION_MS = 1100;

    /** Length of the final fade-out inside {@link #LABEL_DURATION_MS}. */
    static final long LABEL_FADE_MS = 400;

    private MapLayer layer;
    private int band;
    private MapLayer previousLayer;
    private int previousBand;
    private long changedAt;
    private long labelStartedAt = Long.MIN_VALUE;

    /**
     * Reports the layer and cave band drawn this frame. The first report is
     * the baseline and starts no transition; a band-only change (vertical
     * move in caves) starts the texture fallback but not the label.
     */
    public void update(MapLayer newLayer, int newBand) {
        if (layer == null) {
            layer = newLayer;
            band = newBand;
            return;
        }

        if (layer == newLayer && band == newBand) {
            return;
        }

        previousLayer = layer;
        previousBand = band;
        long now = Util.getMillis();
        changedAt = now;
        if (layer != newLayer) {
            labelStartedAt = now;
        }

        layer = newLayer;
        band = newBand;
    }

    /** The full-resolution region to draw for {@code key}; see {@link #regionFor(RegionKey, int)}. */
    public ClientMapCache.Region regionFor(RegionKey key) {
        return regionFor(key, 0);
    }

    /**
     * The region to draw for {@code key} at a level of detail: its own
     * texture when available (the wanted level, else another level of the
     * same region, see {@link ClientMapCache#getOrLoad(RegionKey, int)}),
     * else (inside the fallback window) the same region of the previous
     * layer at the closest level in RAM, else null (background).
     */
    public ClientMapCache.Region regionFor(RegionKey key, int level) {
        ClientMapCache.Region region = ClientMapCache.getOrLoad(key, level);
        if (region != null || previousLayer == null) {
            return region;
        }

        if (Util.getMillis() - changedAt > FALLBACK_WINDOW_MS) {
            return null;
        }

        int oldBand = previousLayer.hasBands() ? previousBand : 0;
        RegionKey old = new RegionKey(key.dimension(), previousLayer, oldBand, key.rx(), key.rz());
        return ClientMapCache.peek(old, level);
    }

    /** Opacity of the layer name label: 0 when hidden, 1 when fully shown. */
    public float labelAlpha() {
        if (layer == null || labelStartedAt == Long.MIN_VALUE) {
            return 0f;
        }

        long elapsed = Util.getMillis() - labelStartedAt;
        if (elapsed >= LABEL_DURATION_MS) {
            return 0f;
        }

        long fadeStart = LABEL_DURATION_MS - LABEL_FADE_MS;
        if (elapsed <= fadeStart) {
            return 1f;
        }

        return 1f - (float) (elapsed - fadeStart) / LABEL_FADE_MS;
    }

    /** Display name of the current layer (raw id for mod layers without a lang key). */
    public Component labelText() {
        if (layer == null) {
            return Component.empty();
        }

        return Component.translatableWithFallback(layer.translationKey(), layer.id());
    }

    /**
     * ARGB made of {@code rgb} and an alpha of {@code maxAlpha * alpha}, or 0
     * when that alpha is below 4 (nothing to draw: vanilla text treats such
     * a small alpha as fully opaque).
     */
    public static int withAlpha(int rgb, float alpha, int maxAlpha) {
        int a = Math.round(maxAlpha * Math.clamp(alpha, 0f, 1f));
        if (a < 4) {
            return 0;
        }

        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
