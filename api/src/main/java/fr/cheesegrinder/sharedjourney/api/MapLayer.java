package fr.cheesegrinder.sharedjourney.api;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Couches de rendu de la carte.
 * CAVE est déclinée en "bandes" verticales de 16 blocs (bande = floorDiv(y,16)),
 * chaque bande étant stockée et synchronisée séparément.
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
        return "sharedjourney.layer." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Nom de dossier sur disque. Pour CAVE, dépend de la bande. */
    public String folderName(int caveBand) {
        return this == CAVE ? "cave_" + caveBand : name().toLowerCase(java.util.Locale.ROOT);
    }
}
