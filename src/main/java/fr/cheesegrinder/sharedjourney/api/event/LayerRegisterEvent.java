package fr.cheesegrinder.sharedjourney.api.event;

import fr.cheesegrinder.sharedjourney.api.ChunkLayerRenderer;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posted on the MOD bus at server startup so that other mods can register
 * custom layers (in addition to DAY/NIGHT/TOPO/BIOME/CAVE). Custom layers are
 * stored/synchronized like the built-in ones, under the provided identifier
 * ("mymod_something").
 */
public class LayerRegisterEvent extends Event implements IModBusEvent {

    private final Map<String, ChunkLayerRenderer> customLayers = new LinkedHashMap<>();

    public void register(String layerId, ChunkLayerRenderer renderer) {
        if (!layerId.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid layer identifier: " + layerId);
        }
        customLayers.put(layerId, renderer);
    }

    public Map<String, ChunkLayerRenderer> getCustomLayers() {
        return customLayers;
    }
}
