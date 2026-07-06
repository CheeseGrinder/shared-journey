package fr.cheesegrinder.sharedjourney.api.event;

import fr.cheesegrinder.sharedjourney.api.ChunkLayerRenderer;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posté sur le bus MOD au démarrage serveur pour permettre à d'autres mods
 * d'enregistrer des couches custom (en plus de DAY/NIGHT/TOPO/BIOME/CAVE).
 * Les couches custom sont stockées/synchronisées comme les autres, sous
 * l'identifiant fourni ("monmod_machin").
 */
public class LayerRegisterEvent extends Event implements IModBusEvent {

    private final Map<String, ChunkLayerRenderer> customLayers = new LinkedHashMap<>();

    public void register(String layerId, ChunkLayerRenderer renderer) {
        if (!layerId.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Identifiant de couche invalide: " + layerId);
        }
        customLayers.put(layerId, renderer);
    }

    public Map<String, ChunkLayerRenderer> getCustomLayers() {
        return customLayers;
    }
}
