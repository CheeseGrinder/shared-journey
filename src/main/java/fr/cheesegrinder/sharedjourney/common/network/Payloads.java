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
        public static Consumer<MapInfoChunkPayload> clientMapInfoChunk = p -> {};
        public static Consumer<HiddenPlayersPayload> clientHiddenPlayers = p -> {};
        public static Consumer<PlayerPositionsPayload> clientPlayerPositions = p -> {};
        public static Consumer<RegenStatePayload> clientRegenState = p -> {};
        public static Consumer<RegenChunksPayload> clientRegenChunks = p -> {};
        public static BiConsumer<Player, RegionRequestPayload> serverRegionRequest = (pl, p) -> {};
        public static BiConsumer<Player, ClientIndexPayload> serverClientIndex = (pl, p) -> {};
        public static BiConsumer<Player, MapInfoRequestPayload> serverMapInfoRequest = (pl, p) -> {};
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

    // ---------------------------------------------------------------- C2S/S2C: hover info

    /** Client requests the info (biome, block, Y) of a column hovered on the map. */
    public record MapInfoRequestPayload(int x, int z) implements CustomPacketPayload {
        public static final Type<MapInfoRequestPayload> TYPE = new Type<>(id("map_info_request"));

        public static final StreamCodec<FriendlyByteBuf, MapInfoRequestPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeInt(p.x);
                    buf.writeInt(p.z);
                },
                buf -> new MapInfoRequestPayload(buf.readInt(), buf.readInt()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Hover info for a WHOLE chunk (256 columns): heights, surface block per
     * column and biome per 4x4 cell, palettized (~1 KB). A single response
     * makes hovering instantaneous over the whole chunk.
     */
    public record MapInfoChunkPayload(
            int chunkX,
            int chunkZ,
            short[] heights,
            byte[] blockIdx,
            List<String> blockPalette,
            byte[] biomeIdx,
            List<String> biomePalette)
            implements CustomPacketPayload {

        public static final int COLUMNS = 256;
        public static final int BIOME_CELLS = 16;

        public static final Type<MapInfoChunkPayload> TYPE = new Type<>(id("map_info_chunk"));

        public static final StreamCodec<FriendlyByteBuf, MapInfoChunkPayload> CODEC =
                StreamCodec.of(MapInfoChunkPayload::write, MapInfoChunkPayload::read);

        private static void write(FriendlyByteBuf buf, MapInfoChunkPayload p) {
            buf.writeVarInt(p.chunkX);
            buf.writeVarInt(p.chunkZ);
            for (short h : p.heights) {
                buf.writeShort(h);
            }

            buf.writeBytes(p.blockIdx);
            writePalette(buf, p.blockPalette);
            buf.writeBytes(p.biomeIdx);
            writePalette(buf, p.biomePalette);
        }

        private static MapInfoChunkPayload read(FriendlyByteBuf buf) {
            int cx = buf.readVarInt();
            int cz = buf.readVarInt();
            short[] heights = new short[COLUMNS];
            for (int i = 0; i < COLUMNS; i++) {
                heights[i] = buf.readShort();
            }

            byte[] blockIdx = new byte[COLUMNS];
            buf.readBytes(blockIdx);
            List<String> blockPalette = readPalette(buf);
            byte[] biomeIdx = new byte[BIOME_CELLS];
            buf.readBytes(biomeIdx);
            List<String> biomePalette = readPalette(buf);
            return new MapInfoChunkPayload(cx, cz, heights, blockIdx, blockPalette, biomeIdx, biomePalette);
        }

        private static void writePalette(FriendlyByteBuf buf, List<String> palette) {
            buf.writeVarInt(palette.size());
            for (String s : palette) {
                buf.writeUtf(s);
            }
        }

        private static List<String> readPalette(FriendlyByteBuf buf) {
            int size = buf.readVarInt();
            List<String> palette = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                palette.add(buf.readUtf());
            }
            return palette;
        }

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

    // ---------------------------------------------------------------- registration

    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar =
                event.registrar(SharedJourneyConstants.MOD_ID).versioned("1");

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

        registrar.playToClient(
                MapInfoChunkPayload.TYPE,
                MapInfoChunkPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.clientMapInfoChunk.accept(payload)));

        registrar.playToServer(
                MapInfoRequestPayload.TYPE,
                MapInfoRequestPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.serverMapInfoRequest.accept(ctx.player(), payload)));

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
    }
}
