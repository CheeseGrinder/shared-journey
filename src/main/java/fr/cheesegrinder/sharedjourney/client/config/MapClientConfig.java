package fr.cheesegrinder.sharedjourney.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * "map" section of the client config: bridged plugin overlays, chunk grid,
 * layer selection (default/auto/cave) and the disk cache toggle.
 */
public final class MapClientConfig {

    /** Create trains/tracks overlay (through the JourneyMap bridge). */
    public static ModConfigSpec.BooleanValue SHOW_TRAIN_OVERLAY;
    /** Create: Rock & Stone deposit overlay (through the JourneyMap bridge). */
    public static ModConfigSpec.BooleanValue SHOW_DEPOSIT_OVERLAY;

    /** Chunk grid overlaid on the minimap and the fullscreen map. */
    public static ModConfigSpec.BooleanValue SHOW_GRID;
    /** Global waypoint overlay toggle (minimap + fullscreen map). */
    public static ModConfigSpec.BooleanValue SHOW_WAYPOINTS;
    /** Allows the auto-switch to CAVE layers when the player is underground. */
    public static ModConfigSpec.BooleanValue SHOW_CAVE;

    public static ModConfigSpec.ConfigValue<String> DEFAULT_LAYER;
    /** Automatic minimap layer selection (day/night, caves underground). */
    public static ModConfigSpec.BooleanValue AUTO_LAYER;
    /** Fullscreen map reopens on its last layer instead of the minimap's. */
    public static ModConfigSpec.BooleanValue REMEMBER_LAYER;

    public static ModConfigSpec.BooleanValue DISK_CACHE_ENABLED;

    private MapClientConfig() {}

    static void define(ModConfigSpec.Builder b) {
        b.push("map");
        SHOW_TRAIN_OVERLAY = b.comment("Create trains/tracks overlay on the maps (JourneyMap bridge).")
                .define("showTrainOverlay", true);
        SHOW_DEPOSIT_OVERLAY = b.comment("Create: Rock & Stone deposit overlay (JourneyMap bridge).")
                .define("showDepositOverlay", true);
        SHOW_GRID = b.comment("Chunk grid overlaid on the minimap and the fullscreen map.")
                .define("showGrid", false);
        SHOW_WAYPOINTS = b.comment("Waypoints drawn on the minimap and the fullscreen map.")
                .define("showWaypoints", true);
        SHOW_CAVE = b.comment("Auto-switch to CAVE layers when the player is underground.")
                .define("showCave", true);

        DEFAULT_LAYER = b.comment("Layer shown by default (DAY, NIGHT, TOPO, BIOME, CAVE).")
                .define("defaultLayer", "DAY");
        AUTO_LAYER = b.comment("Automatic minimap switching: day/night by time of day, caves underground.")
                .define("autoLayer", true);
        REMEMBER_LAYER = b.comment("Reopen the fullscreen map on its last layer instead of the minimap's.")
                .define("rememberLayer", false);
        DISK_CACHE_ENABLED = b.comment("Disk cache of received tiles (.minecraft/sharedjourney_cache/).")
                .define("diskCache", true);
        b.pop();
    }
}
