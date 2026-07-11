package fr.cheesegrinder.sharedjourney.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Waypoint keys of the client config: temp waypoint removal radius, in-world
 * beacons (beam + distance bounds) and name labels. Top-level TOML keys
 * (no section) kept for compatibility with existing config files.
 */
public final class WaypointClientConfig {

    /** Radius (blocks) at which a temp waypoint is considered reached and removed. */
    public static ModConfigSpec.IntValue TEMP_WAYPOINT_RADIUS;

    /** Waypoint beacons in the world: vertical beam + name/distance. */
    public static ModConfigSpec.BooleanValue WAYPOINT_BEACONS;
    /** Minimum display distance of a beacon (avoids blinding up close). */
    public static ModConfigSpec.IntValue BEACON_MIN_DISTANCE;
    /** Maximum display distance of a beacon. */
    public static ModConfigSpec.IntValue BEACON_MAX_DISTANCE;

    /** Waypoint names (fullscreen map + beacon labels). */
    public static ModConfigSpec.BooleanValue SHOW_WAYPOINT_NAMES;

    /** Automatic waypoint on death (in the reserved "deaths" group). */
    public static ModConfigSpec.BooleanValue DEATH_WAYPOINTS;

    private WaypointClientConfig() {}

    static void define(ModConfigSpec.Builder b) {
        TEMP_WAYPOINT_RADIUS = b.comment("Radius (blocks) at which a temp waypoint is considered reached and removed.")
                .defineInRange("tempWaypointRadius", 8, 1, 128);
        WAYPOINT_BEACONS = b.comment("Waypoint beacons in the world (vertical beam + name and distance).")
                .define("waypointBeacons", true);
        BEACON_MIN_DISTANCE = b.comment("Minimum display distance (blocks) of the beacons.")
                .defineInRange("beaconMinDistance", 4, 0, 512);
        BEACON_MAX_DISTANCE = b.comment("Maximum display distance (blocks) of the beacons.")
                .defineInRange("beaconMaxDistance", 512, 16, 4096);
        SHOW_WAYPOINT_NAMES =
                b.comment("Waypoint names (fullscreen map + beacon labels).").define("showWaypointNames", true);
        DEATH_WAYPOINTS = b.comment("Automatically create a waypoint where you die (\"deaths\" group).")
                .define("deathWaypoints", true);
    }
}
