package fr.cheesegrinder.sharedjourney.api.client.event;

import fr.cheesegrinder.sharedjourney.api.client.MapView;

import net.neoforged.bus.api.Event;

/**
 * Fullscreen map visibility events, posted on NeoForge.EVENT_BUS (CLIENT
 * side only). "Visible" semantics: Opened fires when the map screen comes
 * up, Closed when it goes away — including when it is temporarily replaced
 * by another screen (waypoint modal, chat) and re-shown afterwards.
 */
public abstract class FullMapScreenEvent extends Event {

    protected FullMapScreenEvent() {}

    /** Posted when the fullscreen map becomes visible. */
    public static class Opened extends FullMapScreenEvent {

        private final MapView view;

        public Opened(MapView view) {
            this.view = view;
        }

        public MapView getView() {
            return view;
        }
    }

    /** Posted when the fullscreen map is taken down. */
    public static class Closed extends FullMapScreenEvent {

        public Closed() {}
    }
}
