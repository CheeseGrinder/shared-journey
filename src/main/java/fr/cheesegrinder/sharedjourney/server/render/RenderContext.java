package fr.cheesegrinder.sharedjourney.server.render;

import fr.cheesegrinder.sharedjourney.common.util.SurfaceHeight;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Shared context of a chunk render: level, chunk, biome zoom and a reused
 * mutable cursor (avoids one allocation per pixel). One instance per
 * ChunkColorizer.render call, confined to the render thread.
 */
final class RenderContext {

    final ServerLevel level;
    final ChunkAccess chunk;
    final BiomeManager zoom;
    final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    RenderContext(ServerLevel level, ChunkAccess chunk, BiomeManager zoom) {
        this.level = level;
        this.chunk = chunk;
        this.zoom = zoom;
    }

    /**
     * Ground height within the current chunk: the WORLD_SURFACE heightmap
     * walked down past air and hidden blocks (grass...), so shading and
     * contours never use the height of a vegetation block. Leaves {@link #pos}
     * on the returned position.
     */
    int surfaceY(int wx, int wz) {
        return SurfaceHeight.groundY(chunk, wx, wz, pos);
    }

    /** Surface height, including outside the current chunk (north neighbor). */
    int surfaceYAt(int wx, int wz) {
        ChunkPos cp = chunk.getPos();
        if (wz >= cp.getMinBlockZ() && wz <= cp.getMaxBlockZ()) {
            return surfaceY(wx, wz);
        }

        // Neighbor: never forces a load, falls back to the same height if absent.
        ChunkAccess neighbor = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        if (neighbor == null) {
            return surfaceY(wx, cp.getMinBlockZ());
        }

        return SurfaceHeight.groundY(neighbor, wx, wz, pos);
    }
}
