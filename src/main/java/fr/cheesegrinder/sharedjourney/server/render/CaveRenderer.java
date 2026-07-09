package fr.cheesegrinder.sharedjourney.server.render;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * CAVE layer: renders a vertical band of 16 blocks (band = floorDiv(y,16)).
 * For each column, scan from the top of the band for the first air pocket,
 * then for that pocket's floor: that is what gets colored, darkened by its
 * depth within the band. Solid column = black.
 */
final class CaveRenderer {

    private CaveRenderer() {}

    static int render(RenderContext ctx, int wx, int wz, int band) {
        int yTop = band * 16 + 15;
        int yBottom = band * 16;
        yTop = Math.min(yTop, ctx.chunk.getMaxBuildHeight() - 1);
        yBottom = Math.max(yBottom, ctx.chunk.getMinBuildHeight());

        int airStart = -1;
        for (int y = yTop; y >= yBottom; y--) {
            BlockState s = ctx.chunk.getBlockState(ctx.pos.set(wx, y, wz));
            boolean open = s.isAir() || !s.getFluidState().isEmpty();
            if (open && airStart == -1) {
                airStart = y;
            } else if (!open && airStart != -1) {
                // s is the floor of the air pocket
                MapColor mc = s.getMapColor(ctx.level, ctx.pos);
                int base = (mc == MapColor.NONE ? MapColor.STONE : mc).col;
                // Lava shown in orange
                BlockState above = ctx.chunk.getBlockState(ctx.pos.set(wx, y + 1, wz));
                if (above.getFluidState().is(FluidTags.LAVA)) {
                    base = 0xD45A12;
                }

                float depth = (yTop - (y + 1)) / 16.0f;
                return 0xFF000000 | ColorUtil.scaleRgb(base, 1.0f - depth * 0.45f);
            }
        }
        if (airStart != -1) {
            // Air pocket open down to the bottom of the band: dark gray
            return 0xFF000000 | 0x1A1A1A;
        }
        return 0xFF000000; // solid rock
    }
}
