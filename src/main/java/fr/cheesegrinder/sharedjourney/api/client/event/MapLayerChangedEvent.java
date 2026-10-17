package fr.cheesegrinder.sharedjourney.api.client.event;

import fr.cheesegrinder.sharedjourney.api.MapLayer;

import net.neoforged.bus.api.Event;

/**
 * Posted on NeoForge.EVENT_BUS (CLIENT side only) when the displayed layer
 * changes: through an explicit selection (toolbar, keyboard cycle) or, on the
 * minimap, when its auto mode switches layer by itself
 * (day/night/underground — see {@link #isAutomatic()}).
 */
public class MapLayerChangedEvent extends Event {

    private final MapLayer layer;
    private final boolean minimap;
    private final boolean automatic;

    /** Explicit selection. */
    public MapLayerChangedEvent(MapLayer layer, boolean minimap) {
        this(layer, minimap, false);
    }

    public MapLayerChangedEvent(MapLayer layer, boolean minimap, boolean automatic) {
        this.layer = layer;
        this.minimap = minimap;
        this.automatic = automatic;
    }

    /** The newly selected layer. */
    public MapLayer getLayer() {
        return layer;
    }

    /** True when the change happened on the HUD minimap, false on the fullscreen map. */
    public boolean isMinimap() {
        return minimap;
    }

    /**
     * True when the minimap's auto mode switched layer by itself, false when
     * the player picked it. Automatic switches happen after the wanted layer
     * held steadily for about a second.
     */
    public boolean isAutomatic() {
        return automatic;
    }
}
