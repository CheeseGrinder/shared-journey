package fr.cheesegrinder.sharedjourney.server.render;

/** RGB color arithmetic shared by the renderers. */
final class ColorUtil {

    private ColorUtil() {}

    /** Multiplies each channel by f (darkening/brightening). */
    static int scaleRgb(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * (Nearly) equal channels: the mark of a texture left grayscale on
     * purpose because the game tints it at runtime (foliage, grass...).
     */
    static boolean isGrayscale(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min <= 16;
    }

    /** Per-channel linear interpolation between two colors. */
    static int lerpRgb(int a, int b, float t) {
        t = Math.clamp(t, 0f, 1f);
        int r = (int) (((a >> 16) & 0xFF) + t * (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)));
        int g = (int) (((a >> 8) & 0xFF) + t * (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)));
        int bl = (int) ((a & 0xFF) + t * ((b & 0xFF) - (a & 0xFF)));
        return (r << 16) | (g << 8) | bl;
    }
}
