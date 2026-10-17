package fr.cheesegrinder.sharedjourney.common.util;

import fr.cheesegrinder.sharedjourney.common.config.EngineServerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Shared "floor" rule of a CAVE map column: the first solid block under the
 * first air pocket found scanning a 16-block band (band = floorDiv(y, 16))
 * from its top. The CAVE renderer, the hover sidecar of the band and the
 * client's local hover all go through it, so they agree on the floor block
 * and its height — same role as {@link SurfaceHeight} for the surface.
 *
 * <p>"Open" blocks are air, fluids and the blocks hidden by the
 * {@code hiddenBlocks} server config (grass, torches...): see-through for
 * the map, so the floor is the solid block below them.
 */
public final class CaveFloor {

    /** The band holds no air pocket in this column: solid rock from top to bottom. */
    public static final int SOLID = Integer.MIN_VALUE;

    /** Open (air, fluid, hidden block) down to the bottom of the band: no floor in it. */
    public static final int OPEN = Integer.MIN_VALUE + 1;

    private CaveFloor() {}

    /** Highest Y of the band, clamped to the world. */
    public static int bandTop(ChunkAccess chunk, int band) {
        return Math.min(band * 16 + 15, chunk.getMaxBuildHeight() - 1);
    }

    /** Lowest Y of the band, clamped to the world. */
    public static int bandBottom(ChunkAccess chunk, int band) {
        return Math.max(band * 16, chunk.getMinBuildHeight());
    }

    /** See-through for the map: air, fluid, or a hidden vegetation/decoration block. */
    public static boolean isOpen(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty() || EngineServerConfig.isHiddenBlock(state);
    }

    /**
     * Y of the floor block of a column within the band, or {@link #SOLID} /
     * {@link #OPEN} when it has none.
     *
     * @param pos reusable cursor, left on the floor block; on {@link #SOLID}
     *     it is left on the top of the band (the block a hover would name),
     *     on {@link #OPEN} on its bottom
     */
    public static int floorY(ChunkAccess chunk, int wx, int wz, int band, BlockPos.MutableBlockPos pos) {
        int yTop = bandTop(chunk, band);
        int yBottom = bandBottom(chunk, band);
        boolean inPocket = false;
        for (int y = yTop; y >= yBottom; y--) {
            BlockState state = chunk.getBlockState(pos.set(wx, y, wz));
            if (isOpen(state)) {
                inPocket = true;
            } else if (inPocket) {
                return y;
            }
        }

        if (inPocket) {
            pos.set(wx, yBottom, wz);
            return OPEN;
        }

        pos.set(wx, yTop, wz);
        return SOLID;
    }
}
