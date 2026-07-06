package fr.cheesegrinder.sharedjourney.client.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.service.DiskCache;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Cycle de session client : ouverture/fermeture du cache local + handshake. */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class ClientSessionEvents {

    private ClientSessionEvents() {}

    /** Connexion : ouvre le cache local + handshake (spec §5.1). */
    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientMapCache.clear();
        DiskCache.openSession();
        WaypointStore.openSession();
        // Handshake : envoi du résumé de l'index local. Le serveur ne
        // renverra que les régions manquantes ou plus récentes.
        byte[] encoded =
                Payloads.ClientIndexPayload.encodeIndex(DiskCache.index().snapshot());
        PacketDistributor.sendToServer(new Payloads.ClientIndexPayload(encoded));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        DiskCache.closeSession();
        WaypointStore.closeSession();
        ClientMapCache.clear();
    }
}
