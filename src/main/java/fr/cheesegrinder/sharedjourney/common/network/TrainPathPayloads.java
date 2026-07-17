package fr.cheesegrinder.sharedjourney.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Train path payloads (spec §5, Create bridge): the client asks for the
 * remaining navigation route of a train hovered/followed on the map, the
 * server answers with the simulated polyline. Handlers are dispatched
 * through {@link Payloads.Hooks} — see {@link Payloads} for the
 * indirection rationale.
 */
public final class TrainPathPayloads {

    private TrainPathPayloads() {}

    // ---------------------------------------------------------------- C2S: path request

    /** Client asks for the navigation path of a train hovered on the map. */
    public record TrainPathRequestPayload(UUID trainId) implements CustomPacketPayload {
        public static final Type<TrainPathRequestPayload> TYPE = new Type<>(Payloads.id("train_path_request"));

        public static final StreamCodec<FriendlyByteBuf, TrainPathRequestPayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUUID(p.trainId), buf -> new TrainPathRequestPayload(buf.readUUID()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- S2C: path polyline

    /**
     * Remaining navigation path of a train (Create), as a polyline of block
     * coordinates in the requesting player's dimension. Empty when the
     * train no longer exists or is not navigating.
     */
    public record TrainPathPayload(UUID trainId, int[] xs, int[] zs) implements CustomPacketPayload {
        public static final Type<TrainPathPayload> TYPE = new Type<>(Payloads.id("train_path"));

        public static final StreamCodec<FriendlyByteBuf, TrainPathPayload> CODEC =
                StreamCodec.of(TrainPathPayload::write, TrainPathPayload::read);

        private static void write(FriendlyByteBuf buf, TrainPathPayload p) {
            buf.writeUUID(p.trainId);
            buf.writeVarInt(p.xs.length);
            for (int i = 0; i < p.xs.length; i++) {
                buf.writeVarInt(p.xs[i]);
                buf.writeVarInt(p.zs[i]);
            }
        }

        private static TrainPathPayload read(FriendlyByteBuf buf) {
            UUID trainId = buf.readUUID();
            int n = buf.readVarInt();
            int[] xs = new int[n];
            int[] zs = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = buf.readVarInt();
                zs[i] = buf.readVarInt();
            }
            return new TrainPathPayload(trainId, xs, zs);
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- registration

    /** Registers this group's payloads; called by {@link Payloads#register}. */
    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(
                TrainPathRequestPayload.TYPE,
                TrainPathRequestPayload.CODEC,
                (payload, ctx) ->
                        ctx.enqueueWork(() -> Payloads.Hooks.serverTrainPathRequest.accept(ctx.player(), payload)));

        registrar.playToClient(
                TrainPathPayload.TYPE,
                TrainPathPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientTrainPath.accept(payload)));
    }
}
