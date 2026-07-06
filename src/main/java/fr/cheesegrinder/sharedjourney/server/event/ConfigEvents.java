package fr.cheesegrinder.sharedjourney.server.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.common.config.ServerConfig;
import fr.cheesegrinder.sharedjourney.server.service.SyncService;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Rechargement à chaud de la config serveur (bus MOD). */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID)
public final class ConfigEvents {

    private ConfigEvents() {}

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == ServerConfig.SPEC) {
            ServerConfig.invalidateCache();
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                server.execute(() -> SyncService.broadcastLayerSettings(server));
            }
        }
    }
}
