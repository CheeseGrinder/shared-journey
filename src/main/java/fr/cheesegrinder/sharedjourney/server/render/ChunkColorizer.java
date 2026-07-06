package fr.cheesegrinder.sharedjourney.server.render;

import fr.cheesegrinder.sharedjourney.api.MapLayer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Transforme un chunk en 16x16 pixels ARGB pour une couche donnée (façade :
 * le rendu de chaque couche vit dans son propre renderer du package).
 * Tout le rendu se fait côté serveur : le serveur est la source de vérité.
 * Implémentation originale (pas dérivée de JourneyMap) : couleurs basées sur
 * MapColor (comme les cartes vanilla) + ombrage de pente.
 */
public final class ChunkColorizer {

    private ChunkColorizer() {}

    /** Rendu d'un chunk complet -> tableau 256 pixels ARGB (index = x + z*16). */
    public static int[] render(
            ServerLevel level, ChunkAccess chunk, ChunkAccess[] neighbors, MapLayer layer, int caveBand) {
        int[] out = new int[256];
        ChunkPos cp = chunk.getPos();
        // Zoom de biomes du jeu (frontières irrégulières bloc par bloc), branché
        // sur le chunk ET ses voisins : sûr depuis un thread de rendu, et fidèle
        // jusqu'aux bordures. Sans lui, les biomes apparaissent en patchs carrés
        // de 4x4 blocs à bords droits.
        BiomeManager zoom =
                new BiomeManager(neighborSource(chunk, neighbors), BiomeManager.obfuscateSeed(level.getSeed()));
        RenderContext ctx = new RenderContext(level, chunk, zoom);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cp.getMinBlockX() + lx;
                int wz = cp.getMinBlockZ() + lz;
                int argb =
                        switch (layer) {
                            case DAY -> SurfaceRenderer.render(ctx, wx, wz, false);
                            case NIGHT -> SurfaceRenderer.render(ctx, wx, wz, true);
                            case TOPO -> TopoRenderer.render(ctx, wx, wz);
                            case BIOME -> BiomeRenderer.render(ctx, wx, wz);
                            case CAVE -> CaveRenderer.render(ctx, wx, wz, caveBand);
                        };
                out[lx + lz * 16] = argb;
            }
        }
        return out;
    }

    /**
     * Source de biomes couvrant le chunk et ses 8 voisins (index 3x3, [4] =
     * centre). Le zoom de biomes lit jusqu'à une cellule au-delà du bloc : sans
     * les voisins, les frontières seraient déformées le long des bords de chunk.
     * Un voisin absent retombe sur le chunk central (bord approximé, corrigé au
     * prochain re-rendu).
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
