package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;
import fr.cheesegrinder.sharedjourney.common.config.CommonConfig;
import fr.cheesegrinder.sharedjourney.common.config.ServerConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Synchronisation serveur -> clients (spec §5).
 *
 * 1. Handshake : le client envoie son index local ; il initialise la vue
 *    "ce que ce joueur possède déjà" (sentVersions).
 * 2. Delta : périodiquement, les régions du rayon configuré dont la version
 *    serveur est plus récente sont mises en ConcurrentLinkedQueue.
 * 3. Batching : chaque tick, la file est dépilée dans la limite du budget
 *    max_kb_per_second_per_player (converti en octets/tick), en fragments.
 */
public final class SyncService {

    private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();

    private SyncService() {}

    // ------------------------------------------------------------------ cycle de vie joueurs

    public static void onPlayerJoin(ServerPlayer player) {
        STATES.put(player.getUUID(), new PlayerState());
        sendLayerSettings(player);
        // Le push démarrera après réception du handshake (ou au 1er delta périodique
        // si le client n'a pas le mod... auquel cas rien ne sera envoyé : le
        // registre réseau NeoForge n'accepte nos payloads que si le client les connaît.
        enqueueDelta(player, false);
    }

    public static void onPlayerLeave(ServerPlayer player) {
        STATES.remove(player.getUUID());
    }

    /** Handshake §5.1 : seed des versions connues du client depuis son index local. */
    public static void handleClientIndex(Player playerRaw, Payloads.ClientIndexPayload payload) {
        if (!(playerRaw instanceof ServerPlayer player)) {
            return;
        }

        PlayerState st = STATES.get(player.getUUID());
        if (st == null) {
            return;
        }

        Map<RegionKey, Long> clientIndex = payload.decodeIndex(50_000);
        st.sentVersions.putAll(clientIndex);
        st.handshakeEntries = clientIndex.size();
        // Recalcule immédiatement le delta avec cette connaissance.
        enqueueDelta(player, false);
    }

    /** Envoie les couches actives par dimension + bandes CAVE + plafond radar. */
    public static void sendLayerSettings(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        Map<ResourceLocation, List<MapLayer>> map = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            map.put(level.dimension().location(),
                    new ArrayList<>(ServerConfig.layersFor(level.dimension())));
        }
        PacketDistributor.sendToPlayer(player, new Payloads.LayerSettingsPayload(
                map, new ArrayList<>(ServerConfig.CAVE_BANDS.get()),
                ServerConfig.RADAR_MAX_RADIUS.get()));
    }

    public static void broadcastLayerSettings(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendLayerSettings(p);
        }
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return;
        }

        mgr.tick();

        long gameTime = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerState st = STATES.get(player.getUUID());
            if (st == null) {
                continue;
            }

            // Déphasage par joueur pour lisser la charge.
            if (gameTime % ServerConfig.SYNC_RATE_TICKS.get() == (player.getId() & 15)) {
                enqueueDelta(player, false);
            }
            drainQueue(player, st);
        }
    }

    /** Delta §5.2 : régions du rayon dont la version serveur > version connue du client. */
    private static void enqueueDelta(ServerPlayer player, boolean force) {
        MapManager mgr = MapManager.get();
        PlayerState st = STATES.get(player.getUUID());
        if (mgr == null || st == null) {
            return;
        }

        int radius = ServerConfig.PUSH_RADIUS_REGIONS.get();
        int prx = Math.floorDiv(player.blockPosition().getX(), RegionKey.REGION_BLOCKS);
        int prz = Math.floorDiv(player.blockPosition().getZ(), RegionKey.REGION_BLOCKS);
        var dim = player.level().dimension();
        EnumSet<MapLayer> layers = ServerConfig.layersFor(dim);

        for (int rx = prx - radius; rx <= prx + radius; rx++) {
            for (int rz = prz - radius; rz <= prz + radius; rz++) {
                for (MapLayer layer : layers) {
                    if (layer == MapLayer.CAVE) {
                        for (int band : ServerConfig.CAVE_BANDS.get()) {
                            maybeQueue(st, mgr, new RegionKey(dim, layer, band, rx, rz), force);
                        }
                    } else {
                        maybeQueue(st, mgr, new RegionKey(dim, layer, 0, rx, rz), force);
                    }
                }
            }
        }
    }

    /**
     * Notifie qu'une ou plusieurs régions viennent d'être re-rendues : mise en
     * file immédiate pour les joueurs de la dimension, sans attendre le delta
     * périodique (réactivité des modifications de terrain). Appelable depuis
     * un thread de rendu : bascule sur le main thread.
     */
    public static void pushRegionUpdates(MinecraftServer server, List<RegionKey> keys) {
        if (keys.isEmpty()) {
            return;
        }

        server.execute(() -> {
            MapManager mgr = MapManager.get();
            if (mgr == null) {
                return;
            }

            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                PlayerState st = STATES.get(p.getUUID());
                if (st == null) {
                    continue;
                }

                var dim = p.level().dimension();
                for (RegionKey key : keys) {
                    if (key.dimension().equals(dim)) {
                        maybeQueue(st, mgr, key, false);
                    }
                }
            }
        });
    }

    private static void maybeQueue(PlayerState st, MapManager mgr, RegionKey key, boolean force) {
        long serverVersion = mgr.versionOf(key);
        if (serverVersion < 0) {
            return;
        }

        long known = force ? -1 : st.sentVersions.getOrDefault(key, -1L);
        if (serverVersion > known) {
            st.enqueue(key);
        }
    }

    /** Batching §5.3 : dépile la queue dans la limite du budget d'octets par tick. */
    private static void drainQueue(ServerPlayer player, PlayerState st) {
        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return;
        }

        // max_kb_per_second -> octets par tick (20 ticks/s)
        int budget = ServerConfig.MAX_KB_PER_SECOND_PER_PLAYER.get() * 1024 / 20;
        int fragSize = CommonConfig.FRAGMENT_SIZE.get();

        while (budget > 0) {
            if (st.currentData == null) {
                RegionKey next = st.poll();
                if (next == null) {
                    return;
                }

                byte[] png = mgr.pngOf(next);
                if (png == null) {
                    continue;
                }

                st.currentKey = next;
                st.currentData = png;
                st.currentVersion = mgr.versionOf(next);
                st.currentOffset = 0;
            }

            int total = (st.currentData.length + fragSize - 1) / fragSize;
            int part = st.currentOffset / fragSize;
            int len = Math.min(fragSize, st.currentData.length - st.currentOffset);
            byte[] slice = new byte[len];
            System.arraycopy(st.currentData, st.currentOffset, slice, 0, len);

            PacketDistributor.sendToPlayer(player,
                    new Payloads.RegionDataPayload(st.currentKey, st.currentVersion, part, total, slice));

            st.currentOffset += len;
            budget -= len;
            st.bytesSent += len;

            if (st.currentOffset >= st.currentData.length) {
                st.sentVersions.put(st.currentKey, st.currentVersion);
                st.regionsSent++;
                st.lastSyncMillis = System.currentTimeMillis();
                st.currentData = null;
                st.currentKey = null;
            }
        }
    }

    // ------------------------------------------------------------------ requêtes plein écran

    public static void handleRegionRequest(Player playerRaw, Payloads.RegionRequestPayload payload) {
        if (!(playerRaw instanceof ServerPlayer player)) {
            return;
        }

        if (!ServerConfig.ALLOW_ON_DEMAND_REQUESTS.get()) {
            return;
        }

        MapManager mgr = MapManager.get();
        PlayerState st = STATES.get(player.getUUID());
        if (mgr == null || st == null) {
            return;
        }

        int max = Math.min(payload.keys().size(), 128); // anti-abus
        for (int i = 0; i < max; i++) {
            RegionKey key = payload.keys().get(i);
            if (!key.dimension().equals(player.level().dimension())) {
                continue;
            }

            if (!ServerConfig.layersFor(key.dimension()).contains(key.layer())) {
                continue;
            }

            long serverVersion = mgr.versionOf(key);
            if (serverVersion > payload.knownVersions().get(i)) {
                st.enqueue(key);
            }
        }
        st.requestsReceived += max;
    }

    // ------------------------------------------------------------------ administration / stats

    /**
     * Force la resynchronisation (spec §7 : /map admin sync force).
     * regionFilter null = tout le rayon ; sinon uniquement la région donnée
     * (sur toutes les couches actives de la dimension du joueur).
     */
    public static int forceSync(ServerPlayer player, boolean full, int[] regionFilter) {
        PlayerState st = STATES.get(player.getUUID());
        MapManager mgr = MapManager.get();
        if (st == null || mgr == null) {
            return 0;
        }

        if (regionFilter != null) {
            var dim = player.level().dimension();
            for (MapLayer layer : ServerConfig.layersFor(dim)) {
                if (layer == MapLayer.CAVE) {
                    for (int band : ServerConfig.CAVE_BANDS.get()) {
                        RegionKey key = new RegionKey(dim, layer, band, regionFilter[0], regionFilter[1]);
                        st.sentVersions.remove(key);
                        maybeQueue(st, mgr, key, true);
                    }
                } else {
                    RegionKey key = new RegionKey(dim, layer, 0, regionFilter[0], regionFilter[1]);
                    st.sentVersions.remove(key);
                    maybeQueue(st, mgr, key, true);
                }
            }
        } else {
            if (full) {
                st.sentVersions.clear();
            }

            enqueueDelta(player, true);
        }
        st.forcedCount++;
        return st.queueSize();
    }

    public static String statsFor(ServerPlayer player) {
        PlayerState st = STATES.get(player.getUUID());
        if (st == null) {
            return player.getGameProfile().getName() + " : aucun état de sync";
        }

        long ago = st.lastSyncMillis == 0 ? -1 : (System.currentTimeMillis() - st.lastSyncMillis) / 1000;
        return String.format(
                "%s : %d régions envoyées, %.1f Ko, file: %d, handshake: %d entrées, requêtes: %d, forcées: %d, dernier envoi: %s",
                player.getGameProfile().getName(),
                st.regionsSent, st.bytesSent / 1024.0, st.queueSize(),
                st.handshakeEntries, st.requestsReceived, st.forcedCount,
                ago < 0 ? "jamais" : "il y a " + ago + "s");
    }

    // ------------------------------------------------------------------

    private static final class PlayerState {
        final Map<RegionKey, Long> sentVersions = new ConcurrentHashMap<>();
        /** ConcurrentLinkedQueue conformément à la spec §5.2. */
        private final ConcurrentLinkedQueue<RegionKey> queue = new ConcurrentLinkedQueue<>();
        private final Set<RegionKey> queuedSet = ConcurrentHashMap.newKeySet();

        RegionKey currentKey;
        byte[] currentData;
        long currentVersion;
        int currentOffset;

        long bytesSent;
        int regionsSent;
        int requestsReceived;
        int forcedCount;
        int handshakeEntries;
        long lastSyncMillis;

        void enqueue(RegionKey key) {
            if (queuedSet.add(key)) {
                queue.add(key);
            }
        }

        RegionKey poll() {
            RegionKey k = queue.poll();
            if (k != null) {
                queuedSet.remove(k);
            }

            return k;
        }

        int queueSize() {
            return queue.size();
        }
    }
}
