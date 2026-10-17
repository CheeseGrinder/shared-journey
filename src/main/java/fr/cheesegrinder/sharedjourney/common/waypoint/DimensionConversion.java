package fr.cheesegrinder.sharedjourney.common.waypoint;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Pure Overworld / Nether coordinate conversion behind the "converted
 * waypoints" (a waypoint linked to be shown in the paired dimension). Only
 * the vanilla Overworld and Nether form a pair; every other dimension has
 * no counterpart and is never converted.
 *
 * <ul>
 *   <li>Overworld to Nether: X and Z are divided by 8 with a floor division,
 *       so the result is the Nether block that contains the scaled
 *       position (as vanilla portal linking does), negative coordinates
 *       included.
 *   <li>Nether to Overworld: X and Z are multiplied by 8.
 *   <li>Y is kept as is, then clamped to the target dimension's buildable
 *       range: {@code 0..127} in the Nether (its roof is at 127), the
 *       whole Overworld range {@code -64..319} in the other direction, so
 *       a surface marker stays at its height and an out-of-range one is
 *       pulled to the nearest valid block, like vanilla portal linking.
 * </ul>
 */
public final class DimensionConversion {

    /** Horizontal scale between the Overworld and the Nether. */
    public static final int NETHER_SCALE = 8;

    /** Lowest buildable Y of the Nether. */
    public static final int NETHER_MIN_Y = 0;

    /** Highest buildable Y of the Nether (the bedrock roof sits there). */
    public static final int NETHER_MAX_Y = 127;

    /** Lowest buildable Y of the Overworld. */
    public static final int OVERWORLD_MIN_Y = -64;

    /** Highest buildable Y of the Overworld. */
    public static final int OVERWORLD_MAX_Y = 319;

    private static final ResourceLocation OVERWORLD = Level.OVERWORLD.location();
    private static final ResourceLocation NETHER = Level.NETHER.location();

    private DimensionConversion() {}

    /** Is this dimension half of the Overworld / Nether pair? */
    public static boolean isPaired(ResourceLocation dimension) {
        return OVERWORLD.equals(dimension) || NETHER.equals(dimension);
    }

    /** The other dimension of the pair, or null when {@code dimension} is not paired. */
    public static ResourceLocation counterpart(ResourceLocation dimension) {
        if (OVERWORLD.equals(dimension)) {
            return NETHER;
        }

        if (NETHER.equals(dimension)) {
            return OVERWORLD;
        }
        return null;
    }

    /**
     * Link flag the server stores for an upsert. While the feature is
     * enabled the requested flag wins (only for a paired dimension);
     * while it is disabled nothing can be created or changed, so the
     * stored flag is kept untouched (the link stays dormant on disk).
     */
    public static boolean resolveLink(boolean featureEnabled, boolean requested, boolean stored, ResourceLocation dim) {
        if (!featureEnabled) {
            return stored;
        }
        return requested && isPaired(dim);
    }

    /** Converted X (or Z) of a coordinate expressed in {@code from}. */
    public static int convertHorizontal(ResourceLocation from, int value) {
        if (OVERWORLD.equals(from)) {
            return Math.floorDiv(value, NETHER_SCALE);
        }

        if (NETHER.equals(from)) {
            return value * NETHER_SCALE;
        }
        return value;
    }

    /** Converted Y of a height expressed in {@code from}: kept, then clamped to the target range. */
    public static int convertHeight(ResourceLocation from, int y) {
        if (OVERWORLD.equals(from)) {
            return Math.clamp(y, NETHER_MIN_Y, NETHER_MAX_Y);
        }

        if (NETHER.equals(from)) {
            return Math.clamp(y, OVERWORLD_MIN_Y, OVERWORLD_MAX_Y);
        }
        return y;
    }
}
