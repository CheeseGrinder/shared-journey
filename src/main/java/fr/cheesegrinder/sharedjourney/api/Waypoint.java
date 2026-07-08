package fr.cheesegrinder.sharedjourney.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Waypoint immuable. `source` identifie l'origine : "user" pour les points
 * créés à la main, ou le modId du mod tiers qui l'a créé via le bridge
 * JourneyMap (ex: "waystones").
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
     * Portée du waypoint :
     * - DIMENSION : visible uniquement dans sa dimension d'origine ;
     * - GLOBAL : visible dans toutes les dimensions ;
     * - TEMP : comme DIMENSION, mais supprimé automatiquement quand le
     *   joueur arrive à proximité (rayon configurable côté client).
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
