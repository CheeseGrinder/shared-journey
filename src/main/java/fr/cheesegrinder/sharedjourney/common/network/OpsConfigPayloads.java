package fr.cheesegrinder.sharedjourney.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Ops server config payloads (spec §5): snapshot request and the editable
 * config carried both ways (S2C authoritative snapshot/echo, C2S sanitized
 * apply). Handlers are dispatched through {@link Payloads.Hooks} — see
 * {@link Payloads} for the indirection rationale.
 */
public final class OpsConfigPayloads {

    private OpsConfigPayloads() {}

    // ---------------------------------------------------------------- C2S: snapshot request

    /**
     * Ops-only (permission level 2+) request for the editable server config
     * snapshot, sent when the fullscreen settings screen opens its server
     * tab. The server answers with an {@link OpsConfigPayload} — or stays
     * silent for a non-op sender.
     */
    public record OpsConfigRequestPayload() implements CustomPacketPayload {
        public static final Type<OpsConfigRequestPayload> TYPE = new Type<>(Payloads.id("ops_config_request"));

        public static final StreamCodec<FriendlyByteBuf, OpsConfigRequestPayload> CODEC =
                StreamCodec.of((buf, p) -> {}, buf -> new OpsConfigRequestPayload());

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- C2S/S2C: editable config

    /**
     * Editable server config, ops (permission level 2+) only.
     * S2C: authoritative snapshot answering {@link OpsConfigRequestPayload}
     * (and echoed back after an apply). C2S: apply request — the server
     * verifies the sender's permission, sanitizes every value into its
     * config bounds, persists the spec and re-broadcasts the active layers
     * to every player.
     */
    public record OpsConfigPayload(
            List<String> defaultLayers,
            List<String> sharedLayers,
            List<Integer> caveBands,
            int pushRadiusRegions,
            int maxKbPerSecondPerPlayer,
            int syncRateTicks,
            boolean allowOnDemandRequests,
            int radarMaxRadius,
            boolean deathWaypointsEnabled,
            boolean waypointStorageServer,
            String hiddenAreaPolicy,
            int quarantineRadiusChunks,
            int quarantineDrainMinutes)
            implements CustomPacketPayload {
        public static final Type<OpsConfigPayload> TYPE = new Type<>(Payloads.id("ops_config"));

        public static final StreamCodec<FriendlyByteBuf, OpsConfigPayload> CODEC =
                StreamCodec.of(OpsConfigPayload::write, OpsConfigPayload::read);

        private static void write(FriendlyByteBuf buf, OpsConfigPayload p) {
            writeStringList(buf, p.defaultLayers);
            writeStringList(buf, p.sharedLayers);
            buf.writeVarInt(p.caveBands.size());
            p.caveBands.forEach(buf::writeVarInt);
            buf.writeVarInt(p.pushRadiusRegions);
            buf.writeVarInt(p.maxKbPerSecondPerPlayer);
            buf.writeVarInt(p.syncRateTicks);
            buf.writeBoolean(p.allowOnDemandRequests);
            buf.writeVarInt(p.radarMaxRadius);
            buf.writeBoolean(p.deathWaypointsEnabled);
            buf.writeBoolean(p.waypointStorageServer);
            buf.writeUtf(p.hiddenAreaPolicy, 32);
            buf.writeVarInt(p.quarantineRadiusChunks);
            buf.writeVarInt(p.quarantineDrainMinutes);
        }

        private static OpsConfigPayload read(FriendlyByteBuf buf) {
            List<String> defaultLayers = readStringList(buf);
            List<String> sharedLayers = readStringList(buf);
            int nb = buf.readVarInt();
            List<Integer> caveBands = new ArrayList<>(nb);
            for (int i = 0; i < nb; i++) {
                caveBands.add(buf.readVarInt());
            }

            return new OpsConfigPayload(
                    defaultLayers,
                    sharedLayers,
                    caveBands,
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readUtf(32),
                    buf.readVarInt(),
                    buf.readVarInt());
        }

        private static void writeStringList(FriendlyByteBuf buf, List<String> list) {
            buf.writeVarInt(list.size());
            for (String s : list) {
                buf.writeUtf(s, 256);
            }
        }

        private static List<String> readStringList(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            List<String> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(buf.readUtf(256));
            }

            return list;
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
                OpsConfigRequestPayload.TYPE,
                OpsConfigRequestPayload.CODEC,
                (payload, ctx) ->
                        ctx.enqueueWork(() -> Payloads.Hooks.serverOpsConfigRequest.accept(ctx.player(), payload)));

        // Bidirectional: S2C authoritative snapshot, C2S ops apply request.
        registrar.playBidirectional(
                OpsConfigPayload.TYPE,
                OpsConfigPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.flow().isServerbound()) {
                        Payloads.Hooks.serverOpsConfigUpdate.accept(ctx.player(), payload);
                    } else {
                        Payloads.Hooks.clientOpsConfig.accept(payload);
                    }
                }));
    }
}
