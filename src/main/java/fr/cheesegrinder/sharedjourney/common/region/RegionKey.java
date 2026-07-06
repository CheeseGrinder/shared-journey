package fr.cheesegrinder.sharedjourney.common;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Identifie une tuile de carte : dimension + couche (+ bande CAVE) + région.
 * Une région couvre 32x32 chunks = 512x512 blocs (1 pixel / bloc).
 */
public record RegionKey(ResourceKey<Level> dimension, MapLayer layer, int caveBand, int rx, int rz) {

    public static final int REGION_BLOCKS = 512;
    public static final int REGION_CHUNKS = 32;

    public static RegionKey of(ResourceKey<Level> dim, MapLayer layer, int caveBand, int chunkX, int chunkZ) {
        return new RegionKey(dim, layer, layer == MapLayer.CAVE ? caveBand : 0,
                Math.floorDiv(chunkX, REGION_CHUNKS), Math.floorDiv(chunkZ, REGION_CHUNKS));
    }

    /** Nom de fichier conforme à la spec : region_X_Z.png */
    public String fileName() {
        return "region_" + rx + "_" + rz + ".png";
    }

    /** Clé texte stable pour l'index.json : "dim|layer|band|rx|rz". */
    public String indexKey() {
        return dimension.location() + "|" + layer.name() + "|" + caveBand + "|" + rx + "|" + rz;
    }

    public static RegionKey fromIndexKey(String s) {
        String[] p = s.split("\\|");
        if (p.length != 5) return null;
        ResourceLocation dim = ResourceLocation.tryParse(p[0]);
        if (dim == null) return null;
        try {
            return new RegionKey(ResourceKey.create(Registries.DIMENSION, dim),
                    MapLayer.valueOf(p[1]), Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]), Integer.parseInt(p[4]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static final StreamCodec<FriendlyByteBuf, RegionKey> STREAM_CODEC = StreamCodec.of(
            (buf, key) -> {
                buf.writeResourceLocation(key.dimension.location());
                buf.writeVarInt(key.layer.ordinal());
                buf.writeVarInt(key.caveBand);
                buf.writeVarInt(key.rx);
                buf.writeVarInt(key.rz);
            },
            buf -> {
                ResourceLocation dim = buf.readResourceLocation();
                MapLayer layer = MapLayer.values()[buf.readVarInt()];
                return new RegionKey(ResourceKey.create(Registries.DIMENSION, dim), layer,
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
            });
}
