package fr.cheesegrinder.sharedjourney.server.render;

/** Arithmétique de couleurs RGB partagée par les renderers. */
final class ColorUtil {

    private ColorUtil() {}

    /** Multiplie chaque canal par f (assombrissement/éclaircissement). */
    static int scaleRgb(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return (r << 16) | (g << 8) | b;
    }

    /** Interpolation linéaire canal par canal entre deux couleurs. */
    static int lerpRgb(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) + t * (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)));
        int g = (int) (((a >> 8) & 0xFF) + t * (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)));
        int bl = (int) ((a & 0xFF) + t * ((b & 0xFF) - (a & 0xFF)));
        return (r << 16) | (g << 8) | bl;
    }
}
