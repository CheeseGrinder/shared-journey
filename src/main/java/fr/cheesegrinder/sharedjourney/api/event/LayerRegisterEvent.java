package fr.cheesegrinder.sharedjourney.api.event;

import fr.cheesegrinder.sharedjourney.api.ChunkLayerRenderer;
import fr.cheesegrinder.sharedjourney.api.MapLayer;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posted on the MOD bus at server startup so that other mods can register
 * custom layers (in addition to DAY/NIGHT/TOPO/BIOME/CAVE), under the
 * provided identifier ("mymod_something" — prefix it with your mod id).
 *
 * <p>Custom layers ride the full region pipeline: rendered by the engine
 * (through the given {@link ChunkLayerRenderer}), stored on disk, delta
 * synced and cached client-side like the built-in layers, and offered in
 * the client's layer cycling. What they do NOT get: per-dimension server
 * config/ops-UI toggles (a custom layer is active everywhere; return null
 * from the renderer to skip a chunk), CAVE-style vertical bands, and a
 * translated name unless the mod ships a {@code sharedjourney.layer.<id>}
 * lang key (the raw id is displayed otherwise).
 */
public class LayerRegisterEvent extends Event implements IModBusEvent {

    private final Map<String, ChunkLayerRenderer> customLayers = new LinkedHashMap<>();

    /**
     * Registers a custom layer. The id must be lowercase {@code
     * [a-z0-9_]+} and not collide with a built-in layer id.
     */
    public void register(String layerId, ChunkLayerRenderer renderer) {
        MapLayer layer = MapLayer.register(layerId);
        if (layer.isBuiltin()) {
            throw new IllegalArgumentException("Layer id collides with a built-in layer: " + layerId);
        }

        customLayers.put(layerId, renderer);
    }

    public Map<String, ChunkLayerRenderer> getCustomLayers() {
        return customLayers;
    }
}
