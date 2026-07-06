package fr.cheesegrinder.sharedjourney.api.event;

import fr.cheesegrinder.sharedjourney.api.Waypoint;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Événements de cycle de vie des waypoints, postés sur NeoForge.EVENT_BUS
 * (côté client, où vivent les waypoints). C'est aussi par ici que transitent
 * les créations issues du bridge JourneyMap : un mod peut les intercepter.
 */
public abstract class WaypointEvent extends Event {

    private final Waypoint waypoint;

    protected WaypointEvent(Waypoint waypoint) {
        this.waypoint = waypoint;
    }

    public Waypoint getWaypoint() {
        return waypoint;
    }

    /** Posté avant l'ajout ; annulable (le waypoint ne sera pas ajouté). */
    public static class Added extends WaypointEvent implements ICancellableEvent {
        public Added(Waypoint waypoint) {
            super(waypoint);
        }
    }

    /** Posté après suppression. */
    public static class Removed extends WaypointEvent {
        public Removed(Waypoint waypoint) {
            super(waypoint);
        }
    }

    /** Posté après modification (nom, couleur, visibilité). */
    public static class Updated extends WaypointEvent {
        public Updated(Waypoint waypoint) {
            super(waypoint);
        }
    }
}
