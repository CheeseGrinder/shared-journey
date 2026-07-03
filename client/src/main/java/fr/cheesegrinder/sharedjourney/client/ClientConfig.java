package fr.cheesegrinder.sharedjourney.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Config CLIENT (spec §8) : minimap, radar, cache, couche par défaut. */
public final class ClientConfig {

    public static final ModConfigSpec SPEC;

    public static ModConfigSpec.BooleanValue MINIMAP_ENABLED;
    public static ModConfigSpec.IntValue MINIMAP_SIZE;
    public static ModConfigSpec.EnumValue<Corner> MINIMAP_CORNER;
    /** Rotation dynamique de la minimap avec le regard du joueur (spec §6.1). */
    public static ModConfigSpec.BooleanValue MINIMAP_ROTATE;
    public static ModConfigSpec.BooleanValue SHOW_COORDS;

    public static ModConfigSpec.BooleanValue RADAR_ENABLED;
    /** Rayon souhaité — plafonné par le serveur (radarMaxRadius). */
    public static ModConfigSpec.IntValue RADAR_RADIUS;
    public static ModConfigSpec.BooleanValue RADAR_PLAYERS;
    public static ModConfigSpec.BooleanValue RADAR_HOSTILE;
    public static ModConfigSpec.BooleanValue RADAR_PASSIVE;

    public static ModConfigSpec.ConfigValue<String> DEFAULT_LAYER;
    public static ModConfigSpec.BooleanValue DISK_CACHE_ENABLED;

    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("minimap");
        MINIMAP_ENABLED = b.define("enabled", true);
        MINIMAP_SIZE = b.comment("Taille de la minimap en pixels écran.").defineInRange("size", 128, 64, 320);
        MINIMAP_CORNER = b.defineEnum("corner", Corner.TOP_RIGHT);
        MINIMAP_ROTATE = b.comment("La carte tourne avec le joueur (sinon nord fixe).").define("rotateWithPlayer", false);
        SHOW_COORDS = b.define("showCoordinates", true);
        b.pop();

        b.push("radar");
        RADAR_ENABLED = b.define("enabled", true);
        RADAR_RADIUS = b.comment("Rayon du radar en blocs (plafonné par le serveur).")
                .defineInRange("radius", 48, 8, 128);
        RADAR_PLAYERS = b.define("showPlayers", true);
        RADAR_HOSTILE = b.define("showHostile", true);
        RADAR_PASSIVE = b.define("showPassive", false);
        b.pop();

        DEFAULT_LAYER = b.comment("Couche affichée par défaut (DAY, NIGHT, TOPO, BIOME, CAVE).")
                .define("defaultLayer", "DAY");
        DISK_CACHE_ENABLED = b.comment("Cache disque des tuiles reçues (.minecraft/sharedjourney_cache/).")
                .define("diskCache", true);

        SPEC = b.build();
    }
}
