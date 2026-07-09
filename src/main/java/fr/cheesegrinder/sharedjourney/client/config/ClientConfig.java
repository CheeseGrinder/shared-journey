package fr.cheesegrinder.sharedjourney.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** CLIENT config (spec §8): minimap, radar, cache, default layer. */
public final class ClientConfig {

    public static final ModConfigSpec SPEC;

    public static ModConfigSpec.BooleanValue MINIMAP_ENABLED;
    public static ModConfigSpec.IntValue MINIMAP_SIZE;
    public static ModConfigSpec.EnumValue<Corner> MINIMAP_CORNER;
    public static ModConfigSpec.EnumValue<Shape> MINIMAP_SHAPE;
    /** Dynamic minimap rotation following the player's view (spec §6.1). */
    public static ModConfigSpec.BooleanValue MINIMAP_ROTATE;

    public static ModConfigSpec.BooleanValue SHOW_COORDS;

    public static ModConfigSpec.BooleanValue RADAR_ENABLED;
    /** Desired radius — capped by the server (radarMaxRadius). */
    public static ModConfigSpec.IntValue RADAR_RADIUS;

    public static ModConfigSpec.BooleanValue RADAR_PLAYERS;
    /** Asks the server to be hidden from the OTHER players' map. */
    public static ModConfigSpec.BooleanValue HIDE_FROM_MAP;

    public static ModConfigSpec.BooleanValue RADAR_HOSTILE;
    public static ModConfigSpec.BooleanValue RADAR_PASSIVE;
    public static ModConfigSpec.BooleanValue RADAR_PETS;
    public static ModConfigSpec.BooleanValue RADAR_VILLAGERS;

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

    public enum Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    public enum Shape {
        CIRCLE,
        SQUARE
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("minimap");
        MINIMAP_ENABLED = b.define("enabled", true);
        MINIMAP_SIZE = b.comment("Minimap size in screen pixels.").defineInRange("size", 128, 64, 320);
        MINIMAP_CORNER = b.defineEnum("corner", Corner.TOP_RIGHT);
        MINIMAP_SHAPE = b.comment("Minimap shape: circle or square.").defineEnum("shape", Shape.CIRCLE);
        MINIMAP_ROTATE = b.comment("The map rotates with the player (otherwise fixed north).")
                .define("rotateWithPlayer", false);
        SHOW_COORDS = b.define("showCoordinates", true);
        b.pop();

        b.push("radar");
        RADAR_ENABLED = b.define("enabled", true);
        RADAR_RADIUS =
                b.comment("Radar radius in blocks (capped by the server).").defineInRange("radius", 48, 8, 128);
        RADAR_PLAYERS = b.define("showPlayers", true);
        HIDE_FROM_MAP = b.comment("Asks the server to be hidden from the other players' map.")
                .define("hideFromMap", false);
        RADAR_HOSTILE = b.define("showHostile", true);
        RADAR_PASSIVE = b.define("showPassive", false);
        RADAR_PETS = b.define("showPets", true);
        RADAR_VILLAGERS = b.define("showVillagers", true);
        b.pop();

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

        SPEC = b.build();
    }
}
