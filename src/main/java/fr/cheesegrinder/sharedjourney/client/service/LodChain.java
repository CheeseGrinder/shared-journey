package fr.cheesegrinder.sharedjourney.client.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Level-of-detail chain of a region image: pure pixel code (no Minecraft
 * types), so it can be exercised outside the game.
 *
 * <p>Level 0 is the full 512x512 image; every following level halves the side
 * (256, 128, 64, 32) with a 2x2 box filter. Pixels are packed ints whose top
 * byte is the alpha; the three other bytes are treated alike, so the
 * NativeImage ABGR layout needs no conversion.
 *
 * <p>Averaging is <b>alpha-weighted</b>: a colour is the mean of the opaque
 * pixels' colours weighted by their alpha, so transparent (never explored)
 * pixels do not darken the colours next to them. A texel's alpha is the
 * coverage (mean alpha), never rounded down to 0 while any source pixel is
 * visible, which keeps the per-chunk content mask of the last level exact.
 * Fully transparent texels next to visible ones get the neighbours' colour
 * (alpha stays 0) so that bilinear sampling does not blend towards black.
 */
public final class LodChain {

    /** Side of a full-resolution region image. */
    public static final int FULL_SIZE = 512;

    /** Coarsest level: 32x32 texels, one per chunk. */
    public static final int MAX_LEVEL = 4;

    /**
     * Finest level kept in the sidecar file. Level 1 (256x256) would nearly
     * double the size of the file for little gain: it is only drawn while few
     * regions are visible, so rebuilding it from the PNG is cheap.
     */
    public static final int MIN_STORED_LEVEL = 2;

    private static final int MAGIC = 0x534A4C44; // "SJLD"
    private static final int FORMAT = 1;
    private static final int HEADER_BYTES = 4 + 4 + 8 + 4;

    private LodChain() {}

    /** Side, in texels, of a level. */
    public static int sizeOf(int level) {
        return FULL_SIZE >> level;
    }

    /**
     * Coarsest level that still has at least one texel per screen pixel for a
     * region drawn {@code regionPixels} wide (physical pixels), capped at
     * {@link #MAX_LEVEL}. Level 0 as soon as the region is drawn at 512
     * physical pixels or more.
     */
    public static int levelFor(double regionPixels) {
        int level = 0;
        while (level < MAX_LEVEL && sizeOf(level + 1) >= regionPixels - 1.0e-3) {
            level++;
        }

        return level;
    }

    /**
     * Builds levels 1..{@link #MAX_LEVEL} from a full image (row-major, 512x512).
     * The returned array is indexed by level; entry 0 is {@code full} itself.
     */
    public static int[][] build(int[] full) {
        if (full.length != FULL_SIZE * FULL_SIZE) {
            throw new IllegalArgumentException("Expected a 512x512 image, got " + full.length + " pixels");
        }

        int[][] levels = new int[MAX_LEVEL + 1][];
        levels[0] = full;
        for (int level = 1; level <= MAX_LEVEL; level++) {
            levels[level] = halve(levels[level - 1], sizeOf(level - 1));
            bleedTransparent(levels[level], sizeOf(level));
        }

        return levels;
    }

    /** Alpha-weighted 2x2 box filter: {@code src} is size x size, the result (size/2)^2. */
    static int[] halve(int[] src, int size) {
        int half = size / 2;
        int[] out = new int[half * half];
        for (int y = 0; y < half; y++) {
            int row0 = (2 * y) * size;
            int row1 = row0 + size;
            for (int x = 0; x < half; x++) {
                int x0 = 2 * x;
                out[y * half + x] = average(src[row0 + x0], src[row0 + x0 + 1], src[row1 + x0], src[row1 + x0 + 1]);
            }
        }

        return out;
    }

    /** Mean of four pixels, colour weighted by alpha, alpha = mean coverage (at least 1 when any is visible). */
    private static int average(int p0, int p1, int p2, int p3) {
        int a0 = p0 >>> 24;
        int a1 = p1 >>> 24;
        int a2 = p2 >>> 24;
        int a3 = p3 >>> 24;
        int total = a0 + a1 + a2 + a3;
        if (total == 0) {
            return 0;
        }

        int alpha = Math.max(1, (total + 2) >> 2);
        int c1 = weighted(p0, a0, 0) + weighted(p1, a1, 0) + weighted(p2, a2, 0) + weighted(p3, a3, 0);
        int c2 = weighted(p0, a0, 8) + weighted(p1, a1, 8) + weighted(p2, a2, 8) + weighted(p3, a3, 8);
        int c3 = weighted(p0, a0, 16) + weighted(p1, a1, 16) + weighted(p2, a2, 16) + weighted(p3, a3, 16);
        int half = total >> 1;
        return (alpha << 24) | (((c3 + half) / total) << 16) | (((c2 + half) / total) << 8) | ((c1 + half) / total);
    }

    private static int weighted(int pixel, int alpha, int shift) {
        return ((pixel >>> shift) & 0xFF) * alpha;
    }

    /**
     * Gives every fully transparent texel that touches a visible one the mean
     * colour of its visible neighbours (alpha stays 0). Bilinear sampling then
     * fades the visible edge into "nothing" instead of into black.
     */
    static void bleedTransparent(int[] image, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int index = y * size + x;
                if ((image[index] >>> 24) == 0) {
                    image[index] = visibleNeighbourColour(image, size, x, y);
                }
            }
        }
    }

    private static int visibleNeighbourColour(int[] image, int size, int x, int y) {
        int count = 0;
        int c1 = 0;
        int c2 = 0;
        int c3 = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= size || ny >= size) {
                    continue;
                }

                int neighbour = image[ny * size + nx];
                if ((neighbour >>> 24) != 0) {
                    count++;
                    c1 += neighbour & 0xFF;
                    c2 += (neighbour >>> 8) & 0xFF;
                    c3 += (neighbour >>> 16) & 0xFF;
                }
            }
        }

        if (count == 0) {
            return 0;
        }

        return (((c3 + count / 2) / count) << 16) | (((c2 + count / 2) / count) << 8) | ((c1 + count / 2) / count);
    }

    // ------------------------------------------------------------------ sidecar file

    /**
     * Decoded sidecar: the version of the region image it was built from, and
     * the levels {@link #MIN_STORED_LEVEL}..{@link #MAX_LEVEL} (other entries null).
     */
    public record Decoded(long sourceVersion, int[][] levels) {}

    /**
     * Serializes levels {@link #MIN_STORED_LEVEL}..{@link #MAX_LEVEL} of a chain:
     * a small plain header (magic, format, source version, coarsest level) then
     * one deflated stream of the little-endian pixel data of every stored level
     * in order.
     */
    public static byte[] encode(long sourceVersion, int[][] levels) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
        DataOutputStream header = new DataOutputStream(bytes);
        header.writeInt(MAGIC);
        header.writeInt(FORMAT);
        header.writeLong(sourceVersion);
        header.writeInt(MAX_LEVEL);
        header.flush();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream body = new DeflaterOutputStream(bytes, deflater)) {
            for (int level = MIN_STORED_LEVEL; level <= MAX_LEVEL; level++) {
                body.write(toLittleEndian(levels[level]));
            }
        } finally {
            deflater.end();
        }

        return bytes.toByteArray();
    }

    /** Parses a sidecar; null when it is truncated, corrupt or of another format. */
    public static Decoded decode(byte[] data) {
        if (data == null || data.length < HEADER_BYTES) {
            return null;
        }

        try {
            DataInputStream header = new DataInputStream(new ByteArrayInputStream(data, 0, HEADER_BYTES));
            if (header.readInt() != MAGIC || header.readInt() != FORMAT) {
                return null;
            }

            long sourceVersion = header.readLong();
            if (header.readInt() != MAX_LEVEL) {
                return null;
            }

            int[][] levels = new int[MAX_LEVEL + 1][];
            try (InflaterInputStream body =
                    new InflaterInputStream(new ByteArrayInputStream(data, HEADER_BYTES, data.length - HEADER_BYTES))) {
                for (int level = MIN_STORED_LEVEL; level <= MAX_LEVEL; level++) {
                    int pixels = sizeOf(level) * sizeOf(level);
                    byte[] raw = body.readNBytes(pixels * 4);
                    if (raw.length != pixels * 4) {
                        return null;
                    }

                    levels[level] = fromLittleEndian(raw);
                }
            }

            return new Decoded(sourceVersion, levels);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] toLittleEndian(int[] pixels) {
        ByteBuffer buffer = ByteBuffer.allocate(pixels.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(pixels);
        return buffer.array();
    }

    private static int[] fromLittleEndian(byte[] raw) {
        int[] pixels = new int[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
        return pixels;
    }
}
