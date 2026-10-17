package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.config.LayersServerConfig;
import fr.cheesegrinder.sharedjourney.common.config.OverlaysServerConfig;
import fr.cheesegrinder.sharedjourney.common.network.ChunkOverlayPayloads;
import fr.cheesegrinder.sharedjourney.common.overlay.ChunkOverlayKind;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import net.neoforged.neoforge.common.world.chunk.ForcedChunkManager;
import net.neoforged.neoforge.network.PacketDistributor;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the chunk overlays (force-loaded chunks, slime chunks).
 *
 * <p>Clients request the 32x32 chunk bitmap of the map regions they
 * display; the server answers per region, never in bulk. Two rules keep the
 * data from leaking more than the shared map already shows:
 * <ul>
 *   <li>a region is only answered when it is already explored on the
 *       shared map (a tile exists for the requesting player, quarantine
 *       trust included);
 *   <li>the world seed is never sent: slime chunks are computed here and
 *       only the resulting bits leave the server.
 * </ul>
 * Force-loaded chunks change over time: the regions a player requested are
 * remembered (bounded) and re-sent when a change is detected (the forced
 * set of each requested dimension is compared once per second). A disabled
 * overlay ({@code overlays.*} server config) is never answered nor pushed.
 * Everything runs on the server main thread.
 */
public final class ChunkOverlayService {

    /** Salt of vanilla's slime chunk test ({@code Slime.checkSlimeSpawnRules}). */
    private static final long SLIME_SALT = 987234911L;

    /** Per-player, per-overlay minimum delay between two accepted requests (ms). */
    private static final long REQUEST_MIN_INTERVAL_MS = 200;

    /** Max forced-chunks regions remembered per player for change pushes. */
    private static final int MAX_SUBSCRIPTIONS = 1024;

    /** Cadence of the forced-set change detection (ticks). */
    private static final int CHANGE_CHECK_INTERVAL_TICKS = 20;

    /** Computed slime region bitmaps kept in memory (static data, LRU). */
    private static final int SLIME_CACHE_CAP = 2048;

    private static final long[] EMPTY_BITS = new long[0];

    private record RegionPos(ResourceKey<Level> dimension, int rx, int rz) {}

    /** Forced chunks of a dimension at the last check, grouped per region. */
    private record ForcedSnapshot(LongSet chunks, Map<Long, long[]> bitsByRegion) {}

    private static final class PlayerState {
        /** Last accepted request per overlay kind (ordinal), for the throttle. */
        final long[] lastRequestAt = new long[ChunkOverlayKind.values().length];

        final Set<RegionPos> forcedRegions = new LinkedHashSet<>();
    }

    private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, ForcedSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    /** Slime bitmaps by packed region (overworld only), cleared when the server stops. */
    private static final Map<Long, long[]> SLIME_CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, long[]> eldest) {
            return size() > SLIME_CACHE_CAP;
        }
    };

    private ChunkOverlayService() {}

    // ------------------------------------------------------------------ lifecycle / settings

    public static void onPlayerJoin(ServerPlayer player) {
        STATES.put(player.getUUID(), new PlayerState());
        sendSettings(player);
    }

    public static void onPlayerLeave(ServerPlayer player) {
        STATES.remove(player.getUUID());
    }

    /** A dimension switch makes the remembered regions of the previous one useless. */
    public static void onDimensionChange(ServerPlayer player) {
        PlayerState state = STATES.get(player.getUUID());
        if (state != null) {
            state.forcedRegions.clear();
        }
    }

    public static void shutdown() {
        STATES.clear();
        SNAPSHOTS.clear();
        synchronized (SLIME_CACHE) {
            SLIME_CACHE.clear();
        }
    }

    public static boolean isEnabled(ChunkOverlayKind kind) {
        return switch (kind) {
            case FORCED_CHUNKS -> OverlaysServerConfig.FORCED_CHUNKS_ENABLED.get();
            case SLIME_CHUNKS -> OverlaysServerConfig.SLIME_CHUNKS_ENABLED.get();
        };
    }

    /** Announces the allowed overlays to one player. */
    public static void sendSettings(ServerPlayer player) {
        PacketDistributor.sendToPlayer(
                player,
                new ChunkOverlayPayloads.OverlaySettingsPayload(
                        isEnabled(ChunkOverlayKind.FORCED_CHUNKS), isEnabled(ChunkOverlayKind.SLIME_CHUNKS)));
    }

    /** Config reload: re-announces the flags and drops the state of a disabled overlay. */
    public static void broadcastSettings(MinecraftServer server) {
        if (!isEnabled(ChunkOverlayKind.FORCED_CHUNKS)) {
            SNAPSHOTS.clear();
            STATES.values().forEach(state -> state.forcedRegions.clear());
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendSettings(player);
        }
    }

    // ------------------------------------------------------------------ client requests

    public static void handleRequest(Player playerRaw, ChunkOverlayPayloads.OverlayRegionRequestPayload payload) {
        if (!(playerRaw instanceof ServerPlayer player)) {
            return;
        }

        ChunkOverlayKind kind = payload.kind();
        PlayerState state = STATES.get(player.getUUID());
        MapManager mgr = MapManager.get();
        if (kind == null || state == null || mgr == null || !isEnabled(kind)) {
            return;
        }

        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().equals(payload.dimension())) {
            return;
        }

        if (kind == ChunkOverlayKind.SLIME_CHUNKS && level.dimension() != Level.OVERWORLD) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - state.lastRequestAt[kind.ordinal()] < REQUEST_MIN_INTERVAL_MS) {
            return;
        }

        state.lastRequestAt[kind.ordinal()] = now;
        List<ChunkOverlayPayloads.RegionBits> answers = new ArrayList<>();
        int count = Math.min(Math.min(payload.rxs().length, payload.rzs().length), ChunkOverlayPayloads.MAX_REGIONS);
        for (int i = 0; i < count; i++) {
            int rx = payload.rxs()[i];
            int rz = payload.rzs()[i];
            if (!isExplored(player, mgr, level.dimension(), rx, rz)) {
                continue;
            }

            if (kind == ChunkOverlayKind.SLIME_CHUNKS) {
                answers.add(new ChunkOverlayPayloads.RegionBits(rx, rz, slimeBits(level, rx, rz)));
            } else {
                answers.add(new ChunkOverlayPayloads.RegionBits(rx, rz, forcedBits(level, rx, rz)));
                subscribe(state, new RegionPos(level.dimension(), rx, rz));
            }
        }

        if (!answers.isEmpty()) {
            PacketDistributor.sendToPlayer(
                    player, new ChunkOverlayPayloads.OverlayRegionDataPayload(kind, payload.dimension(), answers));
        }
    }

    /**
     * Is the region already part of the map this player can see? Checked on
     * the hover sidecar first, then on every active layer (a dimension may
     * only serve CAVE bands). The quarantine trust of the player is honoured
     * exactly like for the region sync.
     */
    private static boolean isExplored(ServerPlayer player, MapManager mgr, ResourceKey<Level> dim, int rx, int rz) {
        RegionKey info = new RegionKey(dim, MapLayer.INFO, 0, rx, rz);
        if (mgr.versionOf(info, QuarantineService.isTrusted(player, info)) >= 0) {
            return true;
        }

        for (MapLayer layer : mgr.activeLayers(dim)) {
            if (layer == MapLayer.CAVE) {
                for (int band : LayersServerConfig.CAVE_BANDS.get()) {
                    if (isRegionKnown(player, mgr, new RegionKey(dim, layer, band, rx, rz))) {
                        return true;
                    }
                }
            } else if (isRegionKnown(player, mgr, new RegionKey(dim, layer, 0, rx, rz))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRegionKnown(ServerPlayer player, MapManager mgr, RegionKey key) {
        return mgr.versionOf(key, QuarantineService.isTrusted(player, key)) >= 0;
    }

    // ------------------------------------------------------------------ slime chunks

    /**
     * Slime bitmap of a region (overworld): the same test as vanilla's
     * {@code Slime.checkSlimeSpawnRules}, evaluated here with the level seed
     * which never leaves the server. Static data, cached.
     */
    private static long[] slimeBits(ServerLevel level, int rx, int rz) {
        long packed = packRegion(rx, rz);
        synchronized (SLIME_CACHE) {
            long[] cached = SLIME_CACHE.get(packed);
            if (cached != null) {
                return cached;
            }
        }

        long seed = level.getSeed();
        long[] bits = new long[ChunkOverlayKind.REGION_WORDS];
        boolean any = false;
        for (int lz = 0; lz < 32; lz++) {
            for (int lx = 0; lx < 32; lx++) {
                int chunkX = rx * 32 + lx;
                int chunkZ = rz * 32 + lz;
                if (WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, seed, SLIME_SALT)
                                .nextInt(10)
                        == 0) {
                    int bit = lz * 32 + lx;
                    bits[bit >> 6] |= 1L << (bit & 63);
                    any = true;
                }
            }
        }

        long[] result = any ? bits : EMPTY_BITS;
        synchronized (SLIME_CACHE) {
            SLIME_CACHE.put(packed, result);
        }
        return result;
    }

    // ------------------------------------------------------------------ forced chunks

    private static long[] forcedBits(ServerLevel level, int rx, int rz) {
        ForcedSnapshot snapshot = SNAPSHOTS.computeIfAbsent(level.dimension(), dim -> snapshotOf(level));
        return snapshot.bitsByRegion().getOrDefault(packRegion(rx, rz), EMPTY_BITS);
    }

    /** Remembers the region so a later change of its forced chunks is pushed (bounded, oldest dropped). */
    private static void subscribe(PlayerState state, RegionPos pos) {
        state.forcedRegions.remove(pos);
        state.forcedRegions.add(pos);
        if (state.forcedRegions.size() > MAX_SUBSCRIPTIONS) {
            Iterator<RegionPos> oldest = state.forcedRegions.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Every chunk of the level kept loaded: vanilla /forceload plus NeoForge block and entity tickets. */
    private static LongSet forcedChunksOf(ServerLevel level) {
        LongSet chunks = new LongOpenHashSet(level.getForcedChunks());
        ForcedChunksSavedData data = level.getDataStorage().get(ForcedChunksSavedData.factory(), "chunks");
        if (data != null) {
            addTickets(chunks, data.getBlockForcedChunks());
            addTickets(chunks, data.getEntityForcedChunks());
        }
        return chunks;
    }

    private static <T extends Comparable<? super T>> void addTickets(
            LongSet target, ForcedChunkManager.TicketTracker<T> tracker) {
        for (LongSet owned : tracker.getChunks().values()) {
            target.addAll(owned);
        }

        for (LongSet ticking : tracker.getTickingChunks().values()) {
            target.addAll(ticking);
        }
    }

    private static ForcedSnapshot snapshotOf(ServerLevel level) {
        LongSet chunks = forcedChunksOf(level);
        Map<Long, long[]> byRegion = new HashMap<>();
        LongIterator it = chunks.iterator();
        while (it.hasNext()) {
            long chunk = it.nextLong();
            int chunkX = ChunkPos.getX(chunk);
            int chunkZ = ChunkPos.getZ(chunk);
            long[] bits = byRegion.computeIfAbsent(
                    packRegion(Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32)),
                    key -> new long[ChunkOverlayKind.REGION_WORDS]);
            int bit = ChunkOverlayKind.bitIndex(chunkX, chunkZ);
            bits[bit >> 6] |= 1L << (bit & 63);
        }
        return new ForcedSnapshot(chunks, byRegion);
    }

    /**
     * Once per second: compares the forced chunks of every dimension that
     * players asked about with the last snapshot, and pushes the changed
     * regions to the players who requested them. Dimensions nobody
     * subscribed to any more are dropped.
     */
    public static void tick(MinecraftServer server) {
        if (SNAPSHOTS.isEmpty() || server.getTickCount() % CHANGE_CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        if (!isEnabled(ChunkOverlayKind.FORCED_CHUNKS)) {
            SNAPSHOTS.clear();
            return;
        }

        for (Map.Entry<ResourceKey<Level>, ForcedSnapshot> entry : new ArrayList<>(SNAPSHOTS.entrySet())) {
            ResourceKey<Level> dim = entry.getKey();
            ServerLevel level = server.getLevel(dim);
            if (level == null || !hasSubscribers(dim)) {
                SNAPSHOTS.remove(dim);
                continue;
            }

            ForcedSnapshot previous = entry.getValue();
            ForcedSnapshot fresh = snapshotOf(level);
            if (fresh.chunks().equals(previous.chunks())) {
                continue;
            }

            SNAPSHOTS.put(dim, fresh);
            pushChanges(server, dim, fresh, changedRegions(previous.chunks(), fresh.chunks()));
        }
    }

    private static boolean hasSubscribers(ResourceKey<Level> dim) {
        for (PlayerState state : STATES.values()) {
            for (RegionPos pos : state.forcedRegions) {
                if (pos.dimension().equals(dim)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Regions holding a chunk that is in exactly one of the two sets. */
    private static Set<Long> changedRegions(LongSet before, LongSet after) {
        Set<Long> regions = new HashSet<>();
        collectDifference(before, after, regions);
        collectDifference(after, before, regions);
        return regions;
    }

    private static void collectDifference(LongSet from, LongSet other, Set<Long> regions) {
        LongIterator it = from.iterator();
        while (it.hasNext()) {
            long chunk = it.nextLong();
            if (!other.contains(chunk)) {
                regions.add(
                        packRegion(Math.floorDiv(ChunkPos.getX(chunk), 32), Math.floorDiv(ChunkPos.getZ(chunk), 32)));
            }
        }
    }

    private static void pushChanges(
            MinecraftServer server, ResourceKey<Level> dim, ForcedSnapshot snapshot, Set<Long> changed) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerState state = STATES.get(player.getUUID());
            if (state == null || !player.level().dimension().equals(dim)) {
                continue;
            }

            List<ChunkOverlayPayloads.RegionBits> updates = new ArrayList<>();
            for (RegionPos pos : state.forcedRegions) {
                if (pos.dimension().equals(dim) && changed.contains(packRegion(pos.rx(), pos.rz()))) {
                    long[] bits = snapshot.bitsByRegion().getOrDefault(packRegion(pos.rx(), pos.rz()), EMPTY_BITS);
                    updates.add(new ChunkOverlayPayloads.RegionBits(pos.rx(), pos.rz(), bits));
                }
            }

            sendInBatches(player, dim, updates);
        }
    }

    private static void sendInBatches(
            ServerPlayer player, ResourceKey<Level> dim, List<ChunkOverlayPayloads.RegionBits> updates) {
        int max = ChunkOverlayPayloads.MAX_REGIONS;
        for (int from = 0; from < updates.size(); from += max) {
            List<ChunkOverlayPayloads.RegionBits> batch =
                    new ArrayList<>(updates.subList(from, Math.min(from + max, updates.size())));
            PacketDistributor.sendToPlayer(
                    player,
                    new ChunkOverlayPayloads.OverlayRegionDataPayload(
                            ChunkOverlayKind.FORCED_CHUNKS, dim.location(), batch));
        }
    }

    private static long packRegion(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }
}
