package fr.cheesegrinder.sharedjourney;

import com.mojang.logging.LogUtils;
import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;
import fr.cheesegrinder.sharedjourney.common.config.CommonConfig;
import fr.cheesegrinder.sharedjourney.common.config.ServerConfig;
import fr.cheesegrinder.sharedjourney.server.service.SyncService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Point d'entrée commun (client + serveur dédié).
 * Assemble les modules : enregistre les configs et les payloads, et câble les
 * handlers réseau SERVEUR (les handlers CLIENT sont câblés par
 * SharedJourneyClient, uniquement sur le client).
 */
@Mod(SharedJourney.MODID)
public class SharedJourney {

    public static final String MODID = SharedJourneyConstants.MOD_ID;
    public static final Logger LOGGER = LogUtils.getLogger();

    public SharedJourney(IEventBus modEventBus, ModContainer modContainer) {
        // Configs : la hiérarchie de la spec §8 est assurée par NeoForge
        // (defaultconfigs/ global, puis world/serverconfig/ écrase).
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.COMMON, CommonConfig.SPEC);

        // Payloads (spec §5)
        modEventBus.addListener(Payloads::register);

        // Câblage des handlers côté serveur (classes présentes des deux côtés).
        Payloads.Hooks.serverRegionRequest = SyncService::handleRegionRequest;
        Payloads.Hooks.serverClientIndex = SyncService::handleClientIndex;
        Payloads.Hooks.serverMapInfoRequest = SyncService::handleMapInfoRequest;

        LOGGER.info("SharedJourney initialisé");
    }
}
