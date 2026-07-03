package fr.cheesegrinder.sharedjourney.server;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Transforme un chunk en 16x16 pixels ARGB pour une couche donnée.
 * Tout le rendu se fait côté serveur : le serveur est la source de vérité.
 * Implémentation originale (pas dérivée de JourneyMap) : couleurs basées sur
 * MapColor (comme les cartes vanilla) + ombrage de pente.
 */
public final class ChunkColorizer {

    private ChunkColorizer() {}

    /** Rendu d'un chunk complet -> tableau 256 pixels ARGB (index = x + z*16). */
    public static int[] render(ServerLevel level, ChunkAccess chunk, MapLayer layer, int caveBand) {
        int[] out = new int[256];
        ChunkPos cp = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cp.getMinBlockX() + lx;
                int wz = cp.getMinBlockZ() + lz;
                int argb = switch (layer) {
                    case DAY   -> renderSurface(level, chunk, pos, wx, wz, false);
                    case NIGHT -> renderSurface(level, chunk, pos, wx, wz, true);
                    case TOPO  -> renderTopo(level, chunk, pos, wx, wz);
                    case BIOME -> renderBiome(level, chunk, wx, wz);
                    case CAVE  -> renderCave(level, chunk, pos, wx, wz, caveBand);
                };
                out[lx + lz * 16] = argb;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ DAY / NIGHT

    private static int renderSurface(ServerLevel level, ChunkAccess chunk,
                                     BlockPos.MutableBlockPos pos, int wx, int wz, boolean night) {
        int y = surfaceY(chunk, wx, wz);
        if (y <= chunk.getMinBuildHeight()) return 0xFF000000;

        pos.set(wx, y, wz);
        BlockState state = chunk.getBlockState(pos);

        // Eau : couleur d'eau assombrie selon la profondeur.
        if (!state.getFluidState().isEmpty()) {
            int depth = 0;
            while (y - depth > chunk.getMinBuildHeight()
                    && !chunk.getBlockState(pos.set(wx, y - depth, wz)).getFluidState().isEmpty()
                    && depth < 24) {
                depth++;
            }
            float dark = Math.max(0.35f, 1.0f - depth * 0.035f);
            int base = MapColor.WATER.col;
            int rgb = scaleRgb(base, dark);
            return night ? applyNight(level, pos.set(wx, y + 1, wz), rgb) : (0xFF000000 | rgb);
        }

        MapColor mc = state.getMapColor(level, pos);
        int base = (mc == MapColor.NONE ? MapColor.STONE : mc).col;

        // Ombrage de pente : compare la hauteur avec le voisin nord (comme un relief).
        int yNorth = surfaceY(level, chunk, wx, wz - 1);
        float shade = y > yNorth ? 1.10f : (y < yNorth ? 0.82f : 1.0f);
        int rgb = scaleRgb(base, shade);

        if (night) {
            return applyNight(level, pos.set(wx, y + 1, wz), rgb);
        }
        return 0xFF000000 | rgb;
    }

    /** Assombrit selon la lumière de bloc (torches, lave...) au-dessus de la surface. */
    private static int applyNight(ServerLevel level, BlockPos pos, int rgb) {
        int light = level.getBrightness(LightLayer.BLOCK, pos);
        float f = 0.18f + 0.82f * (light / 15.0f);
        // Teinte bleutée pour la nuit
        int r = (int) (((rgb >> 16) & 0xFF) * f * 0.85f);
        int g = (int) (((rgb >> 8) & 0xFF) * f * 0.9f);
        int b = (int) ((rgb & 0xFF) * Math.min(1f, f * 1.05f + 0.05f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ TOPO

    private static int renderTopo(ServerLevel level, ChunkAccess chunk,
                                  BlockPos.MutableBlockPos pos, int wx, int wz) {
        int y = surfaceY(chunk, wx, wz);
        if (y <= chunk.getMinBuildHeight()) return 0xFF000000;

        pos.set(wx, y, wz);
        if (!chunk.getBlockState(pos).getFluidState().isEmpty()) {
            // Eau en dégradé de bleu selon profondeur relative au niveau de la mer
            int sea = level.getSeaLevel();
            float t = Math.max(0f, Math.min(1f, (sea - y) / 32f));
            int b = 255 - (int) (t * 140);
            return 0xFF000000 | (30 << 16) | (80 << 8) | b;
        }

        // Dégradé altitude : vert -> jaune/brun -> gris -> blanc
        int min = level.getSeaLevel();
        int max = chunk.getMaxBuildHeight();
        float t = Math.max(0f, Math.min(1f, (y - min) / (float) Math.max(1, max - min)));
        int rgb;
        if (t < 0.33f)      rgb = lerpRgb(0x2E8B37, 0xC8B454, t / 0.33f);
        else if (t < 0.66f) rgb = lerpRgb(0xC8B454, 0x8A7355, (t - 0.33f) / 0.33f);
        else                rgb = lerpRgb(0x8A7355, 0xF2F2F2, (t - 0.66f) / 0.34f);

        // Courbes de niveau tous les 8 blocs
        if (y % 8 == 0) rgb = scaleRgb(rgb, 0.72f);
        return 0xFF000000 | rgb;
    }

    // ------------------------------------------------------------------ BIOME

    private static int renderBiome(ServerLevel level, ChunkAccess chunk, int wx, int wz) {
        Holder<Biome> biome = level.getBiome(new BlockPos(wx, level.getSeaLevel(), wz));
        // Couleur d'herbe si le biome en définit une, sinon couleur déterministe
        // dérivée de l'identifiant du biome (stable entre serveur et clients).
        var effects = biome.value().getSpecialEffects();
        int rgb = effects.getGrassColorOverride()
                .orElseGet(() -> hashColor(biome.unwrapKey()
                        .map(k -> k.location())
                        .orElse(ResourceLocation.withDefaultNamespace("plains"))));
        return 0xFF000000 | rgb;
    }

    private static int hashColor(ResourceLocation id) {
        int h = id.toString().hashCode();
        // Couleurs saturées et lisibles
        float hue = ((h & 0xFFFF) % 360) / 360f;
        return java.awt.Color.HSBtoRGB(hue, 0.55f, 0.80f) & 0xFFFFFF;
    }

    // ------------------------------------------------------------------ CAVE

    /**
     * Rendu d'une bande verticale de 16 blocs (band = floorDiv(y,16)).
     * Pour chaque colonne, on cherche depuis le haut de la bande la première
     * poche d'air, puis le sol de cette poche : c'est lui qu'on colorie,
     * assombri selon sa profondeur dans la bande. Colonne pleine = noir.
     */
    private static int renderCave(ServerLevel level, ChunkAccess chunk,
                                  BlockPos.MutableBlockPos pos, int wx, int wz, int band) {
        int yTop = band * 16 + 15;
        int yBottom = band * 16;
        yTop = Math.min(yTop, chunk.getMaxBuildHeight() - 1);
        yBottom = Math.max(yBottom, chunk.getMinBuildHeight());

        int airStart = -1;
        for (int y = yTop; y >= yBottom; y--) {
            BlockState s = chunk.getBlockState(pos.set(wx, y, wz));
            boolean open = s.isAir() || !s.getFluidState().isEmpty();
            if (open && airStart == -1) {
                airStart = y;
            } else if (!open && airStart != -1) {
                // s est le sol de la poche d'air
                MapColor mc = s.getMapColor(level, pos);
                int base = (mc == MapColor.NONE ? MapColor.STONE : mc).col;
                // Lave visible en orange
                BlockState above = chunk.getBlockState(pos.set(wx, y + 1, wz));
                if (above.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) base = 0xD45A12;
                float depth = (yTop - (y + 1)) / 16.0f;
                return 0xFF000000 | scaleRgb(base, 1.0f - depth * 0.45f);
            }
        }
        if (airStart != -1) {
            // Poche d'air ouverte jusqu'en bas de la bande : gris sombre
            return 0xFF000000 | 0x1A1A1A;
        }
        return 0xFF000000; // roche pleine
    }

    // ------------------------------------------------------------------ utilitaires

    private static int surfaceY(ChunkAccess chunk, int wx, int wz) {
        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
    }

    /** Hauteur de surface, y compris hors du chunk courant (voisin nord). */
    private static int surfaceY(ServerLevel level, ChunkAccess chunk, int wx, int wz) {
        ChunkPos cp = chunk.getPos();
        if (wz >= cp.getMinBlockZ() && wz <= cp.getMaxBlockZ()) {
            return surfaceY(chunk, wx, wz);
        }
        // Voisin : ne force pas le chargement, retombe sur la même hauteur si absent.
        var neighbor = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        if (neighbor == null) return surfaceY(chunk, wx, cp.getMinBlockZ());
        return neighbor.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
    }

    private static int scaleRgb(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return (r << 16) | (g << 8) | b;
    }

    private static int lerpRgb(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) + t * (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)));
        int g = (int) (((a >> 8) & 0xFF) + t * (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)));
        int bl = (int) ((a & 0xFF) + t * ((b & 0xFF) - (a & 0xFF)));
        return (r << 16) | (g << 8) | bl;
    }
}
