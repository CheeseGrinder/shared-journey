package fr.cheesegrinder.sharedjourney.api.client.event;

import fr.cheesegrinder.sharedjourney.api.client.MapView;

import net.minecraft.client.gui.GuiGraphics;

import net.neoforged.bus.api.Event;

/**
 * Posted on NeoForge.EVENT_BUS (CLIENT side only) once per frame for each
 * visible map — the HUD minimap and the fullscreen map — after the map tiles
 * and the bridged JourneyMap overlays, below the player markers and widgets.
 *
 * Listeners draw custom overlays with {@link #getGraphics()}, using the
 * {@link MapView} conversions to place world-anchored elements (see the
 * MapView coordinate contract; drawing is clipped to the map area).
 */
public class MapRenderEvent extends Event {

    private final GuiGraphics graphics;
    private final MapView view;
    private final float partialTick;

    public MapRenderEvent(GuiGraphics graphics, MapView view, float partialTick) {
        this.graphics = graphics;
        this.view = view;
        this.partialTick = partialTick;
    }

    public GuiGraphics getGraphics() {
        return graphics;
    }

    public MapView getView() {
        return view;
    }

    public float getPartialTick() {
        return partialTick;
    }
}
