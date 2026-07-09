package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.config.CommonConfig;
import fr.cheesegrinder.sharedjourney.common.config.LayersServerConfig;
import fr.cheesegrinder.sharedjourney.common.config.SyncServerConfig;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

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
 * Server -> clients synchronization (spec §5).
 *
 * 1. Handshake: the client sends its local index; it initializes the view of
 *    "what this player already owns" (sentVersions).
 * 2. Delta: periodically, regions within the configured radius whose server
 *    version is newer are put in a ConcurrentLinkedQueue.
 * 3. Batching: each tick, the queue is drained within the
 *    max_kb_per_second_per_player budget (converted to bytes/tick), in
 *    fragments.
 */
public final class SyncService {

    /**
     * Minimum interval between two hover info requests per player (IO
     * anti-spam). Short: the client prefetches the chunks around the cursor
     * (spaced 60 ms apart on its side).
     */
    private static final long INFO_REQUEST_MIN_INTERVAL_MS = 40;

    private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();

    private SyncService() {}

    // ------------------------------------------------------------------ player lifecycle

    public static void onPlayerJoin(ServerPlayer player) {
        STATES.put(player.getUUID(), new PlayerState());
        sendLayerSettings(player);
        // Pushing starts after the handshake is received (or on the 1st
        // periodic delta if the client does not have the mod... in which
        // case nothing is sent: the NeoForge network registry only accepts
        // our payloads if the client knows them).
        enqueueDelta(player, false);
    }

    public static void onPlayerLeave(ServerPlayer player) {
        STATES.remove(player.getUUID());
    }

    /** Handshake §5.1: seeds the client's known versions from its local index. */
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
        // Immediately recompute the delta with this knowledge.
        enqueueDelta(player, false);
    }

    /** Sends the active layers per dimension + CAVE bands + radar cap. */
    public static void sendLayerSettings(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        Map<ResourceLocation, List<MapLayer>> map = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            map.put(level.dimension().location(), new ArrayList<>(LayersServerConfig.layersFor(level.dimension())));
        }
        PacketDistributor.sendToPlayer(
                player,
                new Payloads.LayerSettingsPayload(
                        map,
                        new ArrayList<>(LayersServerConfig.CAVE_BANDS.get()),
                        SyncServerConfig.RADAR_MAX_RADIUS.get()));
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
        // Positions of (non-hidden) players for the client maps: ~1x/s.
        if (gameTime % PLAYER_POSITIONS_INTERVAL_TICKS == 0) {
            broadcastPlayerPositions(server);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerState st = STATES.get(player.getUUID());
            if (st == null) {
                continue;
            }

            // Per-player phase shift to smooth the load.
            if (gameTime % SyncServerConfig.SYNC_RATE_TICKS.get() == (player.getId() & 15)) {
                enqueueDelta(player, false);
            }
            drainQueue(player, st);
        }
    }

    /** Delta §5.2: regions in the radius whose server version > client's known version. */
    private static void enqueueDelta(ServerPlayer player, boolean force) {
        MapManager mgr = MapManager.get();
        PlayerState st = STATES.get(player.getUUID());
        if (mgr == null || st == null) {
            return;
        }

        int radius = SyncServerConfig.PUSH_RADIUS_REGIONS.get();
        int prx = Math.floorDiv(player.blockPosition().getX(), RegionKey.REGION_BLOCKS);
        int prz = Math.floorDiv(player.blockPosition().getZ(), RegionKey.REGION_BLOCKS);
        var dim = player.level().dimension();
        EnumSet<MapLayer> layers = LayersServerConfig.layersFor(dim);

        for (int rx = prx - radius; rx <= prx + radius; rx++) {
            for (int rz = prz - radius; rz <= prz + radius; rz++) {
                for (MapLayer layer : layers) {
                    if (layer == MapLayer.CAVE) {
                        for (int band : LayersServerConfig.CAVE_BANDS.get()) {
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
     * Notifies that one or more regions were just re-rendered: immediate
     * queueing for the players of that dimension, without waiting for the
     * periodic delta (terrain modification reactivity). Callable from a
     * render thread: hops onto the main thread.
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

    /** Batching §5.3: drains the queue within the per-tick byte budget. */
    private static void drainQueue(ServerPlayer player, PlayerState st) {
        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return;
        }

        // max_kb_per_second -> bytes per tick (20 ticks/s)
        int budget = SyncServerConfig.MAX_KB_PER_SECOND_PER_PLAYER.get() * 1024 / 20;
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

            PacketDistributor.sendToPlayer(
                    player, new Payloads.RegionDataPayload(st.currentKey, st.currentVersion, part, total, slice));

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

    // ------------------------------------------------------------------ fullscreen requests

    public static void handleRegionRequest(Player playerRaw, Payloads.RegionRequestPayload payload) {
        if (!(playerRaw instanceof ServerPlayer player)) {
            return;
        }

        if (!SyncServerConfig.ALLOW_ON_DEMAND_REQUESTS.get()) {
            return;
        }

        MapManager mgr = MapManager.get();
        PlayerState st = STATES.get(player.getUUID());
        if (mgr == null || st == null) {
            return;
        }

        int max = Math.min(payload.keys().size(), 128); // anti-abuse
        for (int i = 0; i < max; i++) {
            RegionKey key = payload.keys().get(i);
            if (!key.dimension().equals(player.level().dimension())) {
                continue;
            }

            if (!LayersServerConfig.layersFor(key.dimension()).contains(key.layer())) {
                continue;
            }

            long serverVersion = mgr.versionOf(key);
            if (serverVersion > payload.knownVersions().get(i)) {
                st.enqueue(key);
            }
        }
        st.requestsReceived += max;
    }

    /**
     * Hover info request from the fullscreen map: returns the column's
     * biome, surface block and Y. If the chunk is not loaded, it is read
     * from disk (NBT status checked: never any terrain generation).
     * Throttled per player to bound the IO.
     */
    public static void handleMapInfoRequest(Player playerRaw, Payloads.MapInfoRequestPayload payload) {
        if (!(playerRaw instanceof ServerPlayer player)) {
            return;
        }

        if (!SyncServerConfig.ALLOW_ON_DEMAND_REQUESTS.get()) {
            return;
        }

        PlayerState st = STATES.get(player.getUUID());
        if (st == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - st.lastInfoRequestMillis < INFO_REQUEST_MIN_INTERVAL_MS) {
            return;
        }

        st.lastInfoRequestMillis = now;
        ServerLevel level = player.serverLevel();
        int cx = payload.x() >> 4;
        int cz = payload.z() >> 4;
        ChunkAccess chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk != null) {
            sendMapInfo(player, chunk);
            return;
        }

        // Chunk not loaded: status checked on disk before loading (no
        // terrain generation), then load and reply on the main thread.
        level.getChunkSource().chunkMap.read(new ChunkPos(cx, cz)).thenAccept(tag -> {
            if (tag.isEmpty() || !tag.get().getString("Status").endsWith("full")) {
                return;
            }

            level.getServer().execute(() -> {
                if (player.hasDisconnected()) {
                    return;
                }

                sendMapInfo(player, level.getChunk(cx, cz));
            });
        });
    }

    /**
     * Sends the hover info of the WHOLE chunk (heights, surface blocks,
     * biomes per 4x4 cell, palettized): one response covers 256 columns,
     * hovering becomes instantaneous client-side.
     */
    private static void sendMapInfo(ServerPlayer player, ChunkAccess chunk) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        short[] heights = new short[Payloads.MapInfoChunkPayload.COLUMNS];
        byte[] blockIdx = new byte[Payloads.MapInfoChunkPayload.COLUMNS];
        List<String> blockPalette = new ArrayList<>();
        Map<String, Integer> blockLookup = new HashMap<>();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int i = dz * 16 + dx;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, dx, dz);
                heights[i] = (short) y;
                BlockState state = chunk.getBlockState(new BlockPos(baseX + dx, y, baseZ + dz));
                String blockId = state.isAir()
                        ? ""
                        : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                blockIdx[i] = (byte) paletteIndex(blockPalette, blockLookup, blockId);
            }
        }

        byte[] biomeIdx = new byte[Payloads.MapInfoChunkPayload.BIOME_CELLS];
        List<String> biomePalette = new ArrayList<>();
        Map<String, Integer> biomeLookup = new HashMap<>();
        for (int bz = 0; bz < 4; bz++) {
            for (int bx = 0; bx < 4; bx++) {
                // Center of the 4x4 cell, at its column's surface Y.
                int dx = bx * 4 + 2;
                int dz = bz * 4 + 2;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, dx, dz);
                String biomeId = chunk.getNoiseBiome((baseX + dx) >> 2, y >> 2, (baseZ + dz) >> 2)
                        .unwrapKey()
                        .map(k -> k.location().toString())
                        .orElse("");
                biomeIdx[bz * 4 + bx] = (byte) paletteIndex(biomePalette, biomeLookup, biomeId);
            }
        }

        PacketDistributor.sendToPlayer(
                player,
                new Payloads.MapInfoChunkPayload(
                        chunk.getPos().x, chunk.getPos().z, heights, blockIdx, blockPalette, biomeIdx, biomePalette));
    }

    // ------------------------------------------------------------------ player visibility on the map

    /** Broadcast cadence (ticks) of player positions to the maps. */
    private static final int PLAYER_POSITIONS_INTERVAL_TICKS = 20;

    /** Players who asked to be hidden from the other players' map. */
    private static final Set<UUID> HIDDEN_PLAYERS = ConcurrentHashMap.newKeySet();

    /** Broadcasts the position (dimension, x, z) of every non-hidden player. */
    private static void broadcastPlayerPositions(MinecraftServer server) {
        List<Payloads.PlayerPositionsPayload.PlayerPos> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (HIDDEN_PLAYERS.contains(player.getUUID())) {
                continue;
            }

            players.add(new Payloads.PlayerPositionsPayload.PlayerPos(
                    player.getUUID(), player.level().dimension().location(), player.getX(), player.getZ()));
        }
        PacketDistributor.sendToAllPlayers(new Payloads.PlayerPositionsPayload(players));
    }

    /** "Hidden from the map" preference received from a client: applied and broadcast. */
    public static void handleMapVisibility(Player playerRaw, Payloads.MapVisibilityPayload payload) {
        if (!(playerRaw instanceof ServerPlayer player)) {
            return;
        }

        boolean changed;
        if (payload.hidden()) {
            changed = HIDDEN_PLAYERS.add(player.getUUID());
        } else {
            changed = HIDDEN_PLAYERS.remove(player.getUUID());
        }

        if (changed) {
            broadcastHiddenPlayers();
        }
    }

    /** Sends the current hidden player list to a joining player. */
    public static void sendHiddenPlayers(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new Payloads.HiddenPlayersPayload(List.copyOf(HIDDEN_PLAYERS)));
    }

    /** Cleared on disconnect (the preference is sent again on reconnect). */
    public static void clearHiddenPlayer(ServerPlayer player) {
        if (HIDDEN_PLAYERS.remove(player.getUUID())) {
            broadcastHiddenPlayers();
        }
    }

    private static void broadcastHiddenPlayers() {
        PacketDistributor.sendToAllPlayers(new Payloads.HiddenPlayersPayload(List.copyOf(HIDDEN_PLAYERS)));
    }

    /** Index of the identifier in the palette (added if absent, capped at 255). */
    private static int paletteIndex(List<String> palette, Map<String, Integer> lookup, String id) {
        Integer existing = lookup.get(id);
        if (existing != null) {
            return existing;
        }

        if (palette.size() >= 255) {
            return 0;
        }

        palette.add(id);
        lookup.put(id, palette.size() - 1);
        return palette.size() - 1;
    }

    // ------------------------------------------------------------------ administration / stats

    /**
     * Forces a resynchronization (spec §7: /sj admin sync force).
     * Null regionFilter = the whole radius; otherwise only the given region
     * (across all active layers of the player's dimension).
     */
    public static int forceSync(ServerPlayer player, boolean full, int[] regionFilter) {
        PlayerState st = STATES.get(player.getUUID());
        MapManager mgr = MapManager.get();
        if (st == null || mgr == null) {
            return 0;
        }

        if (regionFilter != null) {
            var dim = player.level().dimension();
            for (MapLayer layer : LayersServerConfig.layersFor(dim)) {
                if (layer == MapLayer.CAVE) {
                    for (int band : LayersServerConfig.CAVE_BANDS.get()) {
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
            return player.getGameProfile().getName() + ": no sync state";
        }

        long ago = st.lastSyncMillis == 0 ? -1 : (System.currentTimeMillis() - st.lastSyncMillis) / 1000;
        return String.format(
                "%s: %d regions sent, %.1f KB, queue: %d, handshake: %d entries, requests: %d, forced: %d, last sent: %s",
                player.getGameProfile().getName(),
                st.regionsSent,
                st.bytesSent / 1024.0,
                st.queueSize(),
                st.handshakeEntries,
                st.requestsReceived,
                st.forcedCount,
                ago < 0 ? "never" : ago + "s ago");
    }

    // ------------------------------------------------------------------

    private static final class PlayerState {
        final Map<RegionKey, Long> sentVersions = new ConcurrentHashMap<>();
        /** ConcurrentLinkedQueue as required by spec §5.2. */
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
        long lastInfoRequestMillis;

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
