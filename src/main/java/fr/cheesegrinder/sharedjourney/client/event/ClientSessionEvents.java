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

        // Signale "mapping démarré" aux plugins JourneyMap bridgés (Waystones
        // attend cet événement avant de créer ses waypoints).
        JourneyMapBridge.fireMappingEvent(true, event.getPlayer().level().dimension());

        // Préférence de visibilité sur la carte des autres joueurs.
        PacketDistributor.sendToServer(new Payloads.MapVisibilityPayload(ClientConfig.HIDE_FROM_MAP.get()));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Avant la fermeture du store : les plugins peuvent encore nettoyer.
        var player = event.getPlayer();
        JourneyMapBridge.fireMappingEvent(false, player != null ? player.level().dimension() : Level.OVERWORLD);
        DiskCache.closeSession();
        WaypointStore.closeSession();
        ClientMapCache.clear();
    }
}
