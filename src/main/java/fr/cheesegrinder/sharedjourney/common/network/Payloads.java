package fr.cheesegrinder.sharedjourney.common.network;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Network packets (spec §5). The common package knows neither the client nor
 * the server: handlers are injected by the entry points ({@code SharedJourney}
 * and {@code SharedJourneyClient}) through the Hooks.* fields, which avoids
 * any circular dependency between packages. Deliberate design choice — do not
 * refactor it away.
 */
public final class Payloads {

    private Payloads() {}

    /** Handler indirection, wired by the entry points at startup. */
    public static final class Hooks {
        public static Consumer<LayerSettingsPayload> clientLayerSettings = p -> {};
        public static Consumer<RegionDataPayload> clientRegionData = p -> {};
        public static Consumer<HiddenPlayersPayload> clientHiddenPlayers = p -> {};
        public static Consumer<PlayerPositionsPayload> clientPlayerPositions = p -> {};
        public static Consumer<RegenStatePayload> clientRegenState = p -> {};
        public static Consumer<RegenChunksPayload> clientRegenChunks = p -> {};
        public static Consumer<TrainPathPayload> clientTrainPath = p -> {};
        public static Consumer<PublicWaypointPayload> clientPublicWaypoint = p -> {};
        public static Consumer<PublicWaypointRemovePayload> clientPublicWaypointRemove = p -> {};
        public static BiConsumer<Player, TrainPathRequestPayload> serverTrainPathRequest = (pl, p) -> {};
        public static BiConsumer<Player, PublicWaypointPayload> serverPublicWaypoint = (pl, p) -> {};
        public static BiConsumer<Player, PublicWaypointRemovePayload> serverPublicWaypointRemove = (pl, p) -> {};
        public static BiConsumer<Player, RegionRequestPayload> serverRegionRequest = (pl, p) -> {};
        public static BiConsumer<Player, ClientIndexPayload> serverClientIndex = (pl, p) -> {};
        public static BiConsumer<Player, MapVisibilityPayload> serverMapVisibility = (pl, p) -> {};
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SharedJourneyConstants.MOD_ID, path);
    }

    // ---------------------------------------------------------------- S2C: active layers

    /** Active layers per dimension + CAVE bands (sent at login and on every reload). */
    public record LayerSettingsPayload(
            Map<ResourceLocation, List<MapLayer>> layersByDim, List<Integer> caveBands, int radarMaxRadius)
            implements CustomPacketPayload {
        public static final Type<LayerSettingsPayload> TYPE = new Type<>(id("layer_settings"));

        public static final StreamCodec<FriendlyByteBuf, LayerSettingsPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.layersByDim.size());
                    p.layersByDim.forEach((dim, layers) -> {
                        buf.writeResourceLocation(dim);
                        buf.writeVarInt(layers.size());
                        layers.forEach(l -> buf.writeVarInt(l.ordinal()));
                    });
                    buf.writeVarInt(p.caveBands.size());
                    p.caveBands.forEach(buf::writeVarInt);
                    buf.writeVarInt(p.radarMaxRadius);
                },
                buf -> {
                    int n = buf.readVarInt();
                    Map<ResourceLocation, List<MapLayer>> map = new HashMap<>();
                    for (int i = 0; i < n; i++) {
                        ResourceLocation dim = buf.readResourceLocation();
                        int m = buf.readVarInt();
                        List<MapLayer> layers = new ArrayList<>(m);
                        for (int j = 0; j < m; j++) {
                            layers.add(MapLayer.values()[buf.readVarInt()]);
                        }

                        map.put(dim, layers);
                    }
                    int nb = buf.readVarInt();
                    List<Integer> bands = new ArrayList<>(nb);
                    for (int i = 0; i < nb; i++) {
                        bands.add(buf.readVarInt());
                    }

                    return new LayerSettingsPayload(map, bands, buf.readVarInt());
                });

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- S2C: region data

    /** Region PNG fragment; the full stream is GZIP-compressed (spec §5.3). */
    public record RegionDataPayload(RegionKey key, long version, int part, int totalParts, byte[] data)
            implements CustomPacketPayload {
        public static final Type<RegionDataPayload> TYPE = new Type<>(id("region_data"));

        public static final StreamCodec<FriendlyByteBuf, RegionDataPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    RegionKey.STREAM_CODEC.encode(buf, p.key);
                    buf.writeVarLong(p.version);
                    buf.writeVarInt(p.part);
                    buf.writeVarInt(p.totalParts);
                    buf.writeByteArray(p.data);
                },
                buf -> new RegionDataPayload(
                        RegionKey.STREAM_CODEC.decode(buf),
                        buf.readVarLong(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readByteArray()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- C2S: index handshake

    /**
     * Handshake (spec §5.1): on connection, the client sends a summary of its
     * local index.json (keys + timestamps, serialized and GZIPped). The server
     * computes the delta and only sends what is missing or has changed.
     */
    public record ClientIndexPayload(byte[] gzippedIndex) implements CustomPacketPayload {
        public static final Type<ClientIndexPayload> TYPE = new Type<>(id("client_index"));

        public static final StreamCodec<FriendlyByteBuf, ClientIndexPayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeByteArray(p.gzippedIndex), buf -> new ClientIndexPayload(buf.readByteArray()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** Compact serialization: "indexKey=timestamp" lines, GZIPped. */
        public static byte[] encodeIndex(Map<RegionKey, Long> entries) {
            StringBuilder sb = new StringBuilder(entries.size() * 48);
            entries.forEach(
                    (k, v) -> sb.append(k.indexKey()).append('=').append(v).append('\n'));

            try (var bos = new ByteArrayOutputStream();
                    var gz = new GZIPOutputStream(bos)) {
                gz.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                gz.finish();

                return bos.toByteArray();
            } catch (IOException e) {
                return new byte[0];
            }
        }

        public Map<RegionKey, Long> decodeIndex(int maxEntries) {
            Map<RegionKey, Long> out = new HashMap<>();

            try (var gis = new GZIPInputStream(new ByteArrayInputStream(gzippedIndex))) {
                String text = new String(gis.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    if (out.size() >= maxEntries) {
                        break;
                    }

                    int eq = line.lastIndexOf('=');
                    if (eq <= 0) {
                        continue;
                    }

                    RegionKey key = RegionKey.fromIndexKey(line.substring(0, eq));
                    if (key == null) {
                        continue;
                    }

                    try {
                        out.put(key, Long.parseLong(line.substring(eq + 1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- C2S: region request

    /** Client requests regions (fullscreen map) with the version it owns (-1 if none). */
    public record RegionRequestPayload(List<RegionKey> keys, List<Long> knownVersions) implements CustomPacketPayload {
        public static final Type<RegionRequestPayload> TYPE = new Type<>(id("region_request"));

        public static final StreamCodec<FriendlyByteBuf, RegionRequestPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.keys.size());
                    for (int i = 0; i < p.keys.size(); i++) {
                        RegionKey.STREAM_CODEC.encode(buf, p.keys.get(i));
                        buf.writeVarLong(p.knownVersions.get(i));
                    }
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<RegionKey> keys = new ArrayList<>(n);
                    List<Long> versions = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        keys.add(RegionKey.STREAM_CODEC.decode(buf));
                        versions.add(buf.readVarLong());
                    }
                    return new RegionRequestPayload(keys, versions);
                });

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- C2S/S2C: player visibility

    /** Player preference: be hidden from the other players' map. */
    public record MapVisibilityPayload(boolean hidden) implements CustomPacketPayload {
        public static final Type<MapVisibilityPayload> TYPE = new Type<>(id("map_visibility"));

        public static final StreamCodec<FriendlyByteBuf, MapVisibilityPayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.hidden), buf -> new MapVisibilityPayload(buf.readBoolean()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** List of players hidden from the map, broadcast to every client. */
    public record HiddenPlayersPayload(List<UUID> hidden) implements CustomPacketPayload {
        public static final Type<HiddenPlayersPayload> TYPE = new Type<>(id("hidden_players"));

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

    /** Positions (dimension, x, z) of every non-hidden player, ~1x/s. */
    public record PlayerPositionsPayload(List<PlayerPos> players) implements CustomPacketPayload {

        /** A player's position on the map. */
        public record PlayerPos(UUID id, ResourceLocation dimension, double x, double z) {}

        public static final Type<PlayerPositionsPayload> TYPE = new Type<>(id("player_positions"));

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

    // ---------------------------------------------------------------- S2C: regen state

    /**
     * Map regeneration state (/sj admin regen). While active, clients veil
     * the chunks not yet re-rendered (see {@link RegenChunksPayload}) so
     * regenerated and stale areas can be told apart. Sent on start/end and
     * to players joining mid-regen.
     */
    public record RegenStatePayload(boolean active) implements CustomPacketPayload {
        public static final Type<RegenStatePayload> TYPE = new Type<>(id("regen_state"));

        public static final StreamCodec<FriendlyByteBuf, RegenStatePayload> CODEC =
                StreamCodec.of((buf, p) -> buf.writeBoolean(p.active), buf -> new RegenStatePayload(buf.readBoolean()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

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

        public static final Type<RegenChunksPayload> TYPE = new Type<>(id("regen_chunks"));

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

    // ---------------------------------------------------------------- C2S/S2C: train path (Create bridge)

    /** Client asks for the navigation path of a train hovered on the map. */
    public record TrainPathRequestPayload(UUID trainId) implements CustomPacketPayload {
        public static final Type<TrainPathRequestPayload> TYPE = new Type<>(id("train_path_request"));

        public static final StreamCodec<FriendlyByteBuf, TrainPathRequestPayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUUID(p.trainId), buf -> new TrainPathRequestPayload(buf.readUUID()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Remaining navigation path of a train (Create), as a polyline of block
     * coordinates in the requesting player's dimension. Empty when the
     * train no longer exists or is not navigating.
     */
    public record TrainPathPayload(UUID trainId, int[] xs, int[] zs) implements CustomPacketPayload {
        public static final Type<TrainPathPayload> TYPE = new Type<>(id("train_path"));

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

    // ---------------------------------------------------------------- C2S/S2C: public waypoints

    /**
     * Public waypoint upsert, server-authoritative: a client creates or
     * edits one (C2S), the server persists it in the world folder and
     * broadcasts it back (S2C) to every player — including at login (full
     * send). Visibility is deliberately absent: showing or hiding a public
     * waypoint is a per-client choice, never shared.
     */
    public record PublicWaypointPayload(
            UUID id, String name, ResourceLocation dimension, int x, int y, int z, int colorRgb)
            implements CustomPacketPayload {
        // Payloads.id: the bare call would resolve to the id() accessor.
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
                },
                buf -> new PublicWaypointPayload(
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

    // ---------------------------------------------------------------- registration

    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar =
                event.registrar(SharedJourneyConstants.MOD_ID).versioned("3");

        registrar.playToClient(
                LayerSettingsPayload.TYPE,
                LayerSettingsPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.clientLayerSettings.accept(payload)));

        registrar.playToClient(
                RegionDataPayload.TYPE,
                RegionDataPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.clientRegionData.accept(payload)));

        registrar.playToServer(
                RegionRequestPayload.TYPE,
                RegionRequestPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.serverRegionRequest.accept(ctx.player(), payload)));

        registrar.playToServer(
                ClientIndexPayload.TYPE,
                ClientIndexPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.serverClientIndex.accept(ctx.player(), payload)));

        registrar.playToServer(
                MapVisibilityPayload.TYPE,
                MapVisibilityPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.serverMapVisibility.accept(ctx.player(), payload)));

        registrar.playToClient(
                HiddenPlayersPayload.TYPE,
                HiddenPlayersPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.clientHiddenPlayers.accept(payload)));

        registrar.playToClient(
                PlayerPositionsPayload.TYPE,
                PlayerPositionsPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.clientPlayerPositions.accept(payload)));

        registrar.playToClient(
                RegenStatePayload.TYPE,
                RegenStatePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.clientRegenState.accept(payload)));

        registrar.playToClient(
                RegenChunksPayload.TYPE,
                RegenChunksPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.clientRegenChunks.accept(payload)));

        registrar.playToServer(
                TrainPathRequestPayload.TYPE,
                TrainPathRequestPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.serverTrainPathRequest.accept(ctx.player(), payload)));

        registrar.playToClient(
                TrainPathPayload.TYPE,
                TrainPathPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.clientTrainPath.accept(payload)));

        // Bidirectional: the same payload is a client edit request (C2S)
        // and the server's authoritative broadcast (S2C).
        registrar.playBidirectional(
                PublicWaypointPayload.TYPE,
                PublicWaypointPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.flow().isServerbound()) {
                        Hooks.serverPublicWaypoint.accept(ctx.player(), payload);
                    } else {
                        Hooks.clientPublicWaypoint.accept(payload);
                    }
                }));

        registrar.playBidirectional(
                PublicWaypointRemovePayload.TYPE,
                PublicWaypointRemovePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.flow().isServerbound()) {
                        Hooks.serverPublicWaypointRemove.accept(ctx.player(), payload);
                    } else {
                        Hooks.clientPublicWaypointRemove.accept(payload);
                    }
                }));
    }
}
