package fr.cheesegrinder.sharedjourney.server.render;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.config.ServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
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
    public static int[] render(ServerLevel level, ChunkAccess chunk, ChunkAccess[] neighbors,
                               MapLayer layer, int caveBand) {
        int[] out = new int[256];
        ChunkPos cp = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Zoom de biomes du jeu (frontières irrégulières bloc par bloc), branché
        // sur le chunk ET ses voisins : sûr depuis un thread de rendu, et fidèle
        // jusqu'aux bordures. Sans lui, les biomes apparaissent en patchs carrés
        // de 4x4 blocs à bords droits.
        BiomeManager zoom = new BiomeManager(neighborSource(chunk, neighbors),
                BiomeManager.obfuscateSeed(level.getSeed()));

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cp.getMinBlockX() + lx;
                int wz = cp.getMinBlockZ() + lz;
                int argb = switch (layer) {
                    case DAY   -> renderSurface(level, chunk, zoom, pos, wx, wz, false);
                    case NIGHT -> renderSurface(level, chunk, zoom, pos, wx, wz, true);
                    case TOPO  -> renderTopo(level, chunk, pos, wx, wz);
                    case BIOME -> renderBiome(level, zoom, pos, wx, wz);
                    case CAVE  -> renderCave(level, chunk, pos, wx, wz, caveBand);
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

    // ------------------------------------------------------------------ DAY / NIGHT

    private static int renderSurface(ServerLevel level, ChunkAccess chunk, BiomeManager zoom,
                                     BlockPos.MutableBlockPos pos, int wx, int wz, boolean night) {
        int y = surfaceY(chunk, wx, wz);
        if (y <= chunk.getMinBuildHeight()) {
            return 0xFF000000;
        }

        pos.set(wx, y, wz);
        BlockState state = chunk.getBlockState(pos);
        // La végétation décorative (fleurs, herbes, pousses...) ne doit pas
        // apparaître sur la carte : on peint le bloc situé dessous.
        while (y > chunk.getMinBuildHeight() && isMapHidden(state) && state.getFluidState().isEmpty()) {
            y--;
            pos.set(wx, y, wz);
            state = chunk.getBlockState(pos);
        }
        // Eau : couleur du biome (lissée entre biomes voisins) assombrie selon
        // la profondeur.
        if (!state.getFluidState().isEmpty()) {
            int depth = 0;
            while (y - depth > chunk.getMinBuildHeight()
                    && !chunk.getBlockState(pos.set(wx, y - depth, wz)).getFluidState().isEmpty()
                    && depth < 24) {
                depth++;
            }
            float dark = Math.max(0.35f, 1.0f - depth * 0.035f);
            int base = blendedColor(zoom, wx, y, wz, (b, x, z) -> b.getWaterColor());
            int rgb = scaleRgb(base, dark);
            return night ? applyNight(level, pos.set(wx, y + 1, wz), rgb) : (0xFF000000 | rgb);
        }

        int base = tintedBaseColor(level, state, zoom, pos, wx, y, wz);

        // Ombrage de pente : compare la hauteur avec le voisin nord (comme un relief).
        int yNorth = surfaceY(level, chunk, wx, wz - 1);
        float shade = y > yNorth ? 1.10f : (y < yNorth ? 0.82f : 1.0f);
        int rgb = scaleRgb(base, shade);

        if (night) {
            return applyNight(level, pos.set(wx, y + 1, wz), rgb);
        }
        return 0xFF000000 | rgb;
    }

    /**
     * Couleur de base d'un bloc de surface, teintée par le biome quand le bloc
     * l'est en jeu (herbe, feuillage). Les colormaps texture de vanilla ne sont
     * pas chargées sur un serveur dédié : on approxime le triangle
     * température/précipitations des colormaps (vérifié proche des valeurs
     * réelles : plaines ≈ 0x8CBD57 pour 0x91BD59).
     */
    private static int tintedBaseColor(ServerLevel level, BlockState state, BiomeManager zoom,
                                       BlockPos pos, int wx, int y, int wz) {
        if (isGrassTinted(state)) {
            return blendedColor(zoom, wx, y, wz, ChunkColorizer::grassColor);
        }
        if (state.is(BlockTags.LEAVES) || state.is(Blocks.VINE)) {
            // Essences à teinte fixe (comme vanilla), sinon feuillage du biome.
            int tint;
            if (state.is(Blocks.BIRCH_LEAVES)) {
                tint = FoliageColor.getBirchColor();
            } else if (state.is(Blocks.SPRUCE_LEAVES)) {
                tint = FoliageColor.getEvergreenColor();
            } else if (state.is(Blocks.MANGROVE_LEAVES)) {
                tint = FoliageColor.getMangroveColor();
            } else if (state.is(Blocks.CHERRY_LEAVES) || state.is(Blocks.AZALEA_LEAVES)
                    || state.is(Blocks.FLOWERING_AZALEA_LEAVES)) {
                tint = -1; // texture non teintée
            } else {
                tint = blendedColor(zoom, wx, y, wz, (b, x, z) -> foliageColor(b));
            }

            // Textures de feuilles sombres : teinte atténuée.
            if (tint >= 0) {
                return scaleRgb(tint & 0xFFFFFF, 0.85f);
            }
        }
        MapColor mc = state.getMapColor(level, pos);
        return (mc == MapColor.NONE ? MapColor.STONE : mc).col;
    }

    /** Blocs décoratifs invisibles sur la carte (config serveur hiddenBlocks). */
    private static boolean isMapHidden(BlockState state) {
        return ServerConfig.isHiddenBlock(state);
    }

    private static boolean isGrassTinted(BlockState state) {
        // Les herbes/fougères sont filtrées par isMapHidden : seuls restent
        // les blocs teintés susceptibles d'être la surface peinte.
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SUGAR_CANE);
    }

    private static int grassColor(Biome biome, int wx, int wz) {
        var fx = biome.getSpecialEffects();
        int base = fx.getGrassColorOverride()
                .orElseGet(() -> climateBlend(biome, 0xBFB755, 0x80B497, 0x47CD33));
        // Modificateur vanilla (marais, forêt sombre) — fonctionne côté serveur.
        return fx.getGrassColorModifier().modifyColor(wx, wz, base) & 0xFFFFFF;
    }

    private static int foliageColor(Biome biome) {
        return biome.getSpecialEffects().getFoliageColorOverride()
                .orElseGet(() -> climateBlend(biome, 0xAEA42A, 0x60A17B, 0x1ABF00));
    }

    /** Interpolation triangulaire chaud-sec / froid / chaud-humide des colormaps. */
    private static int climateBlend(Biome biome, int hotDry, int cold, int hotWet) {
        float t = Math.max(0f, Math.min(1f, biome.getBaseTemperature()));
        float d = Math.max(0f, Math.min(1f, biome.getModifiedClimateSettings().downfall()));
        float r = d * t;
        float cw = 1f - t;
        int rr = clamp255(((hotDry >> 16) & 0xFF)
                + cw * (((cold >> 16) & 0xFF) - ((hotDry >> 16) & 0xFF))
                + r * (((hotWet >> 16) & 0xFF) - ((hotDry >> 16) & 0xFF)));
        int gg = clamp255(((hotDry >> 8) & 0xFF)
                + cw * (((cold >> 8) & 0xFF) - ((hotDry >> 8) & 0xFF))
                + r * (((hotWet >> 8) & 0xFF) - ((hotDry >> 8) & 0xFF)));
        int bb = clamp255((hotDry & 0xFF)
                + cw * ((cold & 0xFF) - (hotDry & 0xFF))
                + r * ((hotWet & 0xFF) - (hotDry & 0xFF)));
        return (rr << 16) | (gg << 8) | bb;
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, (int) v));
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
        if (y <= chunk.getMinBuildHeight()) {
            return 0xFF000000;
        }

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
        if (t < 0.33f) {
            rgb = lerpRgb(0x2E8B37, 0xC8B454, t / 0.33f);
        }
        else if (t < 0.66f) {
            rgb = lerpRgb(0xC8B454, 0x8A7355, (t - 0.33f) / 0.33f);
        }
        else {
            rgb = lerpRgb(0x8A7355, 0xF2F2F2, (t - 0.66f) / 0.34f);
        }

        // Courbes de niveau tous les 8 blocs
        if (y % 8 == 0) {
            rgb = scaleRgb(rgb, 0.72f);
        }

        return 0xFF000000 | rgb;
    }

    /** Échantillonneur de couleur dépendant du biome à une position donnée. */
    @FunctionalInterface
    private interface BiomeColorSampler {
        int colorAt(Biome biome, int wx, int wz);
    }

    /**
     * Couleur moyennée sur les blocs voisins (équivalent du lissage de biomes
     * du client vanilla) : adoucit les frontières entre biomes au lieu d'un
     * bord franc. Le rayon vient de la config serveur (biomeBlendRadius,
     * 0 = désactivé, 2 = équivalent vanilla).
     */
    private static int blendedColor(BiomeManager zoom, int wx, int y, int wz, BiomeColorSampler sampler) {
        int radius = ServerConfig.BIOME_BLEND_RADIUS.get();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        if (radius <= 0) {
            return sampler.colorAt(zoom.getBiome(p.set(wx, y, wz)).value(), wx, wz) & 0xFFFFFF;
        }

        int r = 0;
        int g = 0;
        int b = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Biome biome = zoom.getBiome(p.set(wx + dx, y, wz + dz)).value();
                int c = sampler.colorAt(biome, wx + dx, wz + dz) & 0xFFFFFF;
                r += (c >>> 16) & 0xFF;
                g += (c >>> 8) & 0xFF;
                b += c & 0xFF;
            }
        }

        int samples = (2 * radius + 1) * (2 * radius + 1);
        return ((r / samples) << 16) | ((g / samples) << 8) | (b / samples);
    }

    // ------------------------------------------------------------------ BIOME

    private static int renderBiome(ServerLevel level, BiomeManager zoom,
                                   BlockPos.MutableBlockPos pos, int wx, int wz) {
        // Zoom BiomeManager branché sur le chunk (et pas level.getBiome) :
        // sûr depuis un thread de rendu, frontières bloc par bloc comme en jeu.
        Holder<Biome> biome = zoom.getBiome(pos.set(wx, level.getSeaLevel(), wz));
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
                if (above.getFluidState().is(FluidTags.LAVA)) {
                    base = 0xD45A12;
                }

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
        if (neighbor == null) {
            return surfaceY(chunk, wx, cp.getMinBlockZ());
        }

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
