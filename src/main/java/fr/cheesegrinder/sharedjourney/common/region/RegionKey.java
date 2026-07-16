package fr.cheesegrinder.sharedjourney.common.region;

import fr.cheesegrinder.sharedjourney.api.MapLayer;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * Identifies a map tile: dimension + layer (+ CAVE band) + region.
 * A region covers 32x32 chunks = 512x512 blocks (1 pixel / block).
 */
public record RegionKey(ResourceKey<Level> dimension, MapLayer layer, int caveBand, int rx, int rz) {

    public static final int REGION_BLOCKS = 512;
    public static final int REGION_CHUNKS = 32;

    public static RegionKey of(ResourceKey<Level> dim, MapLayer layer, int caveBand, int chunkX, int chunkZ) {
        return new RegionKey(
                dim,
                layer,
                layer == MapLayer.CAVE ? caveBand : 0,
                Math.floorDiv(chunkX, REGION_CHUNKS),
                Math.floorDiv(chunkZ, REGION_CHUNKS));
    }

    /** Spec-compliant file name: region_X_Z.png (.bin for the INFO data layer). */
    public String fileName() {
        String extension = layer == MapLayer.INFO ? ".bin" : ".png";
        return "region_" + rx + "_" + rz + extension;
    }

    /** Stable text key for index.json: "dim|layer|band|rx|rz". */
    public String indexKey() {
        return dimension.location() + "|" + layer.name() + "|" + caveBand + "|" + rx + "|" + rz;
    }

    public static RegionKey fromIndexKey(String s) {
        String[] p = s.split("\\|");
        if (p.length != 5) {
            return null;
        }

        ResourceLocation dim = ResourceLocation.tryParse(p[0]);
        if (dim == null) {
            return null;
        }

        try {
            // Lenient on the layer: a client disk cache may hold regions of
            // a custom layer before the server announces it this session.
            return new RegionKey(
                    ResourceKey.create(Registries.DIMENSION, dim),
                    MapLayer.register(p[1].toLowerCase(Locale.ROOT)),
                    Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static final StreamCodec<FriendlyByteBuf, RegionKey> STREAM_CODEC = StreamCodec.of(
            (buf, key) -> {
                buf.writeResourceLocation(key.dimension.location());
                buf.writeUtf(key.layer.id());
                buf.writeVarInt(key.caveBand);
                buf.writeVarInt(key.rx);
                buf.writeVarInt(key.rz);
            },
            buf -> {
                ResourceLocation dim = buf.readResourceLocation();
                // Lenient: a custom layer id is registered on the fly
                // (the client may not have the registering mod).
                MapLayer layer = MapLayer.register(buf.readUtf());

                return new RegionKey(
                        ResourceKey.create(Registries.DIMENSION, dim),
                        layer,
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt());
            });
}
