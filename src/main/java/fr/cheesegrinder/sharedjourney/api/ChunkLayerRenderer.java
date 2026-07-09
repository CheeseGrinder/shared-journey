package fr.cheesegrinder.sharedjourney.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Custom layer renderer: turns a chunk into 256 ARGB pixels
 * (index = x + z*16). Called server-side, potentially off the main thread
 * (read-only access to the chunk!).
 */
@FunctionalInterface
public interface ChunkLayerRenderer {
    int[] render(ServerLevel level, ChunkAccess chunk, int caveBand);
}
