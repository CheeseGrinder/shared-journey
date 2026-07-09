package fr.cheesegrinder.sharedjourney;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.common.config.CommonConfig;
import fr.cheesegrinder.sharedjourney.common.config.ServerConfig;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;
import fr.cheesegrinder.sharedjourney.server.service.SyncService;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Common entry point (client + dedicated server).
 * Assembles the parts: registers configs and payloads, and wires the SERVER
 * network handlers (CLIENT handlers are wired by SharedJourneyClient, on the
 * client only).
 */
@Mod(SharedJourney.MODID)
public class SharedJourney {

    public static final String MODID = SharedJourneyConstants.MOD_ID;
    public static final Logger LOGGER = LogUtils.getLogger();

    public SharedJourney(IEventBus modEventBus, ModContainer modContainer) {
        // Configs: the hierarchy required by spec §8 is handled by NeoForge
        // (global defaultconfigs/, then world/serverconfig/ overrides).
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.COMMON, CommonConfig.SPEC);

        // Payloads (spec §5)
        modEventBus.addListener(Payloads::register);

        // Server-side handler wiring (classes present on both sides).
        Payloads.Hooks.serverRegionRequest = SyncService::handleRegionRequest;
        Payloads.Hooks.serverClientIndex = SyncService::handleClientIndex;
        Payloads.Hooks.serverMapInfoRequest = SyncService::handleMapInfoRequest;
        Payloads.Hooks.serverMapVisibility = SyncService::handleMapVisibility;

        LOGGER.info("SharedJourney initialized");
    }
}
