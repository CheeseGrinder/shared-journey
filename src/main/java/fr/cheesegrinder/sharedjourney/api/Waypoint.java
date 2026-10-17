package fr.cheesegrinder.sharedjourney.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Immutable waypoint. {@code source} identifies the origin:
 * {@link #SOURCE_USER} for hand-created points, or the modId of the
 * third-party mod that created it through the JourneyMap bridge
 * (ex: "waystones"). {@code group} organizes waypoints in the management
 * screen; group visibility is toggled as a whole there.
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
        String group,
        boolean visible,
        Type type) {

    /** Source of hand-created waypoints — the only ones persisted locally. */
    public static final String SOURCE_USER = "user";

    /**
     * Source of the server-shared public waypoints. Volatile client-side
     * (resynchronized by the server at every login), like bridged mods'.
     */
    public static final String SOURCE_PUBLIC = "public";

    /**
     * Source of banner waypoints: a NAMED banner (renamed at an anvil
     * before being placed) detected server-side. Read-only, world-derived,
     * volatile client-side like public waypoints.
     */
    public static final String SOURCE_BANNER = "banner";

    /**
     * Source of the automatic contextual waypoints (player bed, nether
     * portal, respawn anchor) detected server-side. Read-only, world-derived,
     * volatile client-side like banner waypoints.
     */
    public static final String SOURCE_AUTO = "auto";

    /** Default group of hand-created waypoints. */
    public static final String GROUP_DEFAULT = "default";

    /** Reserved group of the automatic death waypoints. */
    public static final String GROUP_DEATHS = "deaths";

    /** Reserved group of the server-shared public waypoints. */
    public static final String GROUP_PUBLIC = "public";

    /** Reserved group of banner waypoints. */
    public static final String GROUP_BANNERS = "banners";

    /** Reserved group of the automatic bed waypoints. */
    public static final String GROUP_BEDS = "beds";

    /** Reserved group of the automatic nether portal waypoints. */
    public static final String GROUP_PORTALS = "portals";

    /** Reserved group of the automatic respawn anchor waypoints. */
    public static final String GROUP_ANCHORS = "anchors";

    public Waypoint {
        if (group == null || group.isBlank()) {
            group = GROUP_DEFAULT;
        }
    }

    /**
     * Waypoint scope. Every type is bound to its home dimension:
     * - DIMENSION: private, only this client sees it;
     * - PUBLIC: shared with every player through the server (persisted in
     *   the world folder, broadcast on change). Its {@code visible} flag
     *   stays a per-client choice and is never shared;
     * - TEMP: like DIMENSION, but automatically removed when the player
     *   gets close (client-configurable radius).
     */
    public enum Type {
        DIMENSION,
        PUBLIC,
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
                GROUP_DEFAULT,
                true,
                type);
    }

    public Waypoint withName(String newName) {
        return new Waypoint(id, newName, dimension, x, y, z, colorRgb, source, group, visible, type);
    }

    public Waypoint withColor(int rgb) {
        return new Waypoint(id, name, dimension, x, y, z, rgb & 0xFFFFFF, source, group, visible, type);
    }

    public Waypoint withGroup(String newGroup) {
        return new Waypoint(id, name, dimension, x, y, z, colorRgb, source, newGroup, visible, type);
    }

    public Waypoint withVisible(boolean v) {
        return new Waypoint(id, name, dimension, x, y, z, colorRgb, source, group, v, type);
    }

    public Waypoint withType(Type newType) {
        return new Waypoint(id, name, dimension, x, y, z, colorRgb, source, group, visible, newType);
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /**
     * Is this waypoint derived from a physical block of the world (named
     * banner, bed, nether portal, respawn anchor)? Such waypoints are
     * created and removed by the server alone: read-only for the player,
     * who can only hide them locally.
     */
    public boolean isWorldDerived() {
        return SOURCE_BANNER.equals(source) || SOURCE_AUTO.equals(source);
    }

    /** Is this one of the groups the mod reserves for itself? */
    public static boolean isReservedGroup(String group) {
        return GROUP_DEFAULT.equals(group)
                || GROUP_DEATHS.equals(group)
                || GROUP_PUBLIC.equals(group)
                || GROUP_BANNERS.equals(group)
                || GROUP_BEDS.equals(group)
                || GROUP_PORTALS.equals(group)
                || GROUP_ANCHORS.equals(group);
    }
}
