package fr.cheesegrinder.sharedjourney.common.network;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Network packets (spec §5), split by functional group:
 * {@link RegionSyncPayloads} (layers, region data, handshake, requests),
 * {@link PlayerVisibilityPayloads} (hide-me preference, hidden list,
 * positions), {@link RegenPayloads} (regen state/masks/progress),
 * {@link TrainPathPayloads} (Create bridge), {@link WaypointPayloads}
 * (public + player + banner) and {@link OpsConfigPayloads} (ops config
 * screen). This class is the thin orchestrator: the {@link Hooks} handler
 * indirection, the shared payload id factory and the registration entry
 * point delegating to each group.
 *
 * <p>The common package knows neither the client nor the server: handlers
 * are injected by the entry points ({@code SharedJourney} and
 * {@code SharedJourneyClient}) through the Hooks.* fields, which avoids
 * any circular dependency between packages. Deliberate design choice — do
 * not refactor it away.
 */
public final class Payloads {

    private Payloads() {}

    /** Handler indirection, wired by the entry points at startup. */
    public static final class Hooks {
        public static Consumer<RegionSyncPayloads.LayerSettingsPayload> clientLayerSettings = p -> {};
        public static Consumer<RegionSyncPayloads.RegionDataPayload> clientRegionData = p -> {};
        public static Consumer<PlayerVisibilityPayloads.HiddenPlayersPayload> clientHiddenPlayers = p -> {};
        public static Consumer<PlayerVisibilityPayloads.PlayerPositionsPayload> clientPlayerPositions = p -> {};
        public static Consumer<RegenPayloads.RegenStatePayload> clientRegenState = p -> {};
        public static Consumer<RegenPayloads.RegenChunksPayload> clientRegenChunks = p -> {};
        public static Consumer<RegenPayloads.RegenProgressPayload> clientRegenProgress = p -> {};
        public static Consumer<TrainPathPayloads.TrainPathPayload> clientTrainPath = p -> {};
        public static Consumer<WaypointPayloads.PublicWaypointPayload> clientPublicWaypoint = p -> {};
        public static Consumer<WaypointPayloads.PublicWaypointRemovePayload> clientPublicWaypointRemove = p -> {};
        public static Consumer<WaypointPayloads.PlayerWaypointPayload> clientPlayerWaypoint = p -> {};
        public static Consumer<WaypointPayloads.PlayerWaypointRemovePayload> clientPlayerWaypointRemove = p -> {};
        public static Consumer<WaypointPayloads.BannerWaypointPayload> clientBannerWaypoint = p -> {};
        public static Consumer<WaypointPayloads.BannerWaypointRemovePayload> clientBannerWaypointRemove = p -> {};
        public static BiConsumer<Player, TrainPathPayloads.TrainPathRequestPayload> serverTrainPathRequest =
                (pl, p) -> {};
        public static BiConsumer<Player, WaypointPayloads.PublicWaypointPayload> serverPublicWaypoint = (pl, p) -> {};
        public static BiConsumer<Player, WaypointPayloads.PublicWaypointRemovePayload> serverPublicWaypointRemove =
                (pl, p) -> {};
        public static BiConsumer<Player, WaypointPayloads.PlayerWaypointPayload> serverPlayerWaypoint = (pl, p) -> {};
        public static BiConsumer<Player, WaypointPayloads.PlayerWaypointRemovePayload> serverPlayerWaypointRemove =
                (pl, p) -> {};
        public static BiConsumer<Player, RegionSyncPayloads.RegionRequestPayload> serverRegionRequest = (pl, p) -> {};
        public static BiConsumer<Player, RegionSyncPayloads.ClientIndexPayload> serverClientIndex = (pl, p) -> {};
        public static BiConsumer<Player, PlayerVisibilityPayloads.MapVisibilityPayload> serverMapVisibility =
                (pl, p) -> {};
        public static Consumer<OpsConfigPayloads.OpsConfigPayload> clientOpsConfig = p -> {};
        public static BiConsumer<Player, OpsConfigPayloads.OpsConfigRequestPayload> serverOpsConfigRequest =
                (pl, p) -> {};
        public static BiConsumer<Player, OpsConfigPayloads.OpsConfigPayload> serverOpsConfigUpdate = (pl, p) -> {};
    }

    /** Payload id in the mod namespace; shared by every payload group. */
    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SharedJourneyConstants.MOD_ID, path);
    }

    /**
     * Registers every payload group on the versioned registrar. The
     * version guards client/server compatibility at connection time:
     * reset to "1" for the initial release (pre-release bumps discarded),
     * bump it on EVERY wire format change once the mod is published.
     */
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar =
                event.registrar(SharedJourneyConstants.MOD_ID).versioned("1");

        RegionSyncPayloads.register(registrar);
        PlayerVisibilityPayloads.register(registrar);
        RegenPayloads.register(registrar);
        TrainPathPayloads.register(registrar);
        WaypointPayloads.register(registrar);
        OpsConfigPayloads.register(registrar);
    }
}
