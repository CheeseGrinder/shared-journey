package fr.cheesegrinder.sharedjourney.server.render;

import fr.cheesegrinder.sharedjourney.api.MapLayer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Turns a chunk into 16x16 ARGB pixels for a given layer (facade: each
 * layer's rendering lives in its own renderer within the package).
 * All rendering happens server-side: the server is the source of truth.
 * Original implementation (not derived from JourneyMap): MapColor-based
 * colors (like vanilla maps) + slope shading.
 */
public final class ChunkColorizer {

    private ChunkColorizer() {}

    /** Full chunk render -> 256 ARGB pixel array (index = x + z*16). */
    public static int[] render(
            ServerLevel level, ChunkAccess chunk, ChunkAccess[] neighbors, MapLayer layer, int caveBand) {
        int[] out = new int[256];
        ChunkPos cp = chunk.getPos();
        // The game's biome zoom (irregular block-by-block borders), plugged
        // into the chunk AND its neighbors: safe from a render thread, and
        // faithful up to the edges. Without it, biomes show up as square
        // 4x4-block patches with straight edges.
        BiomeManager zoom =
                new BiomeManager(neighborSource(chunk, neighbors), BiomeManager.obfuscateSeed(level.getSeed()));
        RenderContext ctx = new RenderContext(level, chunk, zoom);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cp.getMinBlockX() + lx;
                int wz = cp.getMinBlockZ() + lz;
                out[lx + lz * 16] = renderPixel(ctx, layer, wx, wz, caveBand);
            }
        }
        return out;
    }

    /**
     * One pixel of a built-in layer (custom layers render through their
     * own {@code ChunkLayerRenderer}, never through this facade).
     */
    private static int renderPixel(RenderContext ctx, MapLayer layer, int wx, int wz, int caveBand) {
        if (layer == MapLayer.DAY) {
            return SurfaceRenderer.render(ctx, wx, wz, false);
        }
        if (layer == MapLayer.NIGHT) {
            return SurfaceRenderer.render(ctx, wx, wz, true);
        }
        if (layer == MapLayer.TOPO) {
            return TopoRenderer.render(ctx, wx, wz);
        }
        if (layer == MapLayer.BIOME) {
            return BiomeRenderer.render(ctx, wx, wz);
        }
        if (layer == MapLayer.CAVE) {
            return CaveRenderer.render(ctx, wx, wz, caveBand);
        }

        // INFO is the hover-data sidecar, custom layers never reach here.
        throw new IllegalArgumentException("Not a built-in renderable layer: " + layer.id());
    }

    /**
     * Biome source covering the chunk and its 8 neighbors (3x3 index, [4] =
     * center). The biome zoom reads up to one cell beyond the block: without
     * the neighbors, borders would be distorted along chunk edges. A missing
     * neighbor falls back to the center chunk (approximated edge, fixed on
     * the next re-render).
     */
    private static BiomeManager.NoiseBiomeSource neighborSource(ChunkAccess chunk, ChunkAccess[] neighbors) {
        ChunkPos cp = chunk.getPos();
        return (qx, qy, qz) -> {
            int dx = (qx >> 2) - cp.x;
            int dz = (qz >> 2) - cp.z;
            ChunkAccess owner = chunk;
            if (dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1) {
                ChunkAccess neighbor = neighbors[(dx + 1) + (dz + 1) * 3];
                if (neighbor != null) {
                    owner = neighbor;
                }
            }

            return owner.getNoiseBiome(qx, qy, qz);
        };
    }
}
