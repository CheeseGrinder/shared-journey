package fr.cheesegrinder.sharedjourney.common.util;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Détection "sous terre" partagée client (bascule auto de la minimap) et
 * serveur (déverrouillage des bandes CAVE par le CaveTracker) : la règle doit
 * être identique des deux côtés pour que l'affichage suive le déverrouillage.
 */
public final class UndergroundCheck {

    private UndergroundCheck() {}

    /**
     * Le joueur est-il sous terre ? Vrai s'il existe un bloc solide (qui
     * bloque le mouvement) au-dessus de ses yeux dans sa colonne. Les fluides
     * sont ignorés : nager ou plonger en mer ouverte n'est pas "sous terre",
     * mais une grotte noyée ou une faille sous un surplomb l'est. Les
     * feuilles sont ignorées aussi : marcher sous un arbre n'est pas une
     * grotte. N'utilise que la heightmap WORLD_SURFACE, synchronisée au
     * client (contrairement à OCEAN_FLOOR, serveur uniquement).
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
