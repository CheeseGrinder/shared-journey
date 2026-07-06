package fr.cheesegrinder.sharedjourney.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Rendu d'une couche custom : transforme un chunk en 256 pixels ARGB
 * (index = x + z*16). Appelé côté serveur, potentiellement hors main thread
 * (lecture seule du chunk uniquement !).
 */
@FunctionalInterface
public interface ChunkLayerRenderer {
    int[] render(ServerLevel level, ChunkAccess chunk, int caveBand);
}
