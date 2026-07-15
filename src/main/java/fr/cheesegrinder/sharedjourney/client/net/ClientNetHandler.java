package fr.cheesegrinder.sharedjourney.client.net;

import fr.cheesegrinder.sharedjourney.client.compat.CreateTrainMapBridge;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Client-side packet reception (client thread via enqueueWork). */
public final class ClientNetHandler {

    private ClientNetHandler() {}

    public static void handleLayerSettings(Payloads.LayerSettingsPayload payload) {
        ClientMapCache.layersByDim = payload.layersByDim();
        ClientMapCache.caveBands = payload.caveBands();
        ClientMapCache.radarMaxRadius = payload.radarMaxRadius();
        ClientMapCache.deathWaypointsEnabled = payload.deathWaypointsEnabled();
        ClientMapCache.serverManagesWaypoints = payload.serverManagesWaypoints();
    }

    public static void handleRegionData(Payloads.RegionDataPayload payload) {
        ClientMapCache.acceptFragment(
                payload.key(), payload.version(), payload.part(), payload.totalParts(), payload.data());
    }

    public static void handleHiddenPlayers(Payloads.HiddenPlayersPayload payload) {
        ClientMapCache.hiddenPlayers = Set.copyOf(payload.hidden());
    }

    public static void handlePlayerPositions(Payloads.PlayerPositionsPayload payload) {
        Map<UUID, Payloads.PlayerPositionsPayload.PlayerPos> positions = new HashMap<>();
        for (Payloads.PlayerPositionsPayload.PlayerPos pos : payload.players()) {
            positions.put(pos.id(), pos);
        }
        ClientMapCache.playerPositions = Map.copyOf(positions);
    }

    public static void handleRegenState(Payloads.RegenStatePayload payload) {
        ClientMapCache.regenActive = payload.active();
        ClientMapCache.regenDoneMasks.clear();
    }

    public static void handleRegenChunks(Payloads.RegenChunksPayload payload) {
        ClientMapCache.regenDoneMasks.put(
                new ClientMapCache.RegionPos(payload.dimension(), payload.rx(), payload.rz()), payload.mask());
    }

    public static void handleTrainPath(Payloads.TrainPathPayload payload) {
        CreateTrainMapBridge.acceptPath(payload.trainId(), payload.xs(), payload.zs());
    }

    public static void handlePublicWaypoint(Payloads.PublicWaypointPayload payload) {
        WaypointStore.acceptPublicUpsert(payload);
    }

    public static void handlePublicWaypointRemove(Payloads.PublicWaypointRemovePayload payload) {
        WaypointStore.acceptPublicRemove(payload.id());
    }

    public static void handlePlayerWaypoint(Payloads.PlayerWaypointPayload payload) {
        WaypointStore.acceptPlayerUpsert(payload);
    }

    public static void handlePlayerWaypointRemove(Payloads.PlayerWaypointRemovePayload payload) {
        WaypointStore.acceptPlayerRemove(payload.id());
    }

    public static void handleBannerWaypoint(Payloads.BannerWaypointPayload payload) {
        WaypointStore.acceptBannerUpsert(payload);
    }

    public static void handleBannerWaypointRemove(Payloads.BannerWaypointRemovePayload payload) {
        WaypointStore.acceptBannerRemove(payload.id());
    }

    public static void handleOpsConfig(Payloads.OpsConfigPayload payload) {
        ClientMapCache.opsConfig = payload;
    }
}
