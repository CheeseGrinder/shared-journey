package fr.cheesegrinder.sharedjourney.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Player visibility payloads (spec §5): the "hide me from the map"
 * preference, the resulting hidden-player broadcast and the periodic
 * player position push. Handlers are dispatched through
 * {@link Payloads.Hooks} — see {@link Payloads} for the indirection
 * rationale.
 */
public final class PlayerVisibilityPayloads {

    private PlayerVisibilityPayloads() {}

    // ---------------------------------------------------------------- C2S: visibility preference

    /** Player preference: be hidden from the other players' map. */
    public record MapVisibilityPayload(boolean hidden) implements CustomPacketPayload {
        public static final Type<MapVisibilityPayload> TYPE = new Type<>(Payloads.id("map_visibility"));

        public static final StreamCodec<FriendlyByteBuf, MapVisibilityPayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.hidden), buf -> new MapVisibilityPayload(buf.readBoolean()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- S2C: hidden players

    /** List of players hidden from the map, broadcast to every client. */
    public record HiddenPlayersPayload(List<UUID> hidden) implements CustomPacketPayload {
        public static final Type<HiddenPlayersPayload> TYPE = new Type<>(Payloads.id("hidden_players"));

        public static final StreamCodec<FriendlyByteBuf, HiddenPlayersPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.hidden.size());
                    for (UUID id : p.hidden) {
                        buf.writeUUID(id);
                    }
                },
                buf -> {
                    int count = buf.readVarInt();
                    List<UUID> ids = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        ids.add(buf.readUUID());
                    }
                    return new HiddenPlayersPayload(ids);
                });

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- S2C: player positions

    /** Positions (dimension, x, z) of every non-hidden player, ~1x/s. */
    public record PlayerPositionsPayload(List<PlayerPos> players) implements CustomPacketPayload {

        /** A player's position on the map. */
        public record PlayerPos(UUID id, ResourceLocation dimension, double x, double z) {}

        public static final Type<PlayerPositionsPayload> TYPE = new Type<>(Payloads.id("player_positions"));

        public static final StreamCodec<FriendlyByteBuf, PlayerPositionsPayload> CODEC =
                StreamCodec.of(PlayerPositionsPayload::write, PlayerPositionsPayload::read);

        private static void write(FriendlyByteBuf buf, PlayerPositionsPayload p) {
            buf.writeVarInt(p.players.size());
            for (PlayerPos pos : p.players) {
                buf.writeUUID(pos.id);
                buf.writeResourceLocation(pos.dimension);
                buf.writeDouble(pos.x);
                buf.writeDouble(pos.z);
            }
        }

        private static PlayerPositionsPayload read(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            List<PlayerPos> players = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                players.add(
                        new PlayerPos(buf.readUUID(), buf.readResourceLocation(), buf.readDouble(), buf.readDouble()));
            }
            return new PlayerPositionsPayload(players);
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
                MapVisibilityPayload.TYPE,
                MapVisibilityPayload.CODEC,
                (payload, ctx) ->
                        ctx.enqueueWork(() -> Payloads.Hooks.serverMapVisibility.accept(ctx.player(), payload)));

        registrar.playToClient(
                HiddenPlayersPayload.TYPE,
                HiddenPlayersPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientHiddenPlayers.accept(payload)));

        registrar.playToClient(
                PlayerPositionsPayload.TYPE,
                PlayerPositionsPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientPlayerPositions.accept(payload)));
    }
}
