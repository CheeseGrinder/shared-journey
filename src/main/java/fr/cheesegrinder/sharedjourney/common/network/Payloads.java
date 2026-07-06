package fr.cheesegrinder.sharedjourney.common;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Paquets réseau (spec §5). Le module common ne connaît ni le client ni le
 * serveur : les handlers sont injectés par le module d'assemblage via les
 * champs Hooks.* (évite toute dépendance circulaire entre modules).
 */
public final class Payloads {

    private Payloads() {}

    /** Indirection des handlers, câblée par :mod au démarrage. */
    public static final class Hooks {
        public static Consumer<LayerSettingsPayload> clientLayerSettings = p -> {};
        public static Consumer<RegionDataPayload> clientRegionData = p -> {};
        public static BiConsumer<Player, RegionRequestPayload> serverRegionRequest = (pl, p) -> {};
        public static BiConsumer<Player, ClientIndexPayload> serverClientIndex = (pl, p) -> {};
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SharedJourneyConstants.MOD_ID, path);
    }

    // ---------------------------------------------------------------- S2C : couches actives

    /** Couches actives par dimension + bandes CAVE (envoyé au login et à chaque reload). */
    public record LayerSettingsPayload(Map<ResourceLocation, List<MapLayer>> layersByDim,
                                       List<Integer> caveBands,
                                       int radarMaxRadius) implements CustomPacketPayload {
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
                        for (int j = 0; j < m; j++) layers.add(MapLayer.values()[buf.readVarInt()]);
                        map.put(dim, layers);
                    }
                    int nb = buf.readVarInt();
                    List<Integer> bands = new ArrayList<>(nb);
                    for (int i = 0; i < nb; i++) bands.add(buf.readVarInt());
                    return new LayerSettingsPayload(map, bands, buf.readVarInt());
                });

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------------------------------------------------------------- S2C : données de région

    /** Fragment de PNG de région, compressé GZIP côté flux complet (spec §5.3). */
    public record RegionDataPayload(RegionKey key, long version, int part, int totalParts,
                                    byte[] data) implements CustomPacketPayload {
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
                        buf.readVarLong(), buf.readVarInt(), buf.readVarInt(),
                        buf.readByteArray()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------------------------------------------------------------- C2S : handshake d'index

    /**
     * Handshake (spec §5.1) : à la connexion, le client envoie le résumé de son
     * index.json local (clés + timestamps, sérialisé et GZIPé). Le serveur
     * calcule le delta et n'enverra que ce qui manque ou a changé.
     */
    public record ClientIndexPayload(byte[] gzippedIndex) implements CustomPacketPayload {
        public static final Type<ClientIndexPayload> TYPE = new Type<>(id("client_index"));

        public static final StreamCodec<FriendlyByteBuf, ClientIndexPayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeByteArray(p.gzippedIndex),
                buf -> new ClientIndexPayload(buf.readByteArray()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        /** Sérialisation compacte : lignes "indexKey=timestamp", GZIP. */
        public static byte[] encodeIndex(Map<RegionKey, Long> entries) {
            StringBuilder sb = new StringBuilder(entries.size() * 48);
            entries.forEach((k, v) -> sb.append(k.indexKey()).append('=').append(v).append('\n'));
            try (var bos = new java.io.ByteArrayOutputStream();
                 var gz = new java.util.zip.GZIPOutputStream(bos)) {
                gz.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                gz.finish();
                return bos.toByteArray();
            } catch (java.io.IOException e) {
                return new byte[0];
            }
        }

        public Map<RegionKey, Long> decodeIndex(int maxEntries) {
            Map<RegionKey, Long> out = new HashMap<>();
            try (var gis = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gzippedIndex))) {
                String text = new String(gis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    if (out.size() >= maxEntries) break;
                    int eq = line.lastIndexOf('=');
                    if (eq <= 0) continue;
                    RegionKey key = RegionKey.fromIndexKey(line.substring(0, eq));
                    if (key == null) continue;
                    try { out.put(key, Long.parseLong(line.substring(eq + 1))); }
                    catch (NumberFormatException ignored) {}
                }
            } catch (java.io.IOException ignored) {}
            return out;
        }
    }

    // ---------------------------------------------------------------- C2S : requête de régions

    /** Le client demande des régions (plein écran) avec la version qu'il possède (-1 sinon). */
    public record RegionRequestPayload(List<RegionKey> keys, List<Long> knownVersions)
            implements CustomPacketPayload {
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

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------------------------------------------------------------- enregistrement

    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(SharedJourneyConstants.MOD_ID).versioned("1");

        registrar.playToClient(LayerSettingsPayload.TYPE, LayerSettingsPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.clientLayerSettings.accept(payload)));

        registrar.playToClient(RegionDataPayload.TYPE, RegionDataPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.clientRegionData.accept(payload)));

        registrar.playToServer(RegionRequestPayload.TYPE, RegionRequestPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.serverRegionRequest.accept(ctx.player(), payload)));

        registrar.playToServer(ClientIndexPayload.TYPE, ClientIndexPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> Hooks.serverClientIndex.accept(ctx.player(), payload)));
    }
}
