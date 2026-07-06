package fr.cheesegrinder.sharedjourney.server.render;

import fr.cheesegrinder.sharedjourney.common.config.EngineServerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * Couches DAY et NIGHT : bloc de surface coloré par MapColor, teinté par le
 * biome quand le bloc l'est en jeu (herbe, feuillage, eau), ombrage de pente,
 * et assombrissement nocturne éclairé par la lumière de bloc.
 */
final class SurfaceRenderer {

    private SurfaceRenderer() {}

    static int render(RenderContext ctx, int wx, int wz, boolean night) {
        int y = ctx.surfaceY(wx, wz);
        if (y <= ctx.chunk.getMinBuildHeight()) {
            return 0xFF000000;
        }

        BlockPos.MutableBlockPos pos = ctx.pos;
        pos.set(wx, y, wz);
        BlockState state = ctx.chunk.getBlockState(pos);
        // La végétation décorative (fleurs, herbes, pousses...) ne doit pas
        // apparaître sur la carte : on peint le bloc situé dessous.
        while (y > ctx.chunk.getMinBuildHeight()
                && isMapHidden(state)
                && state.getFluidState().isEmpty()) {
            y--;
            pos.set(wx, y, wz);
            state = ctx.chunk.getBlockState(pos);
        }
        // Eau : couleur du biome (lissée entre biomes voisins) assombrie selon
        // la profondeur.
        if (!state.getFluidState().isEmpty()) {
            int depth = 0;
            while (y - depth > ctx.chunk.getMinBuildHeight()
                    && !ctx.chunk
                            .getBlockState(pos.set(wx, y - depth, wz))
                            .getFluidState()
                            .isEmpty()
                    && depth < 24) {
                depth++;
            }
            float dark = Math.max(0.35f, 1.0f - depth * 0.035f);
            int base = BiomeTints.blended(ctx.zoom, wx, y, wz, (b, x, z) -> b.getWaterColor());
            int rgb = ColorUtil.scaleRgb(base, dark);
            return night ? applyNight(ctx, pos.set(wx, y + 1, wz), rgb) : (0xFF000000 | rgb);
        }

        int base = tintedBaseColor(ctx, state, wx, y, wz);

        // Ombrage de pente : compare la hauteur avec le voisin nord (comme un relief).
        int yNorth = ctx.surfaceYAt(wx, wz - 1);
        float shade = y > yNorth ? 1.10f : (y < yNorth ? 0.82f : 1.0f);
        int rgb = ColorUtil.scaleRgb(base, shade);

        if (night) {
            return applyNight(ctx, pos.set(wx, y + 1, wz), rgb);
        }
        return 0xFF000000 | rgb;
    }

    /**
     * Couleur de base d'un bloc de surface, teintée par le biome quand le bloc
     * l'est en jeu (herbe, feuillage) — voir BiomeTints pour l'approximation
     * des colormaps côté serveur.
     */
    private static int tintedBaseColor(RenderContext ctx, BlockState state, int wx, int y, int wz) {
        if (isGrassTinted(state)) {
            return BiomeTints.blended(ctx.zoom, wx, y, wz, BiomeTints::grassColor);
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
            } else if (state.is(Blocks.CHERRY_LEAVES)
                    || state.is(Blocks.AZALEA_LEAVES)
                    || state.is(Blocks.FLOWERING_AZALEA_LEAVES)) {
                tint = -1; // texture non teintée
            } else {
                tint = BiomeTints.blended(ctx.zoom, wx, y, wz, (b, x, z) -> BiomeTints.foliageColor(b));
            }

            // Textures de feuilles sombres : teinte atténuée.
            if (tint >= 0) {
                return ColorUtil.scaleRgb(tint & 0xFFFFFF, 0.85f);
            }
        }
        MapColor mc = state.getMapColor(ctx.level, ctx.pos);
        return (mc == MapColor.NONE ? MapColor.STONE : mc).col;
    }

    /** Blocs décoratifs invisibles sur la carte (config serveur hiddenBlocks). */
    private static boolean isMapHidden(BlockState state) {
        return EngineServerConfig.isHiddenBlock(state);
    }

    private static boolean isGrassTinted(BlockState state) {
        // Les herbes/fougères sont filtrées par isMapHidden : seuls restent
        // les blocs teintés susceptibles d'être la surface peinte.
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SUGAR_CANE);
    }

    /** Assombrit selon la lumière de bloc (torches, lave...) au-dessus de la surface. */
    private static int applyNight(RenderContext ctx, BlockPos pos, int rgb) {
        int light = ctx.level.getBrightness(LightLayer.BLOCK, pos);
        float f = 0.18f + 0.82f * (light / 15.0f);
        // Teinte bleutée pour la nuit
        int r = (int) (((rgb >> 16) & 0xFF) * f * 0.85f);
        int g = (int) (((rgb >> 8) & 0xFF) * f * 0.9f);
        int b = (int) ((rgb & 0xFF) * Math.min(1f, f * 1.05f + 0.05f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
