package fr.cheesegrinder.sharedjourney.server.render;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Contexte partagé d'un rendu de chunk : niveau, chunk, zoom de biomes et
 * curseur mutable réutilisé (évite une allocation par pixel). Une instance
 * par appel de ChunkColorizer.render, confinée au thread de rendu.
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

    /** Hauteur de surface (heightmap WORLD_SURFACE) dans le chunk courant. */
    int surfaceY(int wx, int wz) {
        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
    }

    /** Hauteur de surface, y compris hors du chunk courant (voisin nord). */
    int surfaceYAt(int wx, int wz) {
        ChunkPos cp = chunk.getPos();
        if (wz >= cp.getMinBlockZ() && wz <= cp.getMaxBlockZ()) {
            return surfaceY(wx, wz);
        }

        // Voisin : ne force pas le chargement, retombe sur la même hauteur si absent.
        ChunkAccess neighbor = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        if (neighbor == null) {
            return surfaceY(wx, cp.getMinBlockZ());
        }

        return neighbor.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
    }
}
