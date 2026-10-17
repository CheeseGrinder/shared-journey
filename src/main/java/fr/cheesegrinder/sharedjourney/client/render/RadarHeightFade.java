package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.client.config.RadarClientConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Height fade of the radar entities (minimap + fullscreen map): entities
 * far above or below the player fade out progressively, with an optional
 * up / down arrow and signed Y offset label next to them. Useful in caves,
 * where a mob two floors away is not an immediate threat.
 *
 * <p>A snapshot of the config is taken once per frame ({@link #current}):
 * each {@code .get()} walks the NeoForge config backing, which is too
 * costly per entity. The vertical offset is the entity's feet Y minus the
 * player's feet Y.
 */
public final class RadarHeightFade {

    /** Opacity of an entity at or beyond the full-fade distance (never invisible). */
    static final float MIN_ALPHA = 0.25f;
    /** Opacity floor of the arrow and label, which carry the information. */
    private static final float MARKER_MIN_ALPHA = 0.7f;
    /** Horizontal offset (GUI units) of the arrow's centre from the icon centre. */
    private static final int MARKER_OFFSET_X = 9;
    /** Horizontal advance from the arrow's centre to the label. */
    private static final int LABEL_AFTER_ARROW = 5;

    private static final float LABEL_SCALE = 0.5f;
    private static final int TEXT_COLOR_RGB = 0xFFFFFF;

    /** An entity's marker to draw once the icon batch is flushed (x / y are free for the caller). */
    public record Mark(double x, double y, double dy, float alpha) {}

    private final boolean enabled;
    private final double start;
    private final double full;
    private final boolean label;
    private final boolean arrow;

    private RadarHeightFade(boolean enabled, double start, double full, boolean label, boolean arrow) {
        this.enabled = enabled;
        this.start = start;
        this.full = full;
        this.label = label;
        this.arrow = arrow;
    }

    /** Snapshot of the current client config. */
    public static RadarHeightFade current() {
        double start = RadarClientConfig.HEIGHT_FADE_START.get();
        double full = Math.max(RadarClientConfig.HEIGHT_FADE_FULL.get(), start + 1);
        return new RadarHeightFade(
                RadarClientConfig.HEIGHT_FADE.get(),
                start,
                full,
                RadarClientConfig.HEIGHT_FADE_LABEL.get(),
                RadarClientConfig.HEIGHT_FADE_ARROW.get());
    }

    /**
     * Opacity (1 = opaque) for an entity {@code dy} blocks above (positive)
     * or below the player: opaque up to the fade start distance, then a
     * linear fade down to {@link #MIN_ALPHA} at the full-fade distance.
     */
    public float alphaFor(double dy) {
        if (!enabled) {
            return 1f;
        }

        double t = (Math.abs(dy) - start) / (full - start);
        if (t <= 0) {
            return 1f;
        }

        return (float) (1 - Math.min(t, 1) * (1 - MIN_ALPHA));
    }

    /** True when an arrow and / or label must accompany an entity at this offset. */
    public boolean hasMarker(double dy) {
        return enabled && (label || arrow) && Math.abs(dy) > start;
    }

    /**
     * Draws the arrow and label for an offset of {@code dy} at the origin
     * of the current pose (the caller translates to the entity and
     * counters any zoom / rotation so the marker keeps a constant, upright
     * size). {@code alpha} is the entity's opacity: the marker never fades
     * below {@link #MARKER_MIN_ALPHA}.
     */
    public void drawMarker(GuiGraphics gg, double dy, float alpha) {
        int a = alphaByte(Math.max(alpha, MARKER_MIN_ALPHA));
        int white = (a << 24) | TEXT_COLOR_RGB;
        int black = a << 24;
        int textX = MARKER_OFFSET_X - 2;
        if (arrow) {
            drawArrow(gg, MARKER_OFFSET_X, dy > 0, white, black);
            textX = MARKER_OFFSET_X + LABEL_AFTER_ARROW;
        }
        if (label) {
            drawLabel(gg, textX, dy, white);
        }
    }

    private static void drawLabel(GuiGraphics gg, int x, double dy, int argb) {
        long offset = Math.round(dy);
        String text = offset > 0 ? "+" + offset : Long.toString(offset);
        gg.pose().pushPose();
        gg.pose().translate(x, -2f, 0f);
        gg.pose().scale(LABEL_SCALE, LABEL_SCALE, 1f);
        gg.drawString(Minecraft.getInstance().font, text, 0, 0, argb, true);
        gg.pose().popPose();
    }

    /**
     * 3-row pixel triangle (1, 3, 5 px wide) centred on row 0, pointing
     * up or down, with a 1 px black outline. Outline and body pixels never
     * overlap, so a translucent alpha is not blended twice.
     */
    private static void drawArrow(GuiGraphics gg, int cx, boolean up, int white, int black) {
        int tipCapY = up ? -2 : 2;
        int baseCapY = up ? 2 : -2;
        gg.fill(cx, tipCapY, cx + 1, tipCapY + 1, black);
        gg.fill(cx - 3, baseCapY, cx + 4, baseCapY + 1, black);
        for (int i = 0; i < 3; i++) {
            int y = up ? i - 1 : 1 - i;
            gg.fill(cx - i - 1, y, cx - i, y + 1, black);
            gg.fill(cx + i + 1, y, cx + i + 2, y + 1, black);
            gg.fill(cx - i, y, cx + i + 1, y + 1, white);
        }
    }

    /** Alpha as a 0x11..0xFF byte (the font renders alpha under 4 as opaque). */
    private static int alphaByte(float alpha) {
        return Math.clamp(Math.round(alpha * 255f), 0x11, 0xFF);
    }
}
