package fr.cheesegrinder.sharedjourney.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * "waypoints" section of the server config: server-wide toggles for the
 * waypoint feature. Pushed to clients alongside the layer settings
 * (see {@code RegionSyncPayloads.LayerSettingsPayload}), at login and on reload.
 */
public final class WaypointServerConfig {

    /** Admin kill switch: clients also need their own deathWaypoints toggle on. */
    public static ModConfigSpec.BooleanValue DEATH_WAYPOINTS_ENABLED;

    /** Automatic bed waypoint (private, per player): set when a player sets their spawn in a bed. */
    public static ModConfigSpec.BooleanValue BED_WAYPOINTS_ENABLED;

    /** Automatic nether portal waypoint (shared): recorded when a player goes through a portal. */
    public static ModConfigSpec.BooleanValue PORTAL_WAYPOINTS_ENABLED;

    /** Automatic respawn anchor waypoint (private, per player): set when a player sets their spawn in one. */
    public static ModConfigSpec.BooleanValue ANCHOR_WAYPOINTS_ENABLED;

    /**
     * Converted waypoints: lets players link a waypoint so its Overworld / Nether
     * counterpart (coordinates multiplied or divided by 8) is also shown in the paired dimension.
     */
    public static ModConfigSpec.BooleanValue CONVERTED_WAYPOINTS_ENABLED;

    /** Where players' own (non-public) waypoints are stored. */
    public static ModConfigSpec.EnumValue<Storage> WAYPOINT_STORAGE;

    /**
     * SERVER: DIMENSION waypoints are persisted server-side, per player
     * (never shared with other players), synced back to their owner only.
     * CLIENT: today's behavior — purely local, in the client's cache
     * folder. Either way, TEMP waypoints always stay client-local (short
     * lived, already detected client-side every tick) and PUBLIC ones
     * always go through the server regardless of this setting.
     */
    public enum Storage {
        SERVER,
        CLIENT
    }

    private WaypointServerConfig() {}

    static void define(ModConfigSpec.Builder b) {
        b.push("waypoints");
        DEATH_WAYPOINTS_ENABLED = b.comment(
                        "Allow automatic death waypoints. Each client also has its own on/off toggle.")
                .define("deathWaypointsEnabled", true);
        BED_WAYPOINTS_ENABLED = b.comment(
                        "Automatic bed waypoint: private to each player, follows the bed they last set their"
                                + " spawn in. Disabling hides the existing ones without deleting them.")
                .define("bedWaypointsEnabled", true);
        PORTAL_WAYPOINTS_ENABLED = b.comment(
                        "Automatic nether portal waypoints: shared with everyone, recorded when a player goes"
                                + " through a portal (both ends). Disabling hides the existing ones without"
                                + " deleting them.")
                .define("portalWaypointsEnabled", true);
        ANCHOR_WAYPOINTS_ENABLED = b.comment(
                        "Automatic respawn anchor waypoint: private to each player, follows the anchor they"
                                + " last set their spawn in. Disabling hides the existing ones without deleting"
                                + " them.")
                .define("anchorWaypointsEnabled", true);
        CONVERTED_WAYPOINTS_ENABLED = b.comment(
                        "Converted waypoints: a player can link one of their Overworld or Nether waypoints so"
                                + " that a read-only counterpart with converted coordinates (x8 / divided by 8)"
                                + " is also shown in the other dimension. Never created automatically."
                                + " Disabling hides every converted counterpart and blocks new links; the existing"
                                + " links are kept and come back when re-enabled.")
                .define("convertedWaypointsEnabled", true);
        WAYPOINT_STORAGE = b.comment(
                        "Where players' own waypoints are stored: SERVER (persisted per-player, private) or"
                                + " CLIENT (purely local, today's behavior). Temporary waypoints always stay"
                                + " client-local; public waypoints always go through the server.")
                .defineEnum("waypointStorage", Storage.SERVER);
        b.pop();
    }
}
