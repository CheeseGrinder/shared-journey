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
 * DAY and NIGHT layers: surface block colored by MapColor, biome-tinted when
 * the block is tinted in-game (grass, foliage, water), slope shading, and
 * night darkening lit by block light.
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
        // Decorative vegetation (flowers, grasses, saplings...) must not
        // show on the map: paint the block below instead.
        while (y > ctx.chunk.getMinBuildHeight()
                && isMapHidden(state)
                && state.getFluidState().isEmpty()) {
            y--;
            pos.set(wx, y, wz);
            state = ctx.chunk.getBlockState(pos);
        }
        // Water: biome color (blended across neighboring biomes) darkened
        // with depth.
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

        // Slope shading: compare the height with the north neighbor (relief-like).
        int yNorth = ctx.surfaceYAt(wx, wz - 1);
        float shade = y > yNorth ? 1.10f : (y < yNorth ? 0.82f : 1.0f);
        int rgb = ColorUtil.scaleRgb(base, shade);

        if (night) {
            return applyNight(ctx, pos.set(wx, y + 1, wz), rgb);
        }
        return 0xFF000000 | rgb;
    }

    /**
     * Base color of a surface block, biome-tinted when the block is tinted
     * in-game (grass, foliage) — see BiomeTints for the server-side colormap
     * approximation.
     */
    private static int tintedBaseColor(RenderContext ctx, BlockState state, int wx, int y, int wz) {
        if (isGrassTinted(state)) {
            return BiomeTints.blended(ctx.zoom, wx, y, wz, BiomeTints::grassColor);
        }
        if (state.is(BlockTags.LEAVES) || state.is(Blocks.VINE)) {
            // Fixed-tint species (like vanilla), otherwise biome foliage.
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
                tint = -1; // untinted texture
            } else {
                tint = BiomeTints.blended(ctx.zoom, wx, y, wz, (b, x, z) -> BiomeTints.foliageColor(b));
            }

            // Dark leaf textures: dampened tint.
            if (tint >= 0) {
                return ColorUtil.scaleRgb(tint & 0xFFFFFF, 0.85f);
            }
        }
        MapColor mc = state.getMapColor(ctx.level, ctx.pos);
        return (mc == MapColor.NONE ? MapColor.STONE : mc).col;
    }

    /** Decorative blocks invisible on the map (hiddenBlocks server config). */
    private static boolean isMapHidden(BlockState state) {
        return EngineServerConfig.isHiddenBlock(state);
    }

    private static boolean isGrassTinted(BlockState state) {
        // Grasses/ferns are filtered out by isMapHidden: only tinted blocks
        // that can actually be the painted surface remain.
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SUGAR_CANE);
    }

    /** Darkens according to block light (torches, lava...) above the surface. */
    private static int applyNight(RenderContext ctx, BlockPos pos, int rgb) {
        int light = ctx.level.getBrightness(LightLayer.BLOCK, pos);
        float f = 0.18f + 0.82f * (light / 15.0f);
        // Bluish night tint
        int r = (int) (((rgb >> 16) & 0xFF) * f * 0.85f);
        int g = (int) (((rgb >> 8) & 0xFF) * f * 0.9f);
        int b = (int) ((rgb & 0xFF) * Math.min(1f, f * 1.05f + 0.05f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
