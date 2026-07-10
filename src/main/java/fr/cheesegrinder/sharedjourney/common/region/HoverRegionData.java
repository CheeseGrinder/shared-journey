package fr.cheesegrinder.sharedjourney.common.region;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Hover sidecar of a map region (the {@code INFO} pseudo-layer): for every
 * rendered chunk, the surface heights, the surface block per column and the
 * biome per 4x4 cell, palettized region-wide. Produced by the render engine
 * while it has the chunk in hand, synced and cached like an image region —
 * which makes the fullscreen map's hover info fully local (zero request)
 * and, by construction, immune to the chunk-load timing attack: the answer
 * never depends on whether a chunk is currently loaded.
 *
 * <p>Binary format, GZIP-compressed: format version (byte), chunk presence
 * bitmap (16 longs, bit = localCz*32 + localCx), block palette, biome
 * palette (UTF strings), then for each present chunk in bit order:
 * 256 heights (short), 256 block indices (byte), 16 biome indices (byte).
 *
 * <p>Thread-safety: the server writes from render workers and serializes
 * from the sync/save paths — callers synchronize on the instance (same
 * discipline as the region images). The client only reads, on the client
 * thread.
 */
public final class HoverRegionData {

    public static final int CHUNKS = RegionKey.REGION_CHUNKS * RegionKey.REGION_CHUNKS;
    public static final int COLUMNS = 256;
    public static final int BIOME_CELLS = 16;

    private static final byte FORMAT_VERSION = 1;
    /** Palette indices are bytes; index 0 is reserved for "" (unknown). */
    private static final int PALETTE_CAP = 255;

    private final long[] presence = new long[CHUNKS / 64];
    private final short[][] heights = new short[CHUNKS][];
    private final byte[][] blocks = new byte[CHUNKS][];
    private final byte[][] biomes = new byte[CHUNKS][];

    private final List<String> blockPalette = new ArrayList<>();
    private final Map<String, Integer> blockLookup = new HashMap<>();
    private final List<String> biomePalette = new ArrayList<>();
    private final Map<String, Integer> biomeLookup = new HashMap<>();

    public HoverRegionData() {
        // Index 0 = "": air columns, and the overflow fallback.
        paletteIndex(blockPalette, blockLookup, "");
        paletteIndex(biomePalette, biomeLookup, "");
    }

    /** Hover info of one column. Empty ids mean "unknown". */
    public record HoverColumn(int y, String biomeId, String blockId) {}

    /**
     * Stores a chunk's hover data. Ids are palettized region-wide.
     *
     * @param blockIds surface block per column, index dz*16+dx ("" = air)
     * @param biomeIds biome per 4x4 cell, index (dz/4)*4 + (dx/4)
     */
    public void putChunk(int localCx, int localCz, short[] chunkHeights, String[] blockIds, String[] biomeIds) {
        byte[] blockIdx = new byte[COLUMNS];
        for (int i = 0; i < COLUMNS; i++) {
            blockIdx[i] = (byte) paletteIndex(blockPalette, blockLookup, blockIds[i]);
        }

        byte[] biomeIdx = new byte[BIOME_CELLS];
        for (int i = 0; i < BIOME_CELLS; i++) {
            biomeIdx[i] = (byte) paletteIndex(biomePalette, biomeLookup, biomeIds[i]);
        }

        int slot = localCz * RegionKey.REGION_CHUNKS + localCx;
        heights[slot] = chunkHeights.clone();
        blocks[slot] = blockIdx;
        biomes[slot] = biomeIdx;
        presence[slot >> 6] |= 1L << (slot & 63);
    }

    /** Hover info of a column (region-local block coords 0..511), or null if the chunk is absent. */
    public HoverColumn at(int localX, int localZ) {
        int slot = (localZ >> 4) * RegionKey.REGION_CHUNKS + (localX >> 4);
        if ((presence[slot >> 6] & (1L << (slot & 63))) == 0) {
            return null;
        }

        int column = (localZ & 15) * 16 + (localX & 15);
        int cell = ((localZ & 15) >> 2) * 4 + ((localX & 15) >> 2);
        return new HoverColumn(
                heights[slot][column],
                paletteValue(biomePalette, biomes[slot][cell]),
                paletteValue(blockPalette, blocks[slot][column]));
    }

    // ------------------------------------------------------------------ serialization

    public byte[] serialize() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024)) {
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos))) {
                out.writeByte(FORMAT_VERSION);
                for (long word : presence) {
                    out.writeLong(word);
                }

                writePalette(out, blockPalette);
                writePalette(out, biomePalette);

                for (int slot = 0; slot < CHUNKS; slot++) {
                    if ((presence[slot >> 6] & (1L << (slot & 63))) == 0) {
                        continue;
                    }

                    for (short h : heights[slot]) {
                        out.writeShort(h);
                    }
                    out.write(blocks[slot]);
                    out.write(biomes[slot]);
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize hover region data", e);
        }
    }

    /** Parses a serialized region, or returns null when unreadable/unknown format. */
    public static HoverRegionData deserialize(byte[] data) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            if (in.readByte() != FORMAT_VERSION) {
                return null;
            }

            HoverRegionData region = new HoverRegionData();
            for (int i = 0; i < region.presence.length; i++) {
                region.presence[i] = in.readLong();
            }

            readPalette(in, region.blockPalette, region.blockLookup);
            readPalette(in, region.biomePalette, region.biomeLookup);

            for (int slot = 0; slot < CHUNKS; slot++) {
                if ((region.presence[slot >> 6] & (1L << (slot & 63))) == 0) {
                    continue;
                }

                short[] chunkHeights = new short[COLUMNS];
                for (int i = 0; i < COLUMNS; i++) {
                    chunkHeights[i] = in.readShort();
                }

                byte[] blockIdx = new byte[COLUMNS];
                in.readFully(blockIdx);
                byte[] biomeIdx = new byte[BIOME_CELLS];
                in.readFully(biomeIdx);

                region.heights[slot] = chunkHeights;
                region.blocks[slot] = blockIdx;
                region.biomes[slot] = biomeIdx;
            }
            return region;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ palettes

    private static void writePalette(DataOutputStream out, List<String> palette) throws IOException {
        out.writeShort(palette.size());
        for (String id : palette) {
            out.writeUTF(id);
        }
    }

    private static void readPalette(DataInputStream in, List<String> palette, Map<String, Integer> lookup)
            throws IOException {
        int size = in.readUnsignedShort();
        for (int i = 0; i < size; i++) {
            String id = in.readUTF();
            // Index 0 ("") is already seeded by the constructor.
            if (i == 0) {
                continue;
            }

            palette.add(id);
            lookup.put(id, i);
        }
    }

    /** Index of the id in the palette (added if absent; overflow falls back to 0 = ""). */
    private static int paletteIndex(List<String> palette, Map<String, Integer> lookup, String id) {
        Integer existing = lookup.get(id);
        if (existing != null) {
            return existing;
        }

        if (palette.size() > PALETTE_CAP) {
            return 0;
        }

        palette.add(id);
        lookup.put(id, palette.size() - 1);
        return palette.size() - 1;
    }

    private static String paletteValue(List<String> palette, byte idx) {
        int i = idx & 0xFF;
        if (i < palette.size()) {
            return palette.get(i);
        }

        return "";
    }
}
