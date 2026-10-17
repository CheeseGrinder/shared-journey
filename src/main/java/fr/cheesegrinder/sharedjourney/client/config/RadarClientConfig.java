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
    /** Mobs drawn as head icons from their own model (dot fallback). */
    public static ModConfigSpec.BooleanValue RADAR_MOB_HEADS;

    /** Entities far above / below the player fade out. */
    public static ModConfigSpec.BooleanValue HEIGHT_FADE;
    /** Vertical offset (blocks) beyond which the fade starts. */
    public static ModConfigSpec.IntValue HEIGHT_FADE_START;
    /** Vertical offset (blocks) at which the fade is complete. */
    public static ModConfigSpec.IntValue HEIGHT_FADE_FULL;
    /** Signed Y offset label next to faded entities. */
    public static ModConfigSpec.BooleanValue HEIGHT_FADE_LABEL;
    /** Up / down arrow next to faded entities. */
    public static ModConfigSpec.BooleanValue HEIGHT_FADE_ARROW;

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
        RADAR_MOB_HEADS = b.comment(
                        "Draw mobs as flat head icons rendered from their own model (colored dot fallback).")
                .define("mobHeads", true);
        HEIGHT_FADE = b.comment("Fade out radar entities far above or below the player.")
                .define("heightFade", true);
        HEIGHT_FADE_START = b.comment("Vertical distance (blocks) from which entities start to fade.")
                .defineInRange("heightFadeStart", 6, 1, 64);
        HEIGHT_FADE_FULL = b.comment(
                        "Vertical distance (blocks) at which the fade is complete. Treated as start + 1 when not above the start distance.")
                .defineInRange("heightFadeFull", 24, 2, 64);
        HEIGHT_FADE_LABEL =
                b.comment("Show the signed Y offset next to faded entities.").define("heightFadeLabel", false);
        HEIGHT_FADE_ARROW =
                b.comment("Show an up / down arrow next to faded entities.").define("heightFadeArrow", true);
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
