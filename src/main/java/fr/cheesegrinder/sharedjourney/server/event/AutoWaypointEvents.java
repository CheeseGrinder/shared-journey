package fr.cheesegrinder.sharedjourney.server.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.server.service.AutoWaypointService;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Detects the automatic contextual waypoints (spec 06 §3.4):
 * <ul>
 *   <li>bed / respawn anchor: a player's spawn point being set (sleeping in
 *       a bed, charging then using an anchor) — the very event vanilla
 *       fires for it, so the waypoint always matches the real spawn;</li>
 *   <li>nether portal: a player going through one, recorded at BOTH ends
 *       (the origin just before the travel, the arrival just after) so the
 *       least spammy reliable trigger is "a portal that was actually
 *       used", not every flint-and-steel click or every generated frame.</li>
 * </ul>
 * Handlers run last (lowest priority) and ignore cancelled events, so
 * another mod vetoing a spawn change or a travel never leaves a waypoint.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID)
public final class AutoWaypointEvents {

    private AutoWaypointEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSpawnSet(PlayerSetSpawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        AutoWaypointService.onSpawnSet(player, event.getSpawnLevel(), event.getNewSpawn());
    }

    /** Origin end of a portal trip: the player still stands inside the portal. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AutoWaypointService.onPortalTravel(
                    player, "travel to " + event.getDimension().location());
        }
    }

    /** Arrival end of a portal trip: the player was just placed inside the destination portal. */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AutoWaypointService.onPortalTravel(
                    player, "arrival from " + event.getFrom().location());
        }
    }

    /**
     * A player breaks a bed or a respawn anchor: its waypoint disappears at
     * once. Both halves of a bed are covered (breaking one breaks the
     * other without a second event).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        BlockState state = event.getState();
        BlockPos pos = event.getPos();
        if (state.getBlock() instanceof BedBlock) {
            AutoWaypointService.onBlockBroken(level.dimension(), pos);
            AutoWaypointService.onBlockBroken(level.dimension(), pos.relative(BedBlock.getConnectedDirection(state)));
            return;
        }

        if (state.is(Blocks.RESPAWN_ANCHOR)) {
            AutoWaypointService.onBlockBroken(level.dimension(), pos);
        }
    }
}
