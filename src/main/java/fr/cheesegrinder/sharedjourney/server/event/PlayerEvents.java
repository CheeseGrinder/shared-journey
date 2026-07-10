package fr.cheesegrinder.sharedjourney.server.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.server.service.RegenService;
import fr.cheesegrinder.sharedjourney.server.service.SyncService;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Player events: per-player synchronization session lifecycle. */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID)
public final class PlayerEvents {

    private PlayerEvents() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            SyncService.onPlayerJoin(sp);
            SyncService.sendHiddenPlayers(sp);
            RegenService.sendStateTo(sp);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            SyncService.onPlayerLeave(sp);
            SyncService.clearHiddenPlayer(sp);
        }
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            SyncService.sendLayerSettings(sp);
        }
    }
}
