package fr.cheesegrinder.sharedjourney.client.net;

import fr.cheesegrinder.sharedjourney.client.compat.CreateTrainMapBridge;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.network.OpsConfigPayloads;
import fr.cheesegrinder.sharedjourney.common.network.PlayerVisibilityPayloads;
import fr.cheesegrinder.sharedjourney.common.network.RegenPayloads;
import fr.cheesegrinder.sharedjourney.common.network.RegionSyncPayloads;
import fr.cheesegrinder.sharedjourney.common.network.TrainPathPayloads;
import fr.cheesegrinder.sharedjourney.common.network.WaypointPayloads;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Client-side packet reception (client thread via enqueueWork). */
public final class ClientNetHandler {

    private ClientNetHandler() {}

    public static void handleLayerSettings(RegionSyncPayloads.LayerSettingsPayload payload) {
        ClientMapCache.layersByDim = payload.layersByDim();
        ClientMapCache.caveBands = payload.caveBands();
        ClientMapCache.radarMaxRadius = payload.radarMaxRadius();
        ClientMapCache.deathWaypointsEnabled = payload.deathWaypointsEnabled();
        ClientMapCache.serverManagesWaypoints = payload.serverManagesWaypoints();
    }

    public static void handleRegionData(RegionSyncPayloads.RegionDataPayload payload) {
        ClientMapCache.acceptFragment(
                payload.key(), payload.version(), payload.part(), payload.totalParts(), payload.data());
    }

    public static void handleHiddenPlayers(PlayerVisibilityPayloads.HiddenPlayersPayload payload) {
        ClientMapCache.hiddenPlayers = Set.copyOf(payload.hidden());
    }

    public static void handlePlayerPositions(PlayerVisibilityPayloads.PlayerPositionsPayload payload) {
        Map<UUID, PlayerVisibilityPayloads.PlayerPositionsPayload.PlayerPos> positions = new HashMap<>();
        for (PlayerVisibilityPayloads.PlayerPositionsPayload.PlayerPos pos : payload.players()) {
            positions.put(pos.id(), pos);
        }
        ClientMapCache.playerPositions = Map.copyOf(positions);
    }

    public static void handleRegenState(RegenPayloads.RegenStatePayload payload) {
        ClientMapCache.regenActive = payload.active();
        ClientMapCache.regenDoneMasks.clear();
    }

    public static void handleRegenChunks(RegenPayloads.RegenChunksPayload payload) {
        ClientMapCache.regenDoneMasks.put(
                new ClientMapCache.RegionPos(payload.dimension(), payload.rx(), payload.rz()), payload.mask());
    }

    public static void handleRegenProgress(RegenPayloads.RegenProgressPayload payload) {
        ClientMapCache.regenProgress = payload.active() ? payload : null;
    }

    public static void handleTrainPath(TrainPathPayloads.TrainPathPayload payload) {
        CreateTrainMapBridge.acceptPath(payload.trainId(), payload.xs(), payload.zs());
    }

    public static void handlePublicWaypoint(WaypointPayloads.PublicWaypointPayload payload) {
        WaypointStore.acceptPublicUpsert(payload);
    }

    public static void handlePublicWaypointRemove(WaypointPayloads.PublicWaypointRemovePayload payload) {
        WaypointStore.acceptPublicRemove(payload.id());
    }

    public static void handlePlayerWaypoint(WaypointPayloads.PlayerWaypointPayload payload) {
        WaypointStore.acceptPlayerUpsert(payload);
    }

    public static void handlePlayerWaypointRemove(WaypointPayloads.PlayerWaypointRemovePayload payload) {
        WaypointStore.acceptPlayerRemove(payload.id());
    }

    public static void handleBannerWaypoint(WaypointPayloads.BannerWaypointPayload payload) {
        WaypointStore.acceptBannerUpsert(payload);
    }

    public static void handleBannerWaypointRemove(WaypointPayloads.BannerWaypointRemovePayload payload) {
        WaypointStore.acceptBannerRemove(payload.id());
    }

    public static void handleOpsConfig(OpsConfigPayloads.OpsConfigPayload payload) {
        ClientMapCache.opsConfig = payload;
    }
}
