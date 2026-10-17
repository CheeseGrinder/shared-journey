package fr.cheesegrinder.sharedjourney.common.overlay;

import java.util.Locale;

/**
 * Per-chunk map overlays whose data is computed by the server and shipped
 * to clients as one 32x32 chunk bitmap per map region (see
 * {@code ChunkOverlayPayloads}). The bitmap layout is the one used by the
 * regen masks and the client content masks: bit {@code localZ * 32 + localX}
 * of a 16-long array.
 */
public enum ChunkOverlayKind {

    /** Chunks kept loaded by a vanilla {@code /forceload} or a NeoForge chunk ticket. */
    FORCED_CHUNKS("forced_chunks"),

    /** Slime chunks of the overworld (derived from the world seed, which is never sent). */
    SLIME_CHUNKS("slime_chunks");

    /** Chunk bits of one region bitmap: 32 x 32 chunks. */
    public static final int REGION_BITS = 1024;

    /** Longs of one region bitmap. */
    public static final int REGION_WORDS = REGION_BITS / Long.SIZE;

    private final String id;

    ChunkOverlayKind(String id) {
        this.id = id;
    }

    /** Stable wire id. */
    public String id() {
        return id;
    }

    /** Kind of a wire id (case-insensitive), or null when unknown. */
    public static ChunkOverlayKind fromId(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        for (ChunkOverlayKind kind : values()) {
            if (kind.id.equals(normalized)) {
                return kind;
            }
        }
        return null;
    }

    /** Bit index of a chunk inside its region bitmap. */
    public static int bitIndex(int chunkX, int chunkZ) {
        return Math.floorMod(chunkZ, 32) * 32 + Math.floorMod(chunkX, 32);
    }

    /** Is the chunk's bit set in a region bitmap? An empty array means "no chunk set". */
    public static boolean isSet(long[] bits, int chunkX, int chunkZ) {
        if (bits == null || bits.length < REGION_WORDS) {
            return false;
        }

        int bit = bitIndex(chunkX, chunkZ);
        return (bits[bit >> 6] & (1L << (bit & 63))) != 0;
    }
}
