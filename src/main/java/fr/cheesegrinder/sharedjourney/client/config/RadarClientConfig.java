package fr.cheesegrinder.sharedjourney.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * "radar" section of the client config: entity radar filters and radius, plus
 * player visibility on the shared map.
 */
public final class RadarClientConfig {

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

    private RadarClientConfig() {}

    static void define(ModConfigSpec.Builder b) {
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
    }
}
