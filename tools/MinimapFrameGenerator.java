import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Offline generator of the minimap frame textures
 * ({@code assets/sharedjourney/textures/gui/minimap_frame/<theme>_round.png}
 * and {@code <theme>_square.png}). There is no art tool in the project:
 * the frames are described procedurally here, supersampled, and baked to
 * PNG. Rerun it after changing a theme, then keep the numbers below in sync
 * with {@code client/render/MinimapFrame.java}.
 *
 * <p>Two texture layouts:
 *
 * <ul>
 *   <li><b>Round</b>: a polar strip, {@value #ROUND_W} x {@value #ROUND_H}
 *       texels. X is the angle (a full turn, seamless, 0 = east, clockwise
 *       on screen), Y is the radial position from the inner edge (row 0) to
 *       the outer edge (last row). The client wraps it around a ring mesh at
 *       the exact on-screen radius, so the circle is antialiased at any
 *       minimap size and GUI scale. The first and last rows are transparent.
 *   <li><b>Square</b>: a 9-slice without centre, {@value #SLICE_SCALE}
 *       texels per GUI pixel. Corners are {@code thickness * SLICE_SCALE}
 *       texels wide, the edges between them are {@value #MID} texels that
 *       the client stretches along the side (so edges are uniform along
 *       their length).
 * </ul>
 *
 * <p>Not part of the mod build. From the repo root:
 *
 * <pre>
 * java tools/MinimapFrameGenerator.java \
 *     src/main/resources/assets/sharedjourney/textures/gui/minimap_frame
 * </pre>
 */
public class MinimapFrameGenerator {

    /** Texels per GUI pixel in the square textures. */
    static final int SLICE_SCALE = 4;

    /** Width in texels of the stretched middle part of each square edge. */
    static final int MID = 8;

    static final int ROUND_W = 512;
    static final int ROUND_H = 32;

    /** Sub-samples per texel and axis. */
    static final int SUB = 4;

    /** Unit vector pointing to the light (top-left of the screen), x and y. */
    static final double LIGHT = -Math.sqrt(0.5);

    /** Color and alpha (not premultiplied) of the frame at one point. */
    record Rgba(double r, double g, double b, double a) {}

    /**
     * One theme's cross-section. {@code s} is the distance in GUI pixels
     * from the inner edge of the band (0) to its outer edge ({@code
     * thickness}); {@code lit} in [-1, 1] says how much the local outward
     * normal faces the light; {@code u}, in [0, 1), is the angle (round
     * frames) used to vary the material along the ring.
     */
    interface Profile {
        Rgba at(double s, double thickness, double lit, double u, boolean round);
    }

    record Theme(String id, double roundThickness, int squareThickness, Profile profile) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java MinimapFrameGenerator.java <output dir>");
            System.exit(1);
        }

        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        for (Theme theme : themes()) {
            writeRound(theme, out.resolve(theme.id() + "_round.png").toFile());
            writeSquare(theme, out.resolve(theme.id() + "_square.png").toFile());
            System.out.println("Generated theme " + theme.id());
        }
    }

    /** The bundled themes; thicknesses must match MinimapFrame.java. */
    static Theme[] themes() {
        return new Theme[] {
            new Theme("classic", 3.25, 1, MinimapFrameGenerator::classic),
            new Theme("stone", 4.0, 4, MinimapFrameGenerator::stone),
            new Theme("wood", 4.0, 4, MinimapFrameGenerator::wood),
            new Theme("thin", 2.25, 2, MinimapFrameGenerator::thin)
        };
    }

    // ------------------------------------------------------------------ texture layouts

    static void writeRound(Theme theme, File file) throws IOException {
        BufferedImage img = new BufferedImage(ROUND_W, ROUND_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < ROUND_H; y++) {
            for (int x = 0; x < ROUND_W; x++) {
                img.setRGB(x, y, roundTexel(theme, x, y));
            }
        }
        ImageIO.write(img, "png", file);
    }

    static int roundTexel(Theme theme, int x, int y) {
        // The outermost rows stay transparent: the mesh samples exactly
        // v = 0 and v = 1 on the ring edges, where REPEAT wraps rows. Their
        // color is copied from the neighbouring row so bilinear filtering
        // fades the alpha without darkening the rim.
        if (y == 0 || y == ROUND_H - 1) {
            int neighbour = roundTexel(theme, x, y == 0 ? 1 : ROUND_H - 2);
            return neighbour & 0x00FFFFFF;
        }

        Accumulator acc = new Accumulator();
        for (int sy = 0; sy < SUB; sy++) {
            for (int sx = 0; sx < SUB; sx++) {
                double u = (x + (sx + 0.5) / SUB) / ROUND_W;
                double s = (y + (sy + 0.5) / SUB) / ROUND_H * theme.roundThickness();
                double angle = 2 * Math.PI * u;
                double lit = LIGHT * (Math.cos(angle) + Math.sin(angle));
                acc.add(theme.profile().at(s, theme.roundThickness(), lit, u, true));
            }
        }
        return acc.argb();
    }

    static void writeSquare(Theme theme, File file) throws IOException {
        int slice = theme.squareThickness() * SLICE_SCALE;
        int size = 2 * slice + MID;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                img.setRGB(x, y, squareTexel(theme, x, y, slice, size));
            }
        }
        ImageIO.write(img, "png", file);
    }

    static int squareTexel(Theme theme, int x, int y, int slice, int size) {
        double t = theme.squareThickness();
        Accumulator acc = new Accumulator();
        for (int sy = 0; sy < SUB; sy++) {
            for (int sx = 0; sx < SUB; sx++) {
                double tx = x + (sx + 0.5) / SUB;
                double ty = y + (sy + 0.5) / SUB;
                acc.add(squareSample(theme, tx, ty, slice, size, t));
            }
        }
        return acc.argb();
    }

    /** Profile at a texel position of the 9-slice, with mitered corners. */
    static Rgba squareSample(Theme theme, double tx, double ty, int slice, int size, double t) {
        double dx = edgeDistance(tx, slice, size);
        double dy = edgeDistance(ty, slice, size);
        if (dx == Double.MAX_VALUE && dy == Double.MAX_VALUE) {
            return new Rgba(0, 0, 0, 0);
        }

        double o = Math.min(dx, dy);
        boolean horizontalSide = dx <= dy;
        double coord = horizontalSide ? tx : ty;
        boolean leadingSide = coord < size / 2.0;
        // Left and top sides face the light, right and bottom sides do not.
        double lit = leadingSide ? 1 : -1;
        return theme.profile().at(t - o, t, lit, 0.37, false);
    }

    /** Distance in GUI pixels to the outer boundary, or MAX_VALUE inside the stretched middle. */
    static double edgeDistance(double texel, int slice, int size) {
        if (texel < slice) {
            return texel / SLICE_SCALE;
        }

        if (texel > size - slice) {
            return (size - texel) / SLICE_SCALE;
        }

        return Double.MAX_VALUE;
    }

    /** Averages premultiplied samples, so antialiased edges do not get dark fringes. */
    static final class Accumulator {
        private double r;
        private double g;
        private double b;
        private double a;
        private int count;

        void add(Rgba c) {
            r += c.r() * c.a();
            g += c.g() * c.a();
            b += c.b() * c.a();
            a += c.a();
            count++;
        }

        int argb() {
            if (a <= 0) {
                return 0;
            }

            int alpha = to8(a / count);
            int red = to8(r / a);
            int green = to8(g / a);
            int blue = to8(b / a);
            return (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
    }

    static int to8(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    // ------------------------------------------------------------------ themes

    /**
     * Classic: the original procedural look, reproduced. Round: a 1 px black
     * liner on the map side, a gray band outside, every edge feathered over
     * 0.75 px. Square: a 1 px dark outline.
     */
    static Rgba classic(double s, double t, double lit, double u, boolean round) {
        if (!round) {
            return rgb(0x202020, 1);
        }

        double black = ramp(s, 0, 0.75);
        // Black to gray transition between s = 1.0 and 1.5.
        double grayMix = ramp(s, 1.0, 1.5);
        double alpha = Math.min(black, 1 - ramp(s, t - 0.75, t));
        return mix(rgb(0x141414, alpha), rgb(0xA8A8A8, alpha), grayMix);
    }

    /** Thin: a translucent light line inside, a dark 1 px outline outside. */
    static Rgba thin(double s, double t, double lit, double u, boolean round) {
        double lightAlpha = 0.6 * Math.min(ramp(s, 0.05, 0.4), 1 - ramp(s, 0.85, 1.0));
        double darkAlpha = Math.min(ramp(s, 0.9, 1.1), round ? 1 - ramp(s, t - 0.25, t) : 1);
        Rgba light = rgb(0xD0D0D0, lightAlpha);
        Rgba dark = rgb(0x141414, darkAlpha);
        return over(dark, light);
    }

    /** Stone: gray speckled face between a light and a dark bevel, dark outlines. */
    static Rgba stone(double s, double t, double lit, double u, boolean round) {
        double grain = 0.6 * noise(u, s / t, 160, 8, 11) + 0.4 * noise(u, s / t, 320, 16, 12);
        int face = shade(0x7C7C7C, (grain - 0.5) * 0.34);
        return bevelled(s, t, lit, round, face, 0xB4B4B4, 0x464646, 0x1C1C1C);
    }

    /** Wood: warm grain running along the frame, between a light and a dark bevel. */
    static Rgba wood(double s, double t, double lit, double u, boolean round) {
        double coarse = noise(u, s / t, 9, 12, 21);
        double fine = noise(u, s / t, 23, 40, 22);
        double grain = 0.65 * coarse + 0.35 * fine;
        int face = shade(0x8A5A2C, (grain - 0.5) * 0.55);
        return bevelled(s, t, lit, round, face, 0xB98A55, 0x4A2E16, 0x24160B);
    }

    /**
     * Shared frame section: a dark inner shadow line (it overlaps the last
     * map pixel), an inner bevel, the material face, an outer bevel and a
     * dark outer line. Bevels are lit by {@code lit}: the outer bevel
     * lights up where the outward normal faces the light, the inner bevel
     * (concave) does the opposite. Inner edge fades in; the outer edge only
     * fades out on round frames (square edges are pixel aligned).
     */
    static Rgba bevelled(double s, double t, double lit, boolean round, int face, int hi, int lo, int line) {
        double bevel = 0.7;
        double outerLine = 0.45;
        double[] ends = {1.0, 1.0 + bevel, t - outerLine - bevel, t - outerLine, t};
        int innerBevel = lightened(face, hi, lo, -lit);
        int outerBevel = lightened(face, hi, lo, lit);
        int[] colors = {line, innerBevel, face, outerBevel, line};
        int rgb = zoneColor(s, ends, colors);
        double alpha = ramp(s, 0, 0.6);
        if (round) {
            alpha = Math.min(alpha, 1 - ramp(s, t - 0.4, t));
        }
        return rgb(rgb, alpha);
    }

    /** Piecewise color by zone end, blended over 0.2 px around each boundary. */
    static int zoneColor(double s, double[] ends, int[] colors) {
        int zone = 0;
        while (zone < ends.length - 1 && s >= ends[zone]) {
            zone++;
        }

        int rgb = colors[zone];
        if (zone > 0) {
            double k = ramp(s, ends[zone - 1] - 0.1, ends[zone - 1] + 0.1);
            rgb = blend(colors[zone - 1], rgb, k);
        }
        if (zone < ends.length - 1) {
            double k = ramp(s, ends[zone] - 0.1, ends[zone] + 0.1);
            rgb = blend(rgb, colors[zone + 1], k);
        }
        return rgb;
    }

    /** Face color pulled to the highlight (k &gt; 0) or the shadow (k &lt; 0) by |k|. */
    static int lightened(int face, int hi, int lo, double k) {
        if (k >= 0) {
            return blend(face, hi, k);
        }

        return blend(face, lo, -k);
    }

    // ------------------------------------------------------------------ helpers

    /** Smooth 0 -> 1 ramp between a and b. */
    static double ramp(double v, double a, double b) {
        double x = Math.max(0, Math.min(1, (v - a) / (b - a)));
        return x * x * (3 - 2 * x);
    }

    static Rgba rgb(int rgb, double alpha) {
        return new Rgba(((rgb >> 16) & 0xFF) / 255.0, ((rgb >> 8) & 0xFF) / 255.0, (rgb & 0xFF) / 255.0, alpha);
    }

    /** Linear mix of two translucent colors (color and alpha both interpolated). */
    static Rgba mix(Rgba a, Rgba b, double k) {
        return new Rgba(
                a.r() + (b.r() - a.r()) * k,
                a.g() + (b.g() - a.g()) * k,
                a.b() + (b.b() - a.b()) * k,
                a.a() + (b.a() - a.a()) * k);
    }

    /** Source-over compositing of {@code top} on {@code bottom}. */
    static Rgba over(Rgba top, Rgba bottom) {
        double a = top.a() + bottom.a() * (1 - top.a());
        if (a <= 0) {
            return new Rgba(top.r(), top.g(), top.b(), 0);
        }

        double r = (top.r() * top.a() + bottom.r() * bottom.a() * (1 - top.a())) / a;
        double g = (top.g() * top.a() + bottom.g() * bottom.a() * (1 - top.a())) / a;
        double b = (top.b() * top.a() + bottom.b() * bottom.a() * (1 - top.a())) / a;
        return new Rgba(r, g, b, a);
    }

    static int blend(int from, int to, double k) {
        int r = (int) Math.round(((from >> 16) & 0xFF) * (1 - k) + ((to >> 16) & 0xFF) * k);
        int g = (int) Math.round(((from >> 8) & 0xFF) * (1 - k) + ((to >> 8) & 0xFF) * k);
        int b = (int) Math.round((from & 0xFF) * (1 - k) + (to & 0xFF) * k);
        return (r << 16) | (g << 8) | b;
    }

    /** Lightens (amount &gt; 0) or darkens (amount &lt; 0) a color by a fraction of 255. */
    static int shade(int rgb, double amount) {
        int delta = (int) Math.round(amount * 255);
        int r = clamp(((rgb >> 16) & 0xFF) + delta);
        int g = clamp(((rgb >> 8) & 0xFF) + delta);
        int b = clamp((rgb & 0xFF) + delta);
        return (r << 16) | (g << 8) | b;
    }

    static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * Value noise in [0, 1], periodic along {@code u} (so the ring closes
     * seamlessly), with {@code cellsU} cells around the circle and
     * {@code cellsV} across the band.
     */
    static double noise(double u, double v, int cellsU, int cellsV, int seed) {
        double x = u * cellsU;
        double y = v * cellsV;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0;
        double fy = y - y0;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = cell(x0, y0, cellsU, seed);
        double b = cell(x0 + 1, y0, cellsU, seed);
        double c = cell(x0, y0 + 1, cellsU, seed);
        double d = cell(x0 + 1, y0 + 1, cellsU, seed);
        double top = a + (b - a) * fx;
        double bottom = c + (d - c) * fx;
        return top + (bottom - top) * fy;
    }

    static double cell(int x, int y, int period, int seed) {
        int h = Math.floorMod(x, period) * 73856093 ^ y * 19349663 ^ seed * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }
}
