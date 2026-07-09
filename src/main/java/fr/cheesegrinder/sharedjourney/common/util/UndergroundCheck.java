package fr.cheesegrinder.sharedjourney.common.util;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * "Underground" detection shared by the client (minimap auto-switch) and the
 * server (CAVE band unlocking by the CaveTracker): the rule must be identical
 * on both sides so that the display follows the unlocking.
 */
public final class UndergroundCheck {

    private UndergroundCheck() {}

    /**
     * Is the player underground? True when a motion-blocking block exists
     * above their eyes in their column. Fluids are ignored: swimming or
     * diving in open sea is not "underground", but a flooded cave or a rift
     * under an overhang is. Leaves are ignored too: walking under a tree is
     * not a cave. Only uses the WORLD_SURFACE heightmap, which is synced to
     * the client (unlike OCEAN_FLOOR, server-only).
     */
    public static boolean isUnderground(Level level, Player player) {
        BlockPos base = player.blockPosition();
        int eyeY = Mth.floor(player.getEyeY());
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, base.getX(), base.getZ());
        if (eyeY >= top) {
            return false;
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = eyeY + 1; y < top; y++) {
            BlockState state = level.getBlockState(pos.set(base.getX(), y, base.getZ()));
            if (!state.getFluidState().isEmpty() || state.is(BlockTags.LEAVES)) {
                continue;
            }

            if (state.blocksMotion()) {
                return true;
            }
        }

        return false;
    }
}
