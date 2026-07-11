package fr.cheesegrinder.sharedjourney.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * "minimap" section of the client config: HUD minimap display (size, corner,
 * shape, rotation, coordinates).
 */
public final class MinimapClientConfig {

    public static ModConfigSpec.BooleanValue MINIMAP_ENABLED;
    public static ModConfigSpec.IntValue MINIMAP_SIZE;
    public static ModConfigSpec.EnumValue<Corner> MINIMAP_CORNER;
    public static ModConfigSpec.EnumValue<Shape> MINIMAP_SHAPE;
    /** Dynamic minimap rotation following the player's view (spec §6.1). */
    public static ModConfigSpec.BooleanValue MINIMAP_ROTATE;

    public static ModConfigSpec.BooleanValue SHOW_COORDS;

    /** Screen corner the HUD minimap is anchored to. */
    public enum Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    /** Outline shape used to draw the HUD minimap. */
    public enum Shape {
        CIRCLE,
        SQUARE
    }

    private MinimapClientConfig() {}

    static void define(ModConfigSpec.Builder b) {
        b.push("minimap");
        MINIMAP_ENABLED = b.define("enabled", true);
        MINIMAP_SIZE = b.comment("Minimap size in screen pixels.").defineInRange("size", 128, 64, 320);
        MINIMAP_CORNER = b.defineEnum("corner", Corner.TOP_RIGHT);
        MINIMAP_SHAPE = b.comment("Minimap shape: circle or square.").defineEnum("shape", Shape.CIRCLE);
        MINIMAP_ROTATE = b.comment("The map rotates with the player (otherwise fixed north).")
                .define("rotateWithPlayer", false);
        SHOW_COORDS = b.define("showCoordinates", true);
        b.pop();
    }
}
