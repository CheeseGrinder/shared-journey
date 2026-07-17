package fr.cheesegrinder.sharedjourney.common.network;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Region sync payloads (spec §5): active layer announcement, region PNG
 * delta push, cache handshake and on-demand region requests. Handlers are
 * dispatched through {@link Payloads.Hooks} — see {@link Payloads} for
 * the indirection rationale.
 */
public final class RegionSyncPayloads {

    private RegionSyncPayloads() {}

    // ---------------------------------------------------------------- S2C: active layers

    /**
     * Active layers per dimension + CAVE bands + misc server toggles (sent
     * at login and on every reload).
     */
    public record LayerSettingsPayload(
            Map<ResourceLocation, List<MapLayer>> layersByDim,
            List<Integer> caveBands,
            int radarMaxRadius,
            boolean deathWaypointsEnabled,
            boolean serverManagesWaypoints)
            implements CustomPacketPayload {
        public static final Type<LayerSettingsPayload> TYPE = new Type<>(Payloads.id("layer_settings"));

        public static final StreamCodec<FriendlyByteBuf, LayerSettingsPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.layersByDim.size());
                    p.layersByDim.forEach((dim, layers) -> {
                        buf.writeResourceLocation(dim);
                        buf.writeVarInt(layers.size());
                        layers.forEach(l -> buf.writeUtf(l.id()));
                    });
                    buf.writeVarInt(p.caveBands.size());
                    p.caveBands.forEach(buf::writeVarInt);
                    buf.writeVarInt(p.radarMaxRadius);
                    buf.writeBoolean(p.deathWaypointsEnabled);
                    buf.writeBoolean(p.serverManagesWaypoints);
                },
                buf -> {
                    int n = buf.readVarInt();
                    Map<ResourceLocation, List<MapLayer>> map = new HashMap<>();
                    for (int i = 0; i < n; i++) {
                        ResourceLocation dim = buf.readResourceLocation();
                        int m = buf.readVarInt();
                        List<MapLayer> layers = new ArrayList<>(m);
                        for (int j = 0; j < m; j++) {
                            // Lenient: a custom layer id is registered on
                            // the fly (the client may not have the mod).
                            layers.add(MapLayer.register(buf.readUtf()));
                        }

                        map.put(dim, layers);
                    }
                    int nb = buf.readVarInt();
                    List<Integer> bands = new ArrayList<>(nb);
                    for (int i = 0; i < nb; i++) {
                        bands.add(buf.readVarInt());
                    }

                    int radarMaxRadius = buf.readVarInt();
                    boolean deathWaypointsEnabled = buf.readBoolean();
                    return new LayerSettingsPayload(
                            map, bands, radarMaxRadius, deathWaypointsEnabled, buf.readBoolean());
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
        public static final Type<RegionDataPayload> TYPE = new Type<>(Payloads.id("region_data"));

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
     * One handshake entry: the version the client holds for a region, plus
     * the SHA-256 (lowercase hex) RECOMPUTED from its cached file — never
     * read from the local index, which a tampering user could edit along
     * with the file. Empty hash = unknown (server falls back to the
     * version-only comparison).
     */
    public record IndexEntry(long version, String sha256) {}

    /**
     * Handshake (spec §5.1): on connection, the client sends a summary of its
     * local cache (keys + timestamps + recomputed file hashes, serialized and
     * GZIPped). The server computes the delta and only sends what is missing,
     * has changed, or fails the integrity check.
     */
    public record ClientIndexPayload(byte[] gzippedIndex) implements CustomPacketPayload {
        public static final Type<ClientIndexPayload> TYPE = new Type<>(Payloads.id("client_index"));

        public static final StreamCodec<FriendlyByteBuf, ClientIndexPayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeByteArray(p.gzippedIndex), buf -> new ClientIndexPayload(buf.readByteArray()));

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** Compact serialization: "indexKey=timestamp:sha256" lines, GZIPped. */
        public static byte[] encodeIndex(Map<RegionKey, IndexEntry> entries) {
            StringBuilder sb = new StringBuilder(entries.size() * 112);
            entries.forEach((k, v) -> sb.append(k.indexKey())
                    .append('=')
                    .append(v.version())
                    .append(':')
                    .append(v.sha256())
                    .append('\n'));

            try (var bos = new ByteArrayOutputStream();
                    var gz = new GZIPOutputStream(bos)) {
                gz.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                gz.finish();

                return bos.toByteArray();
            } catch (IOException e) {
                return new byte[0];
            }
        }

        public Map<RegionKey, IndexEntry> decodeIndex(int maxEntries) {
            Map<RegionKey, IndexEntry> out = new HashMap<>();

            try (var gis = new GZIPInputStream(new ByteArrayInputStream(gzippedIndex))) {
                String text = new String(gis.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    if (out.size() >= maxEntries) {
                        break;
                    }

                    decodeLine(line, out);
                }
            } catch (IOException ignored) {
            }
            return out;
        }

        private static void decodeLine(String line, Map<RegionKey, IndexEntry> out) {
            int eq = line.lastIndexOf('=');
            if (eq <= 0) {
                return;
            }

            RegionKey key = RegionKey.fromIndexKey(line.substring(0, eq));
            if (key == null) {
                return;
            }

            String value = line.substring(eq + 1);
            int colon = value.indexOf(':');
            String rawVersion = colon < 0 ? value : value.substring(0, colon);
            String sha256 = colon < 0 ? "" : value.substring(colon + 1);

            try {
                out.put(key, new IndexEntry(Long.parseLong(rawVersion), sha256));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- C2S: region request

    /** Client requests regions (fullscreen map) with the version it owns (-1 if none). */
    public record RegionRequestPayload(List<RegionKey> keys, List<Long> knownVersions) implements CustomPacketPayload {
        public static final Type<RegionRequestPayload> TYPE = new Type<>(Payloads.id("region_request"));

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

    // ---------------------------------------------------------------- registration

    /** Registers this group's payloads; called by {@link Payloads#register}. */
    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(
                LayerSettingsPayload.TYPE,
                LayerSettingsPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientLayerSettings.accept(payload)));

        registrar.playToClient(
                RegionDataPayload.TYPE,
                RegionDataPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Payloads.Hooks.clientRegionData.accept(payload)));

        registrar.playToServer(
                RegionRequestPayload.TYPE,
                RegionRequestPayload.CODEC,
                (payload, ctx) ->
                        ctx.enqueueWork(() -> Payloads.Hooks.serverRegionRequest.accept(ctx.player(), payload)));

        registrar.playToServer(
                ClientIndexPayload.TYPE,
                ClientIndexPayload.CODEC,
                (payload, ctx) ->
                        ctx.enqueueWork(() -> Payloads.Hooks.serverClientIndex.accept(ctx.player(), payload)));
    }
}
