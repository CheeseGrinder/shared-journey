package fr.cheesegrinder.sharedjourney.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Map display keys of the client config: bridged plugin overlays, chunk grid,
 * layer selection (default/auto/cave) and the disk cache toggle. Top-level
 * TOML keys (no section) kept for compatibility with existing config files.
 */
public final class MapClientConfig {

    /** Create trains/tracks overlay (through the JourneyMap bridge). */
    public static ModConfigSpec.BooleanValue SHOW_TRAIN_OVERLAY;
    /** Create: Rock & Stone deposit overlay (through the JourneyMap bridge). */
    public static ModConfigSpec.BooleanValue SHOW_DEPOSIT_OVERLAY;

    /** Chunk grid overlaid on the minimap and the fullscreen map. */
    public static ModConfigSpec.BooleanValue SHOW_GRID;
    /** Allows the auto-switch to CAVE layers when the player is underground. */
    public static ModConfigSpec.BooleanValue SHOW_CAVE;

    public static ModConfigSpec.ConfigValue<String> DEFAULT_LAYER;
    /** Automatic minimap layer selection (day/night, caves underground). */
    public static ModConfigSpec.BooleanValue AUTO_LAYER;

    public static ModConfigSpec.BooleanValue DISK_CACHE_ENABLED;

    private MapClientConfig() {}

    static void define(ModConfigSpec.Builder b) {
        SHOW_TRAIN_OVERLAY = b.comment("Create trains/tracks overlay on the maps (JourneyMap bridge).")
                .define("showTrainOverlay", true);
        SHOW_DEPOSIT_OVERLAY = b.comment("Create: Rock & Stone deposit overlay (JourneyMap bridge).")
                .define("showDepositOverlay", true);
        SHOW_GRID = b.comment("Chunk grid overlaid on the minimap and the fullscreen map.")
                .define("showGrid", false);
        SHOW_CAVE = b.comment("Auto-switch to CAVE layers when the player is underground.")
                .define("showCave", true);

        DEFAULT_LAYER = b.comment("Layer shown by default (DAY, NIGHT, TOPO, BIOME, CAVE).")
                .define("defaultLayer", "DAY");
        AUTO_LAYER = b.comment("Automatic minimap switching: day/night by time of day, caves underground.")
                .define("autoLayer", true);
        DISK_CACHE_ENABLED = b.comment("Disk cache of received tiles (.minecraft/sharedjourney_cache/).")
                .define("diskCache", true);
    }
}
