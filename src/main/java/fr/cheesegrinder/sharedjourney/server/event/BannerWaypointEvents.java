package fr.cheesegrinder.sharedjourney.server.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.server.service.BannerWaypointService;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.AbstractBannerBlock;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Detects banner waypoints (spec: a banner renamed at an anvil, then
 * placed, becomes a map marker — same behavior as vanilla map banner
 * markers). A banner can only carry a custom name this way: anvils rename
 * the held ITEM, never a block already placed in the world, so placement
 * and breakage are the only two transitions that matter.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID)
public final class BannerWaypointEvents {

    private BannerWaypointEvents() {}

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        if (!(event.getState().getBlock() instanceof AbstractBannerBlock banner)) {
            return;
        }

        if (!(level.getBlockEntity(event.getPos()) instanceof Nameable nameable) || !nameable.hasCustomName()) {
            return;
        }

        BannerWaypointService.onPlace(
                level.dimension(),
                event.getPos(),
                nameable.getCustomName().getString(),
                mapIconColor(banner.getColor()));
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        if (!(event.getState().getBlock() instanceof AbstractBannerBlock)) {
            return;
        }

        BannerWaypointService.onBreak(level.dimension(), event.getPos());
    }

    /**
     * The banner cloth color as vanilla's OWN map icon renders it
     * ({@code textures/map/decorations/<color>_banner.png}), traced pixel
     * by pixel. {@link DyeColor#getTextureDiffuseColor()} is a close but
     * NOT identical shade (it is the leather-dye tint formula, meant for
     * armor/eggs, not banner cloth) — this table is the exact match.
     */
    private static int mapIconColor(DyeColor color) {
        return switch (color) {
            case WHITE -> 0xEEEEEE;
            case ORANGE -> 0xF9801D;
            case MAGENTA -> 0xC74EBD;
            case LIGHT_BLUE -> 0x3AB3DA;
            case YELLOW -> 0xFED83D;
            case LIME -> 0x80C71F;
            case PINK -> 0xF38BAA;
            case GRAY -> 0x474F52;
            case LIGHT_GRAY -> 0x9D9D97;
            case CYAN -> 0x169C9C;
            case PURPLE -> 0x8932B8;
            case BLUE -> 0x3C44AA;
            case BROWN -> 0x835432;
            case GREEN -> 0x5E7C16;
            case RED -> 0xB02E26;
            case BLACK -> 0x1D1D21;
        };
    }
}
