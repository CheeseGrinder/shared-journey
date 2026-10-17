package fr.cheesegrinder.sharedjourney.server.render;

import fr.cheesegrinder.sharedjourney.common.util.CaveFloor;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * CAVE layer: renders a vertical band of 16 blocks (band = floorDiv(y,16)).
 * For each column, {@link CaveFloor} finds the floor of the first air pocket
 * scanning from the top of the band: that is what gets colored, darkened by
 * its depth within the band. Solid column = black.
 */
final class CaveRenderer {

    private CaveRenderer() {}

    static int render(RenderContext ctx, int wx, int wz, int band) {
        int yTop = CaveFloor.bandTop(ctx.chunk, band);
        int y = CaveFloor.floorY(ctx.chunk, wx, wz, band, ctx.pos);
        if (y == CaveFloor.OPEN) {
            // Air pocket open down to the bottom of the band: dark gray
            return 0xFF000000 | 0x1A1A1A;
        }

        if (y == CaveFloor.SOLID) {
            return 0xFF000000; // solid rock
        }

        // ctx.pos is on the floor block
        BlockState floor = ctx.chunk.getBlockState(ctx.pos);
        int base = BlockPalette.color(floor, ctx.level, ctx.pos);
        // Lava shown in orange
        BlockState above = ctx.chunk.getBlockState(ctx.pos.set(wx, y + 1, wz));
        if (above.getFluidState().is(FluidTags.LAVA)) {
            base = 0xD45A12;
        }

        float depth = (yTop - (y + 1)) / 16.0f;
        return 0xFF000000 | ColorUtil.scaleRgb(base, 1.0f - depth * 0.45f);
    }
}
