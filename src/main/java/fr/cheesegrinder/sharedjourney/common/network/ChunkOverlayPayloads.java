package fr.cheesegrinder.sharedjourney.common.network;

import fr.cheesegrinder.sharedjourney.common.overlay.ChunkOverlayKind;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import io.netty.handler.codec.DecoderException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-computed chunk overlay payloads (force-loaded chunks, slime
 * chunks): the server announces which overlays it allows, clients request
 * the chunk bitmap of the map regions they display, the server answers with
 * one 32x32 chunk bitmap per region (and re-sends a forced-chunks region
 * when it changes). Handlers are dispatched through {@link Payloads.Hooks}
 * — see {@link Payloads} for the indirection rationale.
 */
public final class ChunkOverlayPayloads {

    /** Max regions of one request or answer (client requests stay far below). */
    public static final int MAX_REGIONS = 64;

    /** Hard decoder bound: a larger count is a malformed or hostile payload. */
    private static final int MAX_DECODED_REGIONS = 256;

    private static final int KIND_ID_MAX_LENGTH = 32;

    private ChunkOverlayPayloads() {}

    // ---------------------------------------------------------------- S2C: allowed overlays

    /**
     * Overlays the server allows ({@code overlays.*} server config), sent
     * at login and on every config reload. A disallowed overlay is never
     * answered nor pushed.
     */
    public record OverlaySettingsPayload(boolean forcedChunks, boolean slimeChunks) implements CustomPacketPayload {
        public static final Type<OverlaySettingsPayload> TYPE = new Type<>(Payloads.id("overlay_settings"));

        public static final StreamCodec<FriendlyByteBuf, OverlaySettingsPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.forcedChunks);
                    buf.writeBoolean(p.slimeChunks);
                },
                buf -> new OverlaySettingsPayload(buf.readBoolean(), buf.readBoolean()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- C2S: region request

    /**
     * Client asks for the chunk bitmaps of some map regions of its current
     * dimension (the regions its map displays). {@code kind} is null when
     * the id is unknown to this side (ignored by the handler).
     */
    public record OverlayRegionRequestPayload(ChunkOverlayKind kind, ResourceLocation dimension, int[] rxs, int[] rzs)
            implements CustomPacketPayload {
        public static final Type<OverlayRegionRequestPayload> TYPE = new Type<>(Payloads.id("overlay_region_request"));

        public static final StreamCodec<FriendlyByteBuf, OverlayRegionRequestPayload> CODEC =
                StreamCodec.of(OverlayRegionRequestPayload::write, OverlayRegionRequestPayload::read);

        private static void write(FriendlyByteBuf buf, OverlayRegionRequestPayload p) {
            buf.writeUtf(p.kind.id(), KIND_ID_MAX_LENGTH);
            buf.writeResourceLocation(p.dimension);
            buf.writeVarInt(p.rxs.length);
            for (int i = 0; i < p.rxs.length; i++) {
                buf.writeVarInt(p.rxs[i]);
                buf.writeVarInt(p.rzs[i]);
            }
        }

        private static OverlayRegionRequestPayload read(FriendlyByteBuf buf) {
            ChunkOverlayKind kind = ChunkOverlayKind.fromId(buf.readUtf(KIND_ID_MAX_LENGTH));
            ResourceLocation dimension = buf.readResourceLocation();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_DECODED_REGIONS) {
                throw new DecoderException("Overlay region request too large: " + n);
            }

            int[] rxs = new int[n];
            int[] rzs = new int[n];
            for (int i = 0; i < n; i++) {
                rxs[i] = buf.readVarInt();
                rzs[i] = buf.readVarInt();
            }
            return new OverlayRegionRequestPayload(kind, dimension, rxs, rzs);
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- S2C: region bitmaps

    /**
     * Chunk bitmap of one region: bit {@code localZ * 32 + localX} set =
     * the chunk is flagged. {@code bits} is either empty (no chunk flagged:
     * 1 byte on the wire) or exactly {@link ChunkOverlayKind#REGION_WORDS} longs.
     */
    public record RegionBits(int rx, int rz, long[] bits) {}

    /**
     * Chunk bitmaps of several regions of one dimension: the answer to a
     * request, or the update of a forced-chunks region that changed.
     */
    public record OverlayRegionDataPayload(ChunkOverlayKind kind, ResourceLocation dimension, List<RegionBits> regions)
            implements CustomPacketPayload {
        public static final Type<OverlayRegionDataPayload> TYPE = new Type<>(Payloads.id("overlay_region_data"));

        public static final StreamCodec<FriendlyByteBuf, OverlayRegionDataPayload> CODEC =
                StreamCodec.of(OverlayRegionDataPayload::write, OverlayRegionDataPayload::read);

        private static void write(FriendlyByteBuf buf, OverlayRegionDataPayload p) {
            buf.writeUtf(p.kind.id(), KIND_ID_MAX_LENGTH);
            buf.writeResourceLocation(p.dimension);
            buf.writeVarInt(p.regions.size());
            for (RegionBits region : p.regions) {
                buf.writeVarInt(region.rx());
                buf.writeVarInt(region.rz());
                buf.writeVarInt(region.bits().length);
                for (long word : region.bits()) {
                    buf.writeLong(word);
                }
            }
        }

        private static OverlayRegionDataPayload read(FriendlyByteBuf buf) {
            ChunkOverlayKind kind = ChunkOverlayKind.fromId(buf.readUtf(KIND_ID_MAX_LENGTH));
            ResourceLocation dimension = buf.readResourceLocation();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_DECODED_REGIONS) {
                throw new DecoderException("Overlay region data too large: " + n);
            }

            List<RegionBits> regions = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int rx = buf.readVarInt();
                int rz = buf.readVarInt();
                int words = buf.readVarInt();
                if (words != 0 && words != ChunkOverlayKind.REGION_WORDS) {
                    throw new DecoderException("Bad overlay bitmap length: " + words);
                }

                long[] bits = new long[words];
                for (int w = 0; w < words; w++) {
                    bits[w] = buf.readLong();
                }
                regions.add(new RegionBits(rx, rz, bits));
            }
            return new OverlayRegionDataPayload(kind, dimension, regions);
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- registration

    /** Registers this group's payloads; called by {@link Payloads#register}. */
    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(
                OverlaySettingsPayload.TYPE,
                OverlaySettingsPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientOverlaySettings.accept(payload)));

        registrar.playToServer(
                OverlayRegionRequestPayload.TYPE,
                OverlayRegionRequestPayload.CODEC,
                (payload, ctx) ->
                        ctx.enqueueWork(() -> Payloads.Hooks.serverOverlayRegionRequest.accept(ctx.player(), payload)));

        registrar.playToClient(
                OverlayRegionDataPayload.TYPE,
                OverlayRegionDataPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientOverlayRegionData.accept(payload)));
    }
}
