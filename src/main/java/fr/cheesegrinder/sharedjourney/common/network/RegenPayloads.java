package fr.cheesegrinder.sharedjourney.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import org.jetbrains.annotations.NotNull;

/**
 * Map regeneration payloads (spec §5): run state, per-chunk progress
 * masks and the numeric progress counter of {@code /sj admin regen} and
 * {@code /sj admin rerender}. Handlers are dispatched through
 * {@link Payloads.Hooks} — see {@link Payloads} for the indirection
 * rationale.
 */
public final class RegenPayloads {

    private RegenPayloads() {}

    // ---------------------------------------------------------------- S2C: regen state

    /**
     * Map regeneration state (/sj admin regen). While active, clients veil
     * the chunks not yet re-rendered (see {@link RegenChunksPayload}) so
     * regenerated and stale areas can be told apart. Sent on start/end and
     * to players joining mid-regen.
     */
    public record RegenStatePayload(boolean active) implements CustomPacketPayload {
        public static final Type<RegenStatePayload> TYPE = new Type<>(Payloads.id("regen_state"));

        public static final StreamCodec<FriendlyByteBuf, RegenStatePayload> CODEC =
                StreamCodec.of((buf, p) -> buf.writeBoolean(p.active), buf -> new RegenStatePayload(buf.readBoolean()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- S2C: regen chunk masks

    /**
     * Per-chunk regen progress of one map region (32x32 chunks): bit
     * (localZ*32 + localX) set = chunk re-rendered. Pushed ~1x/s for the
     * regions the running regen touched since the last push; the veil is
     * lifted chunk by chunk on the clients' map.
     */
    public record RegenChunksPayload(ResourceLocation dimension, int rx, int rz, long[] mask)
            implements CustomPacketPayload {

        /** 1024 chunk bits = 16 longs. */
        public static final int MASK_WORDS = 16;

        public static final Type<RegenChunksPayload> TYPE = new Type<>(Payloads.id("regen_chunks"));

        public static final StreamCodec<FriendlyByteBuf, RegenChunksPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeResourceLocation(p.dimension);
                    buf.writeVarInt(p.rx);
                    buf.writeVarInt(p.rz);
                    for (int i = 0; i < MASK_WORDS; i++) {
                        buf.writeLong(p.mask[i]);
                    }
                },
                buf -> {
                    ResourceLocation dimension = buf.readResourceLocation();
                    int rx = buf.readVarInt();
                    int rz = buf.readVarInt();
                    long[] mask = new long[MASK_WORDS];
                    for (int i = 0; i < MASK_WORDS; i++) {
                        mask[i] = buf.readLong();
                    }
                    return new RegenChunksPayload(dimension, rx, rz, mask);
                });

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- S2C: regen progress counter

    /**
     * Numeric regen progress (chunks done / total), pushed ~1x/s. Sent to
     * every player during a full-map regen (/sj admin regen), or only to
     * the requesting player for a local re-render (/sj admin rerender).
     * {@code active=false} clears the client display when the run ends.
     */
    public record RegenProgressPayload(boolean active, int done, int total) implements CustomPacketPayload {
        public static final Type<RegenProgressPayload> TYPE = new Type<>(Payloads.id("regen_progress"));

        public static final StreamCodec<FriendlyByteBuf, RegenProgressPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.active);
                    buf.writeVarInt(p.done);
                    buf.writeVarInt(p.total);
                },
                buf -> new RegenProgressPayload(buf.readBoolean(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- registration

    /** Registers this group's payloads; called by {@link Payloads#register}. */
    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(
                RegenStatePayload.TYPE,
                RegenStatePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientRegenState.accept(payload)));

        registrar.playToClient(
                RegenChunksPayload.TYPE,
                RegenChunksPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientRegenChunks.accept(payload)));

        registrar.playToClient(
                RegenProgressPayload.TYPE,
                RegenProgressPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientRegenProgress.accept(payload)));
    }
}
