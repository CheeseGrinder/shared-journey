package fr.cheesegrinder.sharedjourney.api;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.netty.buffer.ByteBuf;

import java.util.Locale;

/**
 * Map render layers.
 * CAVE comes in vertical "bands" of 16 blocks (band = floorDiv(y,16)), each
 * band being stored and synchronized separately.
 */
public enum MapLayer {
    DAY,
    NIGHT,
    TOPO,
    BIOME,
    CAVE;

    public static final StreamCodec<ByteBuf, MapLayer> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(i -> MapLayer.values()[i], MapLayer::ordinal);

    public String translationKey() {
        return "sharedjourney.layer." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * On-disk folder path (relative to the dimension). CAVE bands are grouped
     * under a parent folder: "cave/<band>".
     */
    public String folderName(int caveBand) {
        return this == CAVE ? "cave/" + caveBand : name().toLowerCase(Locale.ROOT);
    }
}
