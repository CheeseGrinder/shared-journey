package fr.cheesegrinder.sharedjourney.client.net;

import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
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

    public static void handleMapInfoChunk(Payloads.MapInfoChunkPayload payload) {
        ClientMapCache.putHoverChunk(
                payload.chunkX(),
                payload.chunkZ(),
                new ClientMapCache.HoverChunk(
                        payload.heights(),
                        payload.blockIdx(),
                        payload.blockPalette(),
                        payload.biomeIdx(),
                        payload.biomePalette()));
    }
}
