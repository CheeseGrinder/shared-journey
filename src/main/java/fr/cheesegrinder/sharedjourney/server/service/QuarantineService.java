package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.common.config.PrivacyServerConfig;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;
import fr.cheesegrinder.sharedjourney.common.region.RegionStorage;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Anti-leak for players hidden from the map ("privacy" config section): a
 * hidden player would otherwise "draw" their position through three vectors
 * — broken/placed blocks (live push), freshly generated chunks (exploration
 * front) and CAVE band unlocks (CaveTracker).
 *
 * <p>The gate applies to the DIFFUSION, not the rendering: chunks resolved
 * near a hidden player are rendered normally into the real region image, but
 * distant players are served a "public" variant of the region
 * ({@link MapManager}), frozen before the quarantined writes and carrying its
 * own version (otherwise the reconnection handshake would bypass the
 * quarantine). Exact attribution is impossible (block events have no owner),
 * so the heuristic is proximity: every chunk resolved within
 * {@code quarantineRadiusChunks} of a hidden player is quarantined, whoever
 * caused the update.
 *
 * <p>Players near a pending chunk (including the hidden player themself)
 * keep receiving the real data immediately: the game already streams them
 * the area. Accepted limit: proximity to ANY pending chunk of a region
 * trusts the whole region — two distinct quarantined spots in the same
 * region cross-reveal to players standing next to either one (both are
 * within 512 blocks anyway).
 *
 * <p>Chunks drain individually after {@code quarantineDrainMinutes},
 * randomized +/-25% so the reveal does not replay the trajectory in order;
 * at drain time proximity is re-evaluated (a hidden player still nearby
 * re-arms the deadline). Pending chunks survive a restart
 * ({@code quarantine.json} next to index.json).
 */
public final class QuarantineService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** File name of the persisted pending set, next to index.json. */
    public static final String FILE_NAME = "quarantine.json";

    /** Drain scan cadence (ticks). */
    private static final int DRAIN_INTERVAL_TICKS = 100;
    /** Max chunks published per drain scan (smooths the region pushes). */
    private static final int MAX_DRAIN_PER_SCAN = 64;

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Long>>() {}.getType();

    /** Verdict for a chunk about to be rendered. */
    public enum Verdict {
        /** Render and diffuse normally. */
        RENDER,
        /** Render into the real image only; diffusion to distant players deferred. */
        QUARANTINE,
        /** Do not render at all (EXCLUDE policy). */
        DROP
    }

    private record ChunkId(ResourceKey<Level> dim, int cx, int cz) {}

    private record RegionPos(ResourceKey<Level> dim, int rx, int rz) {}

    /** Pending chunks -> drain deadline (epoch ms). */
    private static final Map<ChunkId, Long> PENDING = new ConcurrentHashMap<>();
    /** Secondary index for the per-region trust checks and variant loading. */
    private static final Map<RegionPos, Set<ChunkId>> BY_REGION = new ConcurrentHashMap<>();

    private static Path file;

    private QuarantineService() {}

    // ------------------------------------------------------------------ lifecycle

    public static void init(MinecraftServer server) {
        file = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("sharedjourney")
                .resolve(FILE_NAME);
        load();
    }

    public static void shutdown() {
        save();
        PENDING.clear();
        BY_REGION.clear();
        file = null;
    }

    // ------------------------------------------------------------------ gate (main thread)

    /**
     * Verdict for a chunk about to be rendered. Called on the main thread at
     * chunk-resolution time (MapManager.tick / renderNow) — the single point
     * every render path converges to (exploration, dirty marks, cave
     * unlocks, regen).
     */
    public static Verdict evaluate(ServerLevel level, int cx, int cz) {
        PrivacyServerConfig.HiddenAreaPolicy policy = PrivacyServerConfig.HIDDEN_AREA_POLICY.get();
        if (policy == PrivacyServerConfig.HiddenAreaPolicy.OFF) {
            return Verdict.RENDER;
        }

        boolean nearHidden = isNearHiddenPlayer(level, cx, cz);
        if (policy == PrivacyServerConfig.HiddenAreaPolicy.EXCLUDE) {
            return nearHidden ? Verdict.DROP : Verdict.RENDER;
        }

        ChunkId id = new ChunkId(level.dimension(), cx, cz);
        if (!nearHidden && !PENDING.containsKey(id)) {
            return Verdict.RENDER;
        }

        // New activity in a pending chunk re-arms its deadline (draining in
        // the middle of the activity would reveal a live position).
        arm(id);
        return Verdict.QUARANTINE;
    }

    /**
     * May this player receive the REAL variant of this region right now?
     * True when the region has no pending chunk, or when the player stands
     * within the quarantine radius of one (the game already streams them the
     * area — see the class Javadoc for the accepted cross-reveal limit).
     */
    public static boolean isTrusted(ServerPlayer player, RegionKey key) {
        Set<ChunkId> pending = BY_REGION.get(new RegionPos(key.dimension(), key.rx(), key.rz()));
        if (pending == null || pending.isEmpty()) {
            return true;
        }
        if (!player.level().dimension().equals(key.dimension())) {
            return false;
        }

        int radius = effectiveRadius(player.serverLevel().getServer());
        ChunkPos pos = player.chunkPosition();
        for (ChunkId id : pending) {
            if (Math.max(Math.abs(pos.x - id.cx()), Math.abs(pos.z - id.cz())) <= radius) {
                return true;
            }
        }
        return false;
    }

    /** Does the region hold at least one pending (quarantined) chunk? */
    public static boolean hasPending(ResourceKey<Level> dim, int rx, int rz) {
        Set<ChunkId> set = BY_REGION.get(new RegionPos(dim, rx, rz));
        return set != null && !set.isEmpty();
    }

    /** Total pending chunks (stats). */
    public static int pendingCount() {
        return PENDING.size();
    }

    // ------------------------------------------------------------------ drain (main thread)

    /** Called every server tick; drains due chunks every few seconds. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % DRAIN_INTERVAL_TICKS != 0 || PENDING.isEmpty()) {
            return;
        }

        MapManager mgr = MapManager.get();
        if (mgr == null) {
            return;
        }

        long now = System.currentTimeMillis();
        List<RegionKey> touched = new ArrayList<>();
        int drained = 0;
        for (Map.Entry<ChunkId, Long> e : PENDING.entrySet()) {
            if (drained >= MAX_DRAIN_PER_SCAN) {
                break;
            }
            if (e.getValue() > now) {
                continue;
            }

            ChunkId id = e.getKey();
            ServerLevel level = server.getLevel(id.dim());
            // A hidden player is still nearby: draining now would reveal a
            // live position — re-arm.
            if (level != null && isNearHiddenPlayer(level, id.cx(), id.cz())) {
                arm(id);
                continue;
            }

            boolean regionClean = release(id);
            touched.addAll(mgr.publishChunk(id.dim(), id.cx(), id.cz(), regionClean));
            drained++;
        }
        if (!touched.isEmpty()) {
            SyncService.pushRegionUpdates(server, touched);
        }
    }

    // ------------------------------------------------------------------ internals

    private static void arm(ChunkId id) {
        long delay = PrivacyServerConfig.QUARANTINE_DRAIN_MINUTES.get() * 60_000L;
        // +/-25% jitter: draining in exact quarantine order would replay the
        // hidden player's trajectory, just delayed.
        delay = (long) (delay * (0.75 + ThreadLocalRandom.current().nextDouble() * 0.5));
        if (PENDING.put(id, System.currentTimeMillis() + delay) == null) {
            BY_REGION
                    .computeIfAbsent(regionOf(id), k -> ConcurrentHashMap.newKeySet())
                    .add(id);
        }
    }

    /** Removes a chunk from the pending set; true if its region is now clean. */
    private static boolean release(ChunkId id) {
        PENDING.remove(id);
        RegionPos rp = regionOf(id);
        Set<ChunkId> set = BY_REGION.get(rp);
        if (set == null) {
            return true;
        }

        set.remove(id);
        if (set.isEmpty()) {
            BY_REGION.remove(rp);
            return true;
        }
        return false;
    }

    private static RegionPos regionOf(ChunkId id) {
        return new RegionPos(
                id.dim(),
                Math.floorDiv(id.cx(), RegionKey.REGION_CHUNKS),
                Math.floorDiv(id.cz(), RegionKey.REGION_CHUNKS));
    }

    /**
     * Configured radius, floored to the server view distance: the
     * exploration front generates (and diffuses) chunks up to the view
     * distance — a smaller quarantine radius would broadcast a painted ring
     * with a hole at the hidden player's position, and the hole itself
     * reveals it.
     */
    private static int effectiveRadius(MinecraftServer server) {
        return Math.max(
                PrivacyServerConfig.QUARANTINE_RADIUS_CHUNKS.get(),
                server.getPlayerList().getViewDistance() + 1);
    }

    private static boolean isNearHiddenPlayer(ServerLevel level, int cx, int cz) {
        int radius = effectiveRadius(level.getServer());
        for (Player player : level.players()) {
            if (!SyncService.isHidden(player.getUUID())) {
                continue;
            }

            ChunkPos pos = player.chunkPosition();
            if (Math.max(Math.abs(pos.x - cx), Math.abs(pos.z - cz)) <= radius) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ persistence

    private static synchronized void load() {
        PENDING.clear();
        BY_REGION.clear();
        if (!Files.exists(file)) {
            return;
        }

        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, Long> raw = GSON.fromJson(r, MAP_TYPE);
            if (raw == null) {
                return;
            }

            raw.forEach((k, deadline) -> {
                ChunkId id = parseChunkId(k);
                if (id != null && deadline != null) {
                    PENDING.put(id, deadline);
                    BY_REGION
                            .computeIfAbsent(regionOf(id), rp -> ConcurrentHashMap.newKeySet())
                            .add(id);
                }
            });
            if (!PENDING.isEmpty()) {
                LOGGER.info("SharedJourney: {} quarantined chunk(s) restored", PENDING.size());
            }
        } catch (Exception e) {
            // Corrupted file: start from scratch — worst case an early reveal.
            LOGGER.warn("SharedJourney: unreadable {}, quarantine reset", FILE_NAME);
        }
    }

    /** Saves the pending set (world save, shutdown). */
    public static synchronized void save() {
        if (file == null) {
            return;
        }

        try {
            Map<String, Long> raw = new HashMap<>();
            PENDING.forEach((id, deadline) -> raw.put(id.dim().location() + "|" + id.cx() + "|" + id.cz(), deadline));
            RegionStorage.writeAtomically(file, GSON.toJson(raw, MAP_TYPE).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.error("Failed to save {}", file, e);
        }
    }

    private static ChunkId parseChunkId(String s) {
        String[] p = s.split("\\|");
        if (p.length != 3) {
            return null;
        }

        ResourceLocation dim = ResourceLocation.tryParse(p[0]);
        if (dim == null) {
            return null;
        }

        try {
            return new ChunkId(
                    ResourceKey.create(Registries.DIMENSION, dim), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
