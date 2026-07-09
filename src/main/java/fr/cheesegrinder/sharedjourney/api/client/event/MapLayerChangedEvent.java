package fr.cheesegrinder.sharedjourney.api.client.event;

import fr.cheesegrinder.sharedjourney.api.MapLayer;

import net.neoforged.bus.api.Event;

/**
 * Posted on NeoForge.EVENT_BUS (CLIENT side only) when the displayed layer
 * changes through an EXPLICIT selection (toolbar, keyboard cycle) — not when
 * the minimap's auto mode switches layer by itself (day/night/underground).
 */
public class MapLayerChangedEvent extends Event {

    private final MapLayer layer;
    private final boolean minimap;

    public MapLayerChangedEvent(MapLayer layer, boolean minimap) {
        this.layer = layer;
        this.minimap = minimap;
    }

    /** The newly selected layer. */
    public MapLayer getLayer() {
        return layer;
    }

    /** True when the change happened on the HUD minimap, false on the fullscreen map. */
    public boolean isMinimap() {
        return minimap;
    }
}
