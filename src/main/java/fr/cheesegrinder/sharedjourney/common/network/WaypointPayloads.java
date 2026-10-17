package fr.cheesegrinder.sharedjourney.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Waypoint payloads (spec §5): public waypoints (shared, broadcast),
 * per-player private waypoints (server-persisted, point-to-point) and
 * banner waypoints and automatic waypoints (both server-detected, S2C
 * only). Handlers are dispatched
 * through {@link Payloads.Hooks} — see {@link Payloads} for the
 * indirection rationale.
 *
 * <p>Inside the records, {@code Payloads.id} must stay qualified: the
 * bare call would resolve to the record's own {@code id()} accessor.
 */
public final class WaypointPayloads {

    private WaypointPayloads() {}

    // ---------------------------------------------------------------- C2S/S2C: public waypoints

    /**
     * Public waypoint upsert, server-authoritative: a client creates or
     * edits one (C2S), the server persists it in the world folder and
     * broadcasts it back (S2C) to every player — including at login (full
     * send). Visibility is deliberately absent: showing or hiding a public
     * waypoint is a per-client choice, never shared. {@code converted} is
     * the shared Overworld / Nether link flag (see {@code Waypoint.Conversion}):
     * it is shared like the position.
     */
    public record PublicWaypointPayload(
            UUID id, String name, ResourceLocation dimension, int x, int y, int z, int colorRgb, boolean converted)
            implements CustomPacketPayload {
        public static final Type<PublicWaypointPayload> TYPE = new Type<>(Payloads.id("public_waypoint"));

        public static final StreamCodec<FriendlyByteBuf, PublicWaypointPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.id);
                    buf.writeUtf(p.name, 48);
                    buf.writeResourceLocation(p.dimension);
                    buf.writeVarInt(p.x);
                    buf.writeVarInt(p.y);
                    buf.writeVarInt(p.z);
                    buf.writeInt(p.colorRgb);
                    buf.writeBoolean(p.converted);
                },
                buf -> new PublicWaypointPayload(
                        buf.readUUID(),
                        buf.readUtf(48),
                        buf.readResourceLocation(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readInt(),
                        buf.readBoolean()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Public waypoint removal (C2S request, S2C broadcast). */
    public record PublicWaypointRemovePayload(UUID id) implements CustomPacketPayload {
        public static final Type<PublicWaypointRemovePayload> TYPE = new Type<>(Payloads.id("public_waypoint_remove"));

        public static final StreamCodec<FriendlyByteBuf, PublicWaypointRemovePayload> CODEC =
                StreamCodec.of((buf, p) -> buf.writeUUID(p.id), buf -> new PublicWaypointRemovePayload(buf.readUUID()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- C2S/S2C: player waypoints

    /**
     * Private waypoint upsert, server-authoritative (opt-in via
     * {@code waypoints.waypointStorage=SERVER}, the default): a client
     * creates or edits one of ITS OWN waypoints (C2S), the server persists
     * it under that player only and echoes it back (S2C) to that SAME
     * player — never broadcast to anyone else, unlike
     * {@link PublicWaypointPayload}. Covers DIMENSION waypoints only:
     * TEMP ones always stay client-local. Visibility is deliberately
     * absent, same rationale as public waypoints: a per-client choice.
     * {@code converted} is the Overworld / Nether link flag (see
     * {@code Waypoint.Conversion}), stored with the waypoint.
     */
    public record PlayerWaypointPayload(
            UUID id,
            String name,
            ResourceLocation dimension,
            int x,
            int y,
            int z,
            int colorRgb,
            String group,
            boolean converted)
            implements CustomPacketPayload {
        public static final Type<PlayerWaypointPayload> TYPE = new Type<>(Payloads.id("player_waypoint"));

        public static final StreamCodec<FriendlyByteBuf, PlayerWaypointPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.id);
                    buf.writeUtf(p.name, 48);
                    buf.writeResourceLocation(p.dimension);
                    buf.writeVarInt(p.x);
                    buf.writeVarInt(p.y);
                    buf.writeVarInt(p.z);
                    buf.writeInt(p.colorRgb);
                    buf.writeUtf(p.group, 32);
                    buf.writeBoolean(p.converted);
                },
                buf -> new PlayerWaypointPayload(
                        buf.readUUID(),
                        buf.readUtf(48),
                        buf.readResourceLocation(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readInt(),
                        buf.readUtf(32),
                        buf.readBoolean()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Private waypoint removal (C2S request, S2C echo to the same player). */
    public record PlayerWaypointRemovePayload(UUID id) implements CustomPacketPayload {
        public static final Type<PlayerWaypointRemovePayload> TYPE = new Type<>(Payloads.id("player_waypoint_remove"));

        public static final StreamCodec<FriendlyByteBuf, PlayerWaypointRemovePayload> CODEC =
                StreamCodec.of((buf, p) -> buf.writeUUID(p.id), buf -> new PlayerWaypointRemovePayload(buf.readUUID()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- S2C: banner waypoints

    /**
     * Banner waypoint upsert, S2C only: unlike public/player waypoints,
     * clients never request these — the server alone detects them (a
     * NAMED banner placed in the world) and broadcasts to everyone. Read-
     * only client-side, like a bridged mod's; visibility is deliberately
     * absent, same rationale as public waypoints.
     */
    public record BannerWaypointPayload(
            UUID id, String name, ResourceLocation dimension, int x, int y, int z, int colorRgb)
            implements CustomPacketPayload {
        public static final Type<BannerWaypointPayload> TYPE = new Type<>(Payloads.id("banner_waypoint"));

        public static final StreamCodec<FriendlyByteBuf, BannerWaypointPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.id);
                    buf.writeUtf(p.name, 48);
                    buf.writeResourceLocation(p.dimension);
                    buf.writeVarInt(p.x);
                    buf.writeVarInt(p.y);
                    buf.writeVarInt(p.z);
                    buf.writeInt(p.colorRgb);
                },
                buf -> new BannerWaypointPayload(
                        buf.readUUID(),
                        buf.readUtf(48),
                        buf.readResourceLocation(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readInt()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Banner waypoint removal (S2C only): the banner was broken. */
    public record BannerWaypointRemovePayload(UUID id) implements CustomPacketPayload {
        public static final Type<BannerWaypointRemovePayload> TYPE = new Type<>(Payloads.id("banner_waypoint_remove"));

        public static final StreamCodec<FriendlyByteBuf, BannerWaypointRemovePayload> CODEC =
                StreamCodec.of((buf, p) -> buf.writeUUID(p.id), buf -> new BannerWaypointRemovePayload(buf.readUUID()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- S2C: automatic waypoints

    /**
     * Automatic waypoint upsert (bed, nether portal, respawn anchor), S2C
     * only: the server alone detects them. Shared kinds are broadcast to
     * everyone, private ones are sent to their owner only. The name and
     * name is deliberately absent: the client derives it (translated) from
     * the kind. {@code color} is an RGB override (a bed's dye colour), or
     * {@code -1} for the kind's own colour. Visibility is a per-client
     * choice, never shared.
     */
    public record AutoWaypointPayload(UUID id, String kind, ResourceLocation dimension, int x, int y, int z, int color)
            implements CustomPacketPayload {
        public static final Type<AutoWaypointPayload> TYPE = new Type<>(Payloads.id("auto_waypoint"));

        public static final StreamCodec<FriendlyByteBuf, AutoWaypointPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.id);
                    buf.writeUtf(p.kind, 16);
                    buf.writeResourceLocation(p.dimension);
                    buf.writeVarInt(p.x);
                    buf.writeVarInt(p.y);
                    buf.writeVarInt(p.z);
                    buf.writeInt(p.color);
                },
                buf -> new AutoWaypointPayload(
                        buf.readUUID(),
                        buf.readUtf(16),
                        buf.readResourceLocation(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readInt()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Automatic waypoint removal (S2C only): the block is gone, or the kind was disabled. */
    public record AutoWaypointRemovePayload(UUID id) implements CustomPacketPayload {
        public static final Type<AutoWaypointRemovePayload> TYPE = new Type<>(Payloads.id("auto_waypoint_remove"));

        public static final StreamCodec<FriendlyByteBuf, AutoWaypointRemovePayload> CODEC =
                StreamCodec.of((buf, p) -> buf.writeUUID(p.id), buf -> new AutoWaypointRemovePayload(buf.readUUID()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- registration

    /** Registers this group's payloads; called by {@link Payloads#register}. */
    static void register(PayloadRegistrar registrar) {
        // Bidirectional: the same payload is a client edit request (C2S)
        // and the server's authoritative broadcast (S2C).
        registrar.playBidirectional(
                PublicWaypointPayload.TYPE,
                PublicWaypointPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.flow().isServerbound()) {
                        Payloads.Hooks.serverPublicWaypoint.accept(ctx.player(), payload);
                    } else {
                        Payloads.Hooks.clientPublicWaypoint.accept(payload);
                    }
                }));

        registrar.playBidirectional(
                PublicWaypointRemovePayload.TYPE,
                PublicWaypointRemovePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.flow().isServerbound()) {
                        Payloads.Hooks.serverPublicWaypointRemove.accept(ctx.player(), payload);
                    } else {
                        Payloads.Hooks.clientPublicWaypointRemove.accept(payload);
                    }
                }));

        // Bidirectional: C2S edit request, S2C echo to that same player
        // only (never broadcast — private per-player waypoints).
        registrar.playBidirectional(
                PlayerWaypointPayload.TYPE,
                PlayerWaypointPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.flow().isServerbound()) {
                        Payloads.Hooks.serverPlayerWaypoint.accept(ctx.player(), payload);
                    } else {
                        Payloads.Hooks.clientPlayerWaypoint.accept(payload);
                    }
                }));

        registrar.playBidirectional(
                PlayerWaypointRemovePayload.TYPE,
                PlayerWaypointRemovePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.flow().isServerbound()) {
                        Payloads.Hooks.serverPlayerWaypointRemove.accept(ctx.player(), payload);
                    } else {
                        Payloads.Hooks.clientPlayerWaypointRemove.accept(payload);
                    }
                }));

        registrar.playToClient(
                BannerWaypointPayload.TYPE,
                BannerWaypointPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientBannerWaypoint.accept(payload)));

        registrar.playToClient(
                BannerWaypointRemovePayload.TYPE,
                BannerWaypointRemovePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientBannerWaypointRemove.accept(payload)));

        registrar.playToClient(
                AutoWaypointPayload.TYPE,
                AutoWaypointPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientAutoWaypoint.accept(payload)));

        registrar.playToClient(
                AutoWaypointRemovePayload.TYPE,
                AutoWaypointRemovePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientAutoWaypointRemove.accept(payload)));
    }
}
