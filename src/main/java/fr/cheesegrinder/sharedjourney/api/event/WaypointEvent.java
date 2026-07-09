package fr.cheesegrinder.sharedjourney.api.event;

import fr.cheesegrinder.sharedjourney.api.Waypoint;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Waypoint lifecycle events, posted on NeoForge.EVENT_BUS (client side, where
 * waypoints live). Creations coming from the JourneyMap bridge also flow
 * through here: a mod can intercept them.
 */
public abstract class WaypointEvent extends Event {

    private final Waypoint waypoint;

    protected WaypointEvent(Waypoint waypoint) {
        this.waypoint = waypoint;
    }

    public Waypoint getWaypoint() {
        return waypoint;
    }

    /** Posted before addition; cancellable (the waypoint will not be added). */
    public static class Added extends WaypointEvent implements ICancellableEvent {
        public Added(Waypoint waypoint) {
            super(waypoint);
        }
    }

    /** Posted after removal. */
    public static class Removed extends WaypointEvent {
        public Removed(Waypoint waypoint) {
            super(waypoint);
        }
    }

    /** Posted after an update (name, color, visibility). */
    public static class Updated extends WaypointEvent {
        public Updated(Waypoint waypoint) {
            super(waypoint);
        }
    }
}
