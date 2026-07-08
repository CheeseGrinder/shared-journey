package fr.cheesegrinder.sharedjourney.client.net;

import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;

/** Réception des paquets côté client (thread client via enqueueWork). */
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
