package fr.cheesegrinder.sharedjourney.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * "waypoints" section of the server config: server-wide toggles for the
 * waypoint feature. Pushed to clients alongside the layer settings
 * (see {@code Payloads.LayerSettingsPayload}), at login and on reload.
 */
public final class WaypointServerConfig {

    /** Admin kill switch: clients also need their own deathWaypoints toggle on. */
    public static ModConfigSpec.BooleanValue DEATH_WAYPOINTS_ENABLED;

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
        WAYPOINT_STORAGE = b.comment(
                        "Where players' own waypoints are stored: SERVER (persisted per-player, private) or"
                                + " CLIENT (purely local, today's behavior). Temporary waypoints always stay"
                                + " client-local; public waypoints always go through the server.")
                .defineEnum("waypointStorage", Storage.SERVER);
        b.pop();
    }
}
