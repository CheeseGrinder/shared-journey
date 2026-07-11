package fr.cheesegrinder.sharedjourney.client.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.config.WaypointClientConfig;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Automatic death waypoints: when the death screen opens, a waypoint is
 * created at the player's position in the reserved
 * {@link Waypoint#GROUP_DEATHS} group. Client-side detection (the death
 * screen) because deaths are not otherwise observable client-side.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class DeathWaypointEvents {

    private static final int DEATH_COLOR = 0xC03030;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** Last created death position: the death screen can reopen (resize...). */
    private static BlockPos lastDeathPos;

    private DeathWaypointEvents() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof DeathScreen)) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !WaypointClientConfig.DEATH_WAYPOINTS.get() || !ClientMapCache.deathWaypointsEnabled) {
            return;
        }

        BlockPos pos = player.blockPosition();
        if (pos.equals(lastDeathPos)) {
            return;
        }

        lastDeathPos = pos;
        ResourceLocation dim = player.level().dimension().location();
        String name = "Death " + LocalTime.now().format(TIME_FORMAT);
        Waypoint wp = Waypoint.create(name, dim, pos, DEATH_COLOR, Waypoint.SOURCE_USER)
                .withGroup(Waypoint.GROUP_DEATHS);
        WaypointStore.add(wp);
    }
}
