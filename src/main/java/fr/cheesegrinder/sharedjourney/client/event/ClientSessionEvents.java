package fr.cheesegrinder.sharedjourney.client.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.compat.JourneyMapBridge;
import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.service.DiskCache;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;

import net.minecraft.world.level.Level;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client session lifecycle: local cache open/close + handshake. */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class ClientSessionEvents {

    private ClientSessionEvents() {}

    /** Login: opens the local cache + handshake (spec §5.1). */
    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientMapCache.clear();
        DiskCache.openSession();
        WaypointStore.openSession();
        // Handshake: send the local index summary. The server will only
        // send back missing or newer regions.
        byte[] encoded =
                Payloads.ClientIndexPayload.encodeIndex(DiskCache.index().snapshot());
        PacketDistributor.sendToServer(new Payloads.ClientIndexPayload(encoded));

        // Signal "mapping started" to the bridged JourneyMap plugins
        // (Waystones waits for this event before creating its waypoints).
        JourneyMapBridge.fireMappingEvent(true, event.getPlayer().level().dimension());

        // Visibility preference on the other players' map.
        PacketDistributor.sendToServer(new Payloads.MapVisibilityPayload(ClientConfig.HIDE_FROM_MAP.get()));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Before closing the store: plugins can still clean up.
        var player = event.getPlayer();
        JourneyMapBridge.fireMappingEvent(false, player != null ? player.level().dimension() : Level.OVERWORLD);
        DiskCache.closeSession();
        WaypointStore.closeSession();
        ClientMapCache.clear();
    }
}
