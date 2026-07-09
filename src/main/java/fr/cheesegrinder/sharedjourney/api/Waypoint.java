package fr.cheesegrinder.sharedjourney.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Immutable waypoint. {@code source} identifies the origin: "user" for
 * hand-created points, or the modId of the third-party mod that created it
 * through the JourneyMap bridge (ex: "waystones").
 */
public record Waypoint(
        UUID id,
        String name,
        ResourceLocation dimension,
        int x,
        int y,
        int z,
        int colorRgb,
        String source,
        boolean visible,
        Type type) {

    /**
     * Waypoint scope:
     * - DIMENSION: visible only in its home dimension;
     * - GLOBAL: visible in every dimension;
     * - TEMP: like DIMENSION, but automatically removed when the player
     *   gets close (client-configurable radius).
     */
    public enum Type {
        DIMENSION,
        GLOBAL,
        TEMP
    }

    public static Waypoint create(String name, ResourceLocation dimension, BlockPos pos, int colorRgb, String source) {
        return create(name, dimension, pos, colorRgb, source, Type.DIMENSION);
    }

    public static Waypoint create(
            String name, ResourceLocation dimension, BlockPos pos, int colorRgb, String source, Type type) {
        return new Waypoint(
                UUID.randomUUID(),
                name,
                dimension,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                colorRgb & 0xFFFFFF,
                source,
                true,
                type);
    }

    public Waypoint withName(String newName) {
        return new Waypoint(id, newName, dimension, x, y, z, colorRgb, source, visible, type);
    }

    public Waypoint withColor(int rgb) {
        return new Waypoint(id, name, dimension, x, y, z, rgb & 0xFFFFFF, source, visible, type);
    }

    public Waypoint withVisible(boolean v) {
        return new Waypoint(id, name, dimension, x, y, z, colorRgb, source, v, type);
    }

    public Waypoint withType(Type newType) {
        return new Waypoint(id, name, dimension, x, y, z, colorRgb, source, visible, newType);
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }
}
