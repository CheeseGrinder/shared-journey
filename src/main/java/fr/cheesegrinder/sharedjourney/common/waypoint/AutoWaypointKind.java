package fr.cheesegrinder.sharedjourney.common.waypoint;

import fr.cheesegrinder.sharedjourney.api.Waypoint;

import org.jetbrains.annotations.Nullable;

/**
 * The kinds of automatic contextual waypoints ({@link Waypoint#SOURCE_AUTO}),
 * created server-side from what a player does in the world. Each kind has
 * its own reserved group, fixed colour, dedicated icon, and a scope:
 * {@code shared} kinds are broadcast to every player, the others only go to
 * the player who triggered them (their own private waypoint).
 */
public enum AutoWaypointKind {

    /** The bed a player set their spawn in (private, one per player). */
    BED("bed", Waypoint.GROUP_BEDS, 0xD84040, false),

    /** A nether portal a player went through (shared, one per portal). */
    PORTAL("portal", Waypoint.GROUP_PORTALS, 0xA040E0, true),

    /** The respawn anchor a player set their spawn in (private, one per player). */
    ANCHOR("anchor", Waypoint.GROUP_ANCHORS, 0xE8A640, false);

    private final String id;
    private final String group;
    private final int colorRgb;
    private final boolean shared;

    AutoWaypointKind(String id, String group, int colorRgb, boolean shared) {
        this.id = id;
        this.group = group;
        this.colorRgb = colorRgb;
        this.shared = shared;
    }

    /** Stable wire/disk identifier. */
    public String id() {
        return id;
    }

    /** Reserved waypoint group holding this kind. */
    public String group() {
        return group;
    }

    public int colorRgb() {
        return colorRgb;
    }

    /** Shared kinds are visible to every player, the others to their owner only. */
    public boolean shared() {
        return shared;
    }

    /** The kind with this identifier, or null when unknown (newer peer). */
    @Nullable
    public static AutoWaypointKind fromId(String id) {
        for (AutoWaypointKind kind : values()) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }

        return null;
    }

    /** The kind stored in this reserved group, or null for any other group. */
    @Nullable
    public static AutoWaypointKind forGroup(String group) {
        for (AutoWaypointKind kind : values()) {
            if (kind.group.equals(group)) {
                return kind;
            }
        }

        return null;
    }
}
