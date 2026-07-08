package fr.cheesegrinder.sharedjourney.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Config CLIENT (spec §8) : minimap, radar, cache, couche par défaut. */
public final class ClientConfig {

    public static final ModConfigSpec SPEC;

    public static ModConfigSpec.BooleanValue MINIMAP_ENABLED;
    public static ModConfigSpec.IntValue MINIMAP_SIZE;
    public static ModConfigSpec.EnumValue<Corner> MINIMAP_CORNER;
    public static ModConfigSpec.EnumValue<Shape> MINIMAP_SHAPE;
    /** Rotation dynamique de la minimap avec le regard du joueur (spec §6.1). */
    public static ModConfigSpec.BooleanValue MINIMAP_ROTATE;

    public static ModConfigSpec.BooleanValue SHOW_COORDS;

    public static ModConfigSpec.BooleanValue RADAR_ENABLED;
    /** Rayon souhaité — plafonné par le serveur (radarMaxRadius). */
    public static ModConfigSpec.IntValue RADAR_RADIUS;

    public static ModConfigSpec.BooleanValue RADAR_PLAYERS;
    public static ModConfigSpec.BooleanValue RADAR_HOSTILE;
    public static ModConfigSpec.BooleanValue RADAR_PASSIVE;
    public static ModConfigSpec.BooleanValue RADAR_PETS;
    public static ModConfigSpec.BooleanValue RADAR_VILLAGERS;

    /** Rayon (blocs) auquel un waypoint temporaire est considéré atteint et supprimé. */
    public static ModConfigSpec.IntValue TEMP_WAYPOINT_RADIUS;

    /** Beacons de waypoints dans le monde : faisceau vertical + nom/distance. */
    public static ModConfigSpec.BooleanValue WAYPOINT_BEACONS;
    /** Distance minimale d'affichage d'un beacon (évite d'aveugler de près). */
    public static ModConfigSpec.IntValue BEACON_MIN_DISTANCE;
    /** Distance maximale d'affichage d'un beacon. */
    public static ModConfigSpec.IntValue BEACON_MAX_DISTANCE;

    /** Nom des waypoints (carte plein écran + étiquettes des beacons). */
    public static ModConfigSpec.BooleanValue SHOW_WAYPOINT_NAMES;
    /** Overlay des trains/rails Create (via le bridge JourneyMap). */
    public static ModConfigSpec.BooleanValue SHOW_TRAIN_OVERLAY;
    /** Overlay des gisements Create: Rock & Stone (via le bridge JourneyMap). */
    public static ModConfigSpec.BooleanValue SHOW_DEPOSIT_OVERLAY;

    /** Grille de chunks superposée à la minimap et à la carte plein écran. */
    public static ModConfigSpec.BooleanValue SHOW_GRID;
    /** Autorise la bascule auto vers les couches CAVE quand le joueur est sous terre. */
    public static ModConfigSpec.BooleanValue SHOW_CAVE;

    public static ModConfigSpec.ConfigValue<String> DEFAULT_LAYER;
    /** Sélection automatique de la couche minimap (jour/nuit, grottes sous terre). */
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
        MINIMAP_SIZE = b.comment("Taille de la minimap en pixels écran.").defineInRange("size", 128, 64, 320);
        MINIMAP_CORNER = b.defineEnum("corner", Corner.TOP_RIGHT);
        MINIMAP_SHAPE = b.comment("Forme de la minimap : ronde ou carrée.").defineEnum("shape", Shape.CIRCLE);
        MINIMAP_ROTATE =
                b.comment("La carte tourne avec le joueur (sinon nord fixe).").define("rotateWithPlayer", false);
        SHOW_COORDS = b.define("showCoordinates", true);
        b.pop();

        b.push("radar");
        RADAR_ENABLED = b.define("enabled", true);
        RADAR_RADIUS =
                b.comment("Rayon du radar en blocs (plafonné par le serveur).").defineInRange("radius", 48, 8, 128);
        RADAR_PLAYERS = b.define("showPlayers", true);
        RADAR_HOSTILE = b.define("showHostile", true);
        RADAR_PASSIVE = b.define("showPassive", false);
        RADAR_PETS = b.define("showPets", true);
        RADAR_VILLAGERS = b.define("showVillagers", true);
        b.pop();

        TEMP_WAYPOINT_RADIUS = b.comment(
                        "Rayon (blocs) auquel un waypoint temporaire est considéré atteint et supprimé.")
                .defineInRange("tempWaypointRadius", 8, 1, 128);
        WAYPOINT_BEACONS = b.comment("Beacons de waypoints dans le monde (faisceau vertical + nom et distance).")
                .define("waypointBeacons", true);
        BEACON_MIN_DISTANCE = b.comment("Distance minimale (blocs) d'affichage des beacons.")
                .defineInRange("beaconMinDistance", 4, 0, 512);
        BEACON_MAX_DISTANCE = b.comment("Distance maximale (blocs) d'affichage des beacons.")
                .defineInRange("beaconMaxDistance", 512, 16, 4096);
        SHOW_WAYPOINT_NAMES = b.comment("Nom des waypoints (carte plein écran + étiquettes des beacons).")
                .define("showWaypointNames", true);
        SHOW_TRAIN_OVERLAY = b.comment("Overlay des trains/rails Create sur les cartes (bridge JourneyMap).")
                .define("showTrainOverlay", true);
        SHOW_DEPOSIT_OVERLAY = b.comment("Overlay des gisements Create: Rock & Stone (bridge JourneyMap).")
                .define("showDepositOverlay", true);
        SHOW_GRID = b.comment("Grille de chunks superposée à la minimap et à la carte plein écran.")
                .define("showGrid", false);
        SHOW_CAVE = b.comment("Bascule auto vers les couches CAVE quand le joueur est sous terre.")
                .define("showCave", true);

        DEFAULT_LAYER = b.comment("Couche affichée par défaut (DAY, NIGHT, TOPO, BIOME, CAVE).")
                .define("defaultLayer", "DAY");
        AUTO_LAYER = b.comment("Bascule automatique de la minimap : jour/nuit selon l'heure, grottes sous terre.")
                .define("autoLayer", true);
        DISK_CACHE_ENABLED = b.comment("Cache disque des tuiles reçues (.minecraft/sharedjourney_cache/).")
                .define("diskCache", true);

        SPEC = b.build();
    }
}
