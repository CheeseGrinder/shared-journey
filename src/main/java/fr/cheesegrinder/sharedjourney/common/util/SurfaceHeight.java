package fr.cheesegrinder.sharedjourney.common.util;

import fr.cheesegrinder.sharedjourney.common.config.EngineServerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Shared "ground height" rule of a map column: the highest block that is
 * actually painted, i.e. neither air nor hidden by the {@code hiddenBlocks}
 * server config (short/tall grass, flowers...). Used by the renderers, the
 * hover sidecar and the client's local hover so that all of them agree on
 * the surface block and its height.
 */
public final class SurfaceHeight {

    private SurfaceHeight() {}

    /**
     * Y of the ground block of a column: starts from the WORLD_SURFACE
     * heightmap and walks down past air and hidden blocks. Hidden blocks
     * holding a fluid (waterlogged plants) stop the walk: the water is the
     * surface. Air is skipped too, so a hidden block hanging above a gap
     * (propagule under a mangrove...) never paints the air below it.
     *
     * @param pos reusable cursor, left on the returned position
     */
    public static int groundY(ChunkAccess chunk, int wx, int wz, BlockPos.MutableBlockPos pos) {
        int minY = chunk.getMinBuildHeight();
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
        BlockState state = chunk.getBlockState(pos.set(wx, y, wz));
        while (y > minY && isSkipped(state)) {
            y--;
            state = chunk.getBlockState(pos.set(wx, y, wz));
        }
        return y;
    }

    private static boolean isSkipped(BlockState state) {
        return state.isAir()
                || (EngineServerConfig.isHiddenBlock(state)
                        && state.getFluidState().isEmpty());
    }
}
