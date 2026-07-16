package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.api.ChunkLayerRenderer;
import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.config.EngineServerConfig;
import fr.cheesegrinder.sharedjourney.common.config.LayersServerConfig;
import fr.cheesegrinder.sharedjourney.common.region.HoverRegionData;
import fr.cheesegrinder.sharedjourney.common.region.RegionHashes;
import fr.cheesegrinder.sharedjourney.common.region.RegionIndex;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;
import fr.cheesegrinder.sharedjourney.common.region.RegionStorage;
import fr.cheesegrinder.sharedjourney.server.render.ChunkColorizer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * Server-side source of truth (spec §3.1 and §4).
 *
 * - 512x512 px regions in RAM, persisted as PNG under
 *   world/data/sharedjourney/[dimension]/[layer]/region_X_Z.png
 * - index.json: {RegionKey -> Timestamp} registry (serialization of the RAM registry)
 * - ASYNC engine: the server tick only drains the "dirty" chunk queue and
 *   submits tasks to a thread pool sized min(cores-2, config.maxWorkerThreads),
 *   floor 1. Pixel computation, PNG encoding and disk writes all happen off
 *   the main thread.
 *   Note: tasks read the ChunkAccess read-only; the chunk is resolved on the
 *   main thread (getChunkNow) before submission.
 */
public final class MapManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static MapManager INSTANCE;

    public static void init(MinecraftServer server, Map<String, ChunkLayerRenderer> customLayers) {
        INSTANCE = new MapManager(server, customLayers);
    }

    public static void shutdown() {
        if (INSTANCE != null) {
            INSTANCE.close();
            INSTANCE = null;
        }
    }

    public static MapManager get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------

    private final MinecraftServer server;
    private final Path root;
    private final RegionIndex index = new RegionIndex();
    /** Served-bytes hashes, for the client cache integrity check (handshake). */
    private final RegionHashes hashes = new RegionHashes();

    private final Map<RegionKey, RegionImage> regions = new ConcurrentHashMap<>();
    /** Hover sidecars (INFO pseudo-layer), keyed like the image regions. */
    private final Map<RegionKey, HoverRegion> hoverRegions = new ConcurrentHashMap<>();

    private final Map<String, ChunkLayerRenderer> customLayers;

    private final ExecutorService workers;
    private final int workerCount;
    private final AtomicInteger tasksInFlight = new AtomicInteger();

    /** Queue of chunks to (re)render, deduplicated (dirty marking, spec §4). */
    private final ArrayDeque<QueuedChunk> renderQueue = new ArrayDeque<>();

    private final Set<QueuedChunk> queued = new LinkedHashSet<>();

    private record QueuedChunk(ResourceKey<Level> dim, int cx, int cz) {}

    /**
     * Cave band unlocks awaiting a render (anti-exploit): a CAVE band is
     * only painted if a player actually explored it.
     */
    private final Set<CaveUnlock> caveUnlocks = ConcurrentHashMap.newKeySet();

    private record CaveUnlock(ResourceKey<Level> dim, int band, int cx, int cz) {}

    private MapManager(MinecraftServer server, Map<String, ChunkLayerRenderer> customLayers) {
        this.server = server;
        this.customLayers = customLayers;
        this.root = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("sharedjourney");
        RegionStorage.migrateLegacyCaveFolders(root);
        this.index.load(root.resolve(RegionIndex.FILE_NAME));
        this.hashes.load(root.resolve(RegionHashes.FILE_NAME));

        // Allocation formula from spec §4: min(cores-2, config), floor 1.
        // Math.clamp would throw on machines with <= 2 cores (min > max).
        int cores = Runtime.getRuntime().availableProcessors();
        this.workerCount = Math.max(1, Math.min(EngineServerConfig.MAX_WORKER_THREADS.get(), cores - 2));
        this.workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "SharedJourney-Render");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        LOGGER.info(
                "SharedJourney: render engine initialized ({} thread(s), index: {} region(s))",
                workerCount,
                index.size());
    }

    private void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        saveAll();
    }

    // ------------------------------------------------------------------ render queue

    public synchronized void enqueueChunk(ServerLevel level, int cx, int cz) {
        QueuedChunk q = new QueuedChunk(level.dimension(), cx, cz);
        if (queued.add(q)) {
            renderQueue.add(q);
        }
    }

    public synchronized int queueSize() {
        return renderQueue.size();
    }

    public int tasksInFlight() {
        return tasksInFlight.get();
    }

    public int workerCount() {
        return workerCount;
    }

    /**
     * Server tick: resolves chunks on the main thread (mandatory) then
     * delegates all computation to the pool. Near-zero main-thread cost.
     */
    public void tick() {
        int budget = EngineServerConfig.RENDER_CHUNKS_PER_TICK.get();
        // Avoid flooding the pool when the disk/CPU cannot keep up.
        int maxInFlight = workerCount * 8;
        while (budget-- > 0 && tasksInFlight.get() < maxInFlight) {
            QueuedChunk q;
            synchronized (this) {
                q = renderQueue.poll();
                if (q == null) {
                    return;
                }

                queued.remove(q);
            }
            ServerLevel level = server.getLevel(q.dim());
            if (level == null) {
                continue;
            }

            ChunkAccess chunk = level.getChunkSource().getChunkNow(q.cx(), q.cz());
            // Unloaded in the meantime.
            if (chunk == null) {
                continue;
            }

            // Hidden-player gate: every render path converges here (or on
            // renderNow), on the main thread — player positions readable.
            QuarantineService.Verdict verdict = QuarantineService.evaluate(level, q.cx(), q.cz());
            if (verdict == QuarantineService.Verdict.DROP) {
                continue;
            }

            submitRender(level, chunk, verdict == QuarantineService.Verdict.QUARANTINE);
        }
    }

    /**
     * Immediate render of an already-resolved chunk (reference coming from
     * an event). Essential for freshly generated chunks (Chunky
     * pregeneration): they are unloaded as soon as they are generated, and a
     * deferred resolution on the next tick (getChunkNow) would
     * systematically miss them. If the pool is saturated, fall back to the
     * regular queue rather than holding an unbounded number of unloaded
     * chunks in memory.
     */
    public void renderNow(ServerLevel level, ChunkAccess chunk) {
        QuarantineService.Verdict verdict = QuarantineService.evaluate(level, chunk.getPos().x, chunk.getPos().z);
        if (verdict == QuarantineService.Verdict.DROP) {
            return;
        }

        if (tasksInFlight.get() < workerCount * 8) {
            submitRender(level, chunk, verdict == QuarantineService.Verdict.QUARANTINE);
        } else {
            // Re-evaluated at resolution time on the next ticks.
            enqueueChunk(level, chunk.getPos().x, chunk.getPos().z);
        }
    }

    /** Submits the render of a resolved chunk to the worker pool. */
    private void submitRender(ServerLevel level, ChunkAccess chunk, boolean quarantined) {
        // 3x3 neighborhood (biomes only, BIOMES status is enough, never any
        // generation): the game's biome zoom reads up to one cell beyond the
        // chunk, so neighbors are needed for faithful borders up to the
        // edge. Resolved here, on the main thread; a missing neighbor is
        // approximated by the center chunk in ChunkColorizer.
        ChunkPos cp = chunk.getPos();
        ChunkAccess[] neighbors = new ChunkAccess[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                neighbors[(dx + 1) + (dz + 1) * 3] =
                        level.getChunkSource().getChunk(cp.x + dx, cp.z + dz, ChunkStatus.BIOMES, false);
            }
        }
        neighbors[4] = chunk;

        tasksInFlight.incrementAndGet();
        try {
            workers.submit(() -> {
                try {
                    renderChunk(level, chunk, neighbors, quarantined);
                } catch (Throwable t) {
                    LOGGER.error(
                            "Failed to render chunk {},{} in {}",
                            chunk.getPos().x,
                            chunk.getPos().z,
                            level.dimension().location(),
                            t);
                } finally {
                    tasksInFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            tasksInFlight.decrementAndGet(); // server shutdown in progress
        }
    }

    /**
     * Renders every active layer of a chunk (runs on a worker). A
     * quarantined chunk (near a hidden player) is written to the real region
     * only; the public variant served to distant players stays frozen until
     * the QuarantineService drain.
     */
    private void renderChunk(ServerLevel level, ChunkAccess chunk, ChunkAccess[] neighbors, boolean quarantined) {
        EnumSet<MapLayer> layers = LayersServerConfig.layersFor(level.dimension());
        ChunkPos cp = chunk.getPos();
        List<RegionKey> touched = new ArrayList<>(layers.size() + 4);
        for (MapLayer layer : layers) {
            if (layer == MapLayer.CAVE) {
                for (int band : LayersServerConfig.CAVE_BANDS.get()) {
                    RegionKey key = RegionKey.of(level.dimension(), layer, band, cp.x, cp.z);
                    CaveUnlock unlock = new CaveUnlock(level.dimension(), band, cp.x, cp.z);
                    // Anti-exploit: a cave band is only painted if a player
                    // explored it (CaveTracker unlock) or if it already was
                    // (update of a known area).
                    if (!caveUnlocks.contains(unlock) && !isChunkPainted(key, cp.x, cp.z)) {
                        continue;
                    }

                    int[] pixels = ChunkColorizer.render(level, chunk, neighbors, layer, band);
                    if (writeChunk(key, cp.x, cp.z, pixels, quarantined)) {
                        touched.add(key);
                    }

                    caveUnlocks.remove(unlock);
                }
            } else {
                int[] pixels = ChunkColorizer.render(level, chunk, neighbors, layer, 0);
                RegionKey key = RegionKey.of(level.dimension(), layer, 0, cp.x, cp.z);
                if (writeChunk(key, cp.x, cp.z, pixels, quarantined)) {
                    touched.add(key);
                }
            }
        }
        // Hover sidecar (INFO pseudo-layer): produced here, while the chunk
        // is in hand — the fullscreen hover info then works fully
        // client-side, with no on-demand chunk loading (anti timing-attack).
        if (!layers.isEmpty()) {
            RegionKey hoverKey = writeHoverChunk(level, chunk, quarantined);
            if (hoverKey != null) {
                touched.add(hoverKey);
            }
        }
        // Immediate push to affected players (without waiting for the periodic delta).
        SyncService.pushRegionUpdates(server, touched);
        // NB: custom layers (LayerRegisterEvent) are collected but their
        // storage/sync pipeline is not wired yet — see README §Limites.
    }

    /** Region keys known to the index (copy, for the full regeneration). */
    public Set<RegionKey> indexedRegions() {
        return index.snapshot().keySet();
    }

    /** Has the chunk already been painted (at least one opaque pixel on the first active layer)? */
    public boolean isChunkRendered(ServerLevel level, int cx, int cz) {
        EnumSet<MapLayer> layers = LayersServerConfig.layersFor(level.dimension());
        if (layers.isEmpty()) {
            return true;
        }

        MapLayer probe = layers.iterator().next();
        int band = probe == MapLayer.CAVE ? firstCaveBand() : 0;
        return isChunkPainted(RegionKey.of(level.dimension(), probe, band, cx, cz), cx, cz);
    }

    /** Does the chunk have at least one opaque pixel on the given region? */
    private boolean isChunkPainted(RegionKey key, int cx, int cz) {
        // Fast path: if the index does not know the region, nothing was painted.
        if (index.get(key) < 0 && !regions.containsKey(key)) {
            return false;
        }

        RegionImage img = getOrLoad(key, false);
        if (img == null) {
            return false;
        }

        int px = Math.floorMod(cx, RegionKey.REGION_CHUNKS) * 16;
        int pz = Math.floorMod(cz, RegionKey.REGION_CHUNKS) * 16;
        synchronized (img) {
            return (img.pixels[px + pz * RegionKey.REGION_BLOCKS] >>> 24) != 0;
        }
    }

    /**
     * Marks a cave band as explored by a player (CaveTracker): the chunk
     * will be painted on that band on the next render. No-op if the band is
     * already painted there.
     */
    public void unlockCave(ServerLevel level, int band, int cx, int cz) {
        RegionKey key = RegionKey.of(level.dimension(), MapLayer.CAVE, band, cx, cz);
        if (isChunkPainted(key, cx, cz)) {
            return;
        }

        if (caveUnlocks.add(new CaveUnlock(level.dimension(), band, cx, cz))) {
            enqueueChunk(level, cx, cz);
        }
    }

    private int firstCaveBand() {
        var bands = LayersServerConfig.CAVE_BANDS.get();
        return bands.isEmpty() ? 0 : bands.getFirst();
    }

    // ------------------------------------------------------------------ quarantine publication

    /**
     * Publishes a drained quarantined chunk (QuarantineService): copies its
     * pixels/hover data from the real region into the public variant on
     * every layer. When the region has no pending chunk left, the variant is
     * dropped entirely (public == real again) and the real version is bumped
     * — a distant player may hold a public version equal to the real one
     * (same-millisecond writes), the bump forces the re-push. Main thread.
     * Returns the touched keys, for the sync push.
     */
    public List<RegionKey> publishChunk(ResourceKey<Level> dim, int cx, int cz, boolean regionClean) {
        List<RegionKey> touched = new ArrayList<>();
        for (MapLayer layer : LayersServerConfig.layersFor(dim)) {
            if (layer == MapLayer.CAVE) {
                for (int band : LayersServerConfig.CAVE_BANDS.get()) {
                    publishImageChunk(RegionKey.of(dim, layer, band, cx, cz), cx, cz, regionClean, touched);
                }
            } else {
                publishImageChunk(RegionKey.of(dim, layer, 0, cx, cz), cx, cz, regionClean, touched);
            }
        }
        publishHoverChunk(RegionKey.of(dim, MapLayer.INFO, 0, cx, cz), cx, cz, regionClean, touched);
        return touched;
    }

    private void publishImageChunk(RegionKey key, int cx, int cz, boolean regionClean, List<RegionKey> touched) {
        RegionImage img = getOrLoad(key, false);
        if (img == null) {
            return;
        }

        int ox = Math.floorMod(cx, RegionKey.REGION_CHUNKS) * 16;
        int oz = Math.floorMod(cz, RegionKey.REGION_CHUNKS) * 16;
        synchronized (img) {
            if (img.publicPixels == null) {
                return;
            }

            if (regionClean) {
                // Last pending chunk of the region: real == public once
                // dropped, no need to copy.
                img.publicPixels = null;
                img.publicDirty = false;
                img.cachedPublicPng = null;
                img.version = Math.max(img.version + 1, System.currentTimeMillis());
                img.dirty = true;
                index.put(key, img.version);
            } else {
                for (int z = 0; z < 16; z++) {
                    System.arraycopy(
                            img.pixels,
                            ox + (oz + z) * RegionKey.REGION_BLOCKS,
                            img.publicPixels,
                            ox + (oz + z) * RegionKey.REGION_BLOCKS,
                            16);
                }
                img.publicVersion = Math.max(img.publicVersion + 1, System.currentTimeMillis());
                img.publicDirty = true;
                img.cachedPublicPng = null;
            }
        }
        if (regionClean) {
            deletePubFile(key);
        }
        touched.add(key);
    }

    private void publishHoverChunk(RegionKey key, int cx, int cz, boolean regionClean, List<RegionKey> touched) {
        HoverRegion region = hoverRegion(key, false);
        if (region == null) {
            return;
        }

        synchronized (region) {
            if (region.publicData == null) {
                return;
            }

            if (regionClean) {
                region.publicData = null;
                region.publicDirty = false;
                region.cachedPublicBlob = null;
                region.version = Math.max(region.version + 1, System.currentTimeMillis());
                region.dirty = true;
                index.put(key, region.version);
            } else {
                region.data.copyChunkTo(
                        region.publicData,
                        Math.floorMod(cx, RegionKey.REGION_CHUNKS),
                        Math.floorMod(cz, RegionKey.REGION_CHUNKS));
                region.publicVersion = Math.max(region.publicVersion + 1, System.currentTimeMillis());
                region.publicDirty = true;
                region.cachedPublicBlob = null;
            }
        }
        if (regionClean) {
            deletePubFile(key);
        }
        touched.add(key);
    }

    // ------------------------------------------------------------------ writes (workers)

    /**
     * Writes a rendered chunk into its region. Returns false — without
     * bumping the version, so nothing is re-pushed — when the pixels are
     * identical to what the region already holds (frequent since block
     * changes re-render the whole 3x3 neighborhood for lighting).
     */
    private boolean writeChunk(RegionKey key, int cx, int cz, int[] chunkPixels, boolean quarantined) {
        RegionImage img = getOrLoad(key, true);
        int ox = Math.floorMod(cx, RegionKey.REGION_CHUNKS) * 16;
        int oz = Math.floorMod(cz, RegionKey.REGION_CHUNKS) * 16;
        long version;

        synchronized (Objects.requireNonNull(img)) {
            if (chunkUnchanged(img, ox, oz, chunkPixels)) {
                return false;
            }

            if (quarantined && img.publicPixels == null) {
                // Freeze the pre-quarantine state: distant players keep
                // being served this snapshot until the chunks drain.
                img.publicPixels = img.pixels.clone();
                img.publicVersion = img.version;
                img.publicDirty = true;
                img.cachedPublicPng = null;
            }
            for (int z = 0; z < 16; z++) {
                System.arraycopy(chunkPixels, z * 16, img.pixels, ox + (oz + z) * RegionKey.REGION_BLOCKS, 16);
                if (!quarantined && img.publicPixels != null) {
                    // Normal write while the region holds a public variant
                    // (another chunk is quarantined): mirror it.
                    System.arraycopy(
                            chunkPixels, z * 16, img.publicPixels, ox + (oz + z) * RegionKey.REGION_BLOCKS, 16);
                }
            }
            img.version = Math.max(img.version + 1, System.currentTimeMillis());
            img.dirty = true;
            img.cachedPng = null;
            version = img.version;
            if (!quarantined && img.publicPixels != null) {
                img.publicVersion = Math.max(img.publicVersion + 1, System.currentTimeMillis());
                img.publicDirty = true;
                img.cachedPublicPng = null;
            }
        }
        index.put(key, version); // RAM registry; serialized by saveAll()
        return true;
    }

    /** Are the region's pixels at this chunk already exactly these? */
    private static boolean chunkUnchanged(RegionImage img, int ox, int oz, int[] chunkPixels) {
        for (int z = 0; z < 16; z++) {
            int rowStart = ox + (oz + z) * RegionKey.REGION_BLOCKS;
            if (!Arrays.equals(img.pixels, rowStart, rowStart + 16, chunkPixels, z * 16, z * 16 + 16)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Extracts and stores the chunk's hover data (surface heights, blocks,
     * biomes) into its region sidecar. Runs on a worker, read-only chunk
     * access like the layer renderers. Returns the touched INFO region key,
     * or null — no version bump, nothing re-pushed — when the extracted
     * data is identical to what the sidecar already holds.
     */
    private RegionKey writeHoverChunk(ServerLevel level, ChunkAccess chunk, boolean quarantined) {
        ChunkPos cp = chunk.getPos();
        short[] heights = new short[HoverRegionData.COLUMNS];
        String[] blockIds = new String[HoverRegionData.COLUMNS];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int i = dz * 16 + dx;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, dx, dz);
                heights[i] = (short) y;
                BlockState state = chunk.getBlockState(pos.set(cp.getMinBlockX() + dx, y, cp.getMinBlockZ() + dz));
                blockIds[i] = state.isAir()
                        ? ""
                        : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            }
        }

        String[] biomeIds = new String[HoverRegionData.BIOME_CELLS];
        for (int bz = 0; bz < 4; bz++) {
            for (int bx = 0; bx < 4; bx++) {
                // Center of the 4x4 cell, at its column's surface Y.
                int dx = bx * 4 + 2;
                int dz = bz * 4 + 2;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, dx, dz);
                biomeIds[bz * 4 + bx] = chunk.getNoiseBiome(
                                (cp.getMinBlockX() + dx) >> 2, y >> 2, (cp.getMinBlockZ() + dz) >> 2)
                        .unwrapKey()
                        .map(k -> k.location().toString())
                        .orElse("");
            }
        }

        RegionKey key = RegionKey.of(level.dimension(), MapLayer.INFO, 0, cp.x, cp.z);
        HoverRegion region = hoverRegion(key, true);
        long version;
        int localCx = Math.floorMod(cp.x, RegionKey.REGION_CHUNKS);
        int localCz = Math.floorMod(cp.z, RegionKey.REGION_CHUNKS);
        synchronized (Objects.requireNonNull(region)) {
            if (region.data.chunkEquals(localCx, localCz, heights, blockIds, biomeIds)) {
                return null;
            }

            if (quarantined && region.publicData == null) {
                // Freeze the pre-quarantine hover data (same rule as the
                // region images: heights/blocks would leak the activity).
                HoverRegionData copy = HoverRegionData.deserialize(region.data.serialize());
                region.publicData = copy != null ? copy : new HoverRegionData();
                region.publicVersion = region.version;
                region.publicDirty = true;
                region.cachedPublicBlob = null;
            }
            region.data.putChunk(localCx, localCz, heights, blockIds, biomeIds);
            region.version = Math.max(region.version + 1, System.currentTimeMillis());
            region.dirty = true;
            region.cachedBlob = null;
            version = region.version;
            if (!quarantined && region.publicData != null) {
                region.publicData.putChunk(localCx, localCz, heights, blockIds, biomeIds);
                region.publicVersion = Math.max(region.publicVersion + 1, System.currentTimeMillis());
                region.publicDirty = true;
                region.cachedPublicBlob = null;
            }
        }
        index.put(key, version);
        return key;
    }

    private HoverRegion hoverRegion(RegionKey key, boolean createIfMissing) {
        HoverRegion region = hoverRegions.get(key);
        if (region != null) {
            return region;
        }

        Path file = pathOf(key);
        if (Files.exists(file)) {
            try {
                HoverRegionData data = HoverRegionData.deserialize(Files.readAllBytes(file));
                if (data != null) {
                    HoverRegion loaded = new HoverRegion(data);
                    long v = index.get(key);
                    loaded.version =
                            v >= 0 ? v : Files.getLastModifiedTime(file).toMillis();
                    loadPublicHover(key, loaded);
                    HoverRegion prev = hoverRegions.putIfAbsent(key, loaded);
                    return prev != null ? prev : loaded;
                }
            } catch (IOException e) {
                LOGGER.error("Failed to read {}", file, e);
            }
        }
        if (!createIfMissing) {
            return null;
        }

        HoverRegion fresh = new HoverRegion(new HoverRegionData());
        HoverRegion prev = hoverRegions.putIfAbsent(key, fresh);
        return prev != null ? prev : fresh;
    }

    // ------------------------------------------------------------------ reads / versions

    /**
     * Version served to a player: the public variant's when the region is
     * under quarantine and the player is not trusted for it, the real one
     * otherwise.
     */
    public long versionOf(RegionKey key, boolean trusted) {
        if (!trusted && QuarantineService.hasPending(key.dimension(), key.rx(), key.rz())) {
            return publicVersionOf(key);
        }

        return versionOf(key);
    }

    private long publicVersionOf(RegionKey key) {
        if (key.layer() == MapLayer.INFO) {
            HoverRegion region = hoverRegion(key, false);
            if (region == null) {
                return -1;
            }

            synchronized (region) {
                // No variant: no quarantined write hit this layer, public == real.
                return region.publicData != null ? region.publicVersion : region.version;
            }
        }

        RegionImage img = getOrLoad(key, false);
        if (img == null) {
            return -1;
        }

        synchronized (img) {
            return img.publicPixels != null ? img.publicVersion : img.version;
        }
    }

    /**
     * Client cache integrity (handshake): true when the client declares a
     * region version for which the server recorded the served-bytes hash,
     * but the hash the client RECOMPUTED from its cached file differs — the
     * file was modified locally and must be re-pushed. Any other combination
     * (no recorded hash, hash unknown client-side, other version) falls back
     * to the plain version comparison.
     */
    public boolean isTampered(RegionKey key, long clientVersion, String clientSha256) {
        if (clientSha256 == null || clientSha256.isEmpty()) {
            return false;
        }

        String expected = hashes.hashFor(key, clientVersion);
        return expected != null && !expected.equalsIgnoreCase(clientSha256);
    }

    /** Version of a region: index (truth) > file (mtime) > -1. */
    public long versionOf(RegionKey key) {
        long fromIndex = index.get(key);
        if (fromIndex >= 0) {
            return fromIndex;
        }

        if (key.layer() == MapLayer.INFO) {
            HoverRegion hover = hoverRegions.get(key);
            if (hover != null) {
                return hover.version;
            }
        }

        RegionImage img = regions.get(key);
        if (img != null) {
            return img.version;
        }

        Path file = pathOf(key);
        if (Files.exists(file)) {
            try {
                long v = Files.getLastModifiedTime(file).toMillis();
                index.put(key, v);
                return v;
            } catch (IOException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Serialized bytes served to a player: the public variant when the
     * region is under quarantine and the player is not trusted for it, the
     * real data otherwise.
     */
    public byte[] dataOf(RegionKey key, boolean trusted) {
        if (!trusted && QuarantineService.hasPending(key.dimension(), key.rx(), key.rz())) {
            return publicDataOf(key);
        }

        return dataOf(key);
    }

    private byte[] publicDataOf(RegionKey key) {
        if (key.layer() == MapLayer.INFO) {
            HoverRegion region = hoverRegion(key, false);
            if (region == null) {
                return null;
            }

            synchronized (region) {
                if (region.publicData == null) {
                    return hoverBlobOf(key);
                }

                if (region.cachedPublicBlob == null) {
                    region.cachedPublicBlob = region.publicData.serialize();
                }
                return region.cachedPublicBlob;
            }
        }

        RegionImage img = getOrLoad(key, false);
        if (img == null) {
            return null;
        }

        synchronized (img) {
            if (img.publicPixels != null) {
                if (img.cachedPublicPng == null) {
                    img.cachedPublicPng = encodePng(img.publicPixels);
                }
                return img.cachedPublicPng;
            }
        }
        // No variant on this layer: public == real.
        return dataOf(key);
    }

    /**
     * Serialized bytes of a region for the sync: PNG for the image layers,
     * hover blob for the INFO data layer (both cached until rewritten).
     */
    public byte[] dataOf(RegionKey key) {
        if (key.layer() == MapLayer.INFO) {
            return hoverBlobOf(key);
        }

        RegionImage img = getOrLoad(key, false);
        if (img == null) {
            return null;
        }

        synchronized (img) {
            if (img.cachedPng == null) {
                img.cachedPng = encodePng(img.pixels);
            }

            if (img.cachedPng != null) {
                // Recorded even when the PNG was already cached: a version
                // bump without a pixel change (quarantine drain) must refresh
                // the (version, hash) pair too.
                hashes.ensure(key, img.version, img.cachedPng);
            }

            return img.cachedPng;
        }
    }

    private byte[] hoverBlobOf(RegionKey key) {
        HoverRegion region = hoverRegion(key, false);
        if (region == null) {
            return null;
        }

        synchronized (region) {
            if (region.cachedBlob == null) {
                region.cachedBlob = region.data.serialize();
            }

            hashes.ensure(key, region.version, region.cachedBlob);
            return region.cachedBlob;
        }
    }

    private static byte[] encodePng(int[] pixels) {
        try {
            BufferedImage bi =
                    new BufferedImage(RegionKey.REGION_BLOCKS, RegionKey.REGION_BLOCKS, BufferedImage.TYPE_INT_ARGB);
            bi.setRGB(0, 0, RegionKey.REGION_BLOCKS, RegionKey.REGION_BLOCKS, pixels, 0, RegionKey.REGION_BLOCKS);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            ImageIO.write(bi, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            LOGGER.error("Failed to encode PNG", e);
            return null;
        }
    }

    private RegionImage getOrLoad(RegionKey key, boolean createIfMissing) {
        RegionImage img = regions.get(key);
        if (img != null) {
            return img;
        }

        Path file = pathOf(key);
        if (Files.exists(file)) {
            try {
                BufferedImage bi = ImageIO.read(file.toFile());
                RegionImage loaded = new RegionImage();
                bi.getRGB(
                        0,
                        0,
                        RegionKey.REGION_BLOCKS,
                        RegionKey.REGION_BLOCKS,
                        loaded.pixels,
                        0,
                        RegionKey.REGION_BLOCKS);
                long v = index.get(key);
                loaded.version = v >= 0 ? v : Files.getLastModifiedTime(file).toMillis();
                loadPublicImage(key, loaded);
                RegionImage prev = regions.putIfAbsent(key, loaded);
                return prev != null ? prev : loaded;
            } catch (IOException e) {
                LOGGER.error("Failed to read {}", file, e);
            }
        }
        if (!createIfMissing) {
            return null;
        }

        RegionImage fresh = new RegionImage();
        RegionImage prev = regions.putIfAbsent(key, fresh);
        return prev != null ? prev : fresh;
    }

    private Path pathOf(RegionKey key) {
        String dim = key.dimension().location().getPath(); // "overworld" as per the spec
        if (!key.dimension().location().getNamespace().equals("minecraft")) {
            dim = key.dimension().location().toString().replace(':', '_');
        }
        return root.resolve(dim).resolve(key.layer().folderName(key.caveBand())).resolve(key.fileName());
    }

    /** Sidecar of the public variant: region_X_Z.pub.png / region_X_Z.pub.bin. */
    private Path pubPathOf(RegionKey key) {
        Path real = pathOf(key);
        String name = real.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return real.resolveSibling(name.substring(0, dot) + ".pub" + name.substring(dot));
    }

    private void deletePubFile(RegionKey key) {
        try {
            Files.deleteIfExists(pubPathOf(key));
        } catch (IOException e) {
            LOGGER.error("Failed to delete {}", pubPathOf(key), e);
        }
    }

    /**
     * Restores the public variant of a region image from its sidecar (server
     * restart with pending quarantine). A sidecar whose region has no
     * pending chunk anymore is stale: deleted. NB: the reverse case (pending
     * chunks but sidecar lost, e.g. crash before a save) falls back to the
     * real data — accepted residual risk.
     */
    private void loadPublicImage(RegionKey key, RegionImage img) {
        Path pub = pubPathOf(key);
        if (!Files.exists(pub)) {
            return;
        }

        if (!QuarantineService.hasPending(key.dimension(), key.rx(), key.rz())) {
            deletePubFile(key);
            return;
        }

        try {
            BufferedImage bi = ImageIO.read(pub.toFile());
            int[] pixels = new int[RegionKey.REGION_BLOCKS * RegionKey.REGION_BLOCKS];
            bi.getRGB(0, 0, RegionKey.REGION_BLOCKS, RegionKey.REGION_BLOCKS, pixels, 0, RegionKey.REGION_BLOCKS);
            img.publicPixels = pixels;
            img.publicVersion = Files.getLastModifiedTime(pub).toMillis();
        } catch (IOException e) {
            LOGGER.error("Failed to read {}", pub, e);
        }
    }

    /** Same as {@link #loadPublicImage} for the hover sidecars. */
    private void loadPublicHover(RegionKey key, HoverRegion region) {
        Path pub = pubPathOf(key);
        if (!Files.exists(pub)) {
            return;
        }

        if (!QuarantineService.hasPending(key.dimension(), key.rx(), key.rz())) {
            deletePubFile(key);
            return;
        }

        try {
            HoverRegionData data = HoverRegionData.deserialize(Files.readAllBytes(pub));
            if (data != null) {
                region.publicData = data;
                region.publicVersion = Files.getLastModifiedTime(pub).toMillis();
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read {}", pub, e);
        }
    }

    // ------------------------------------------------------------------ persistence

    /** Async flush: saves modified regions + index.json (world save, shutdown). */
    public void saveAllAsync() {
        workers.submit(this::saveAll);
    }

    public synchronized void saveAll() {
        int saved = 0;
        for (Map.Entry<RegionKey, RegionImage> e : regions.entrySet()) {
            RegionImage img = e.getValue();
            byte[] png;
            long version;
            synchronized (img) {
                if (!img.dirty) {
                    continue;
                }

                png = img.cachedPng != null ? img.cachedPng : encodePng(img.pixels);
                img.cachedPng = png;
                version = img.version;
                img.dirty = false;
            }
            if (png == null) {
                continue;
            }

            hashes.ensure(e.getKey(), version, png);
            Path file = pathOf(e.getKey());
            try {
                RegionStorage.writeAtomically(file, png);
                Files.setLastModifiedTime(file, FileTime.fromMillis(version));
                saved++;
            } catch (IOException ex) {
                LOGGER.error("Failed to save {}", file, ex);
                synchronized (img) {
                    img.dirty = true;
                }
            }
        }
        // Public variants (hidden-player quarantine): .pub sidecars, mtime =
        // public version (the version source on restart).
        for (Map.Entry<RegionKey, RegionImage> e : regions.entrySet()) {
            RegionImage img = e.getValue();
            byte[] png;
            long version;
            synchronized (img) {
                if (!img.publicDirty || img.publicPixels == null) {
                    continue;
                }

                png = img.cachedPublicPng != null ? img.cachedPublicPng : encodePng(img.publicPixels);
                img.cachedPublicPng = png;
                version = img.publicVersion;
                img.publicDirty = false;
            }
            if (png == null) {
                continue;
            }

            Path file = pubPathOf(e.getKey());
            try {
                RegionStorage.writeAtomically(file, png);
                Files.setLastModifiedTime(file, FileTime.fromMillis(version));
                saved++;
            } catch (IOException ex) {
                LOGGER.error("Failed to save {}", file, ex);
                synchronized (img) {
                    img.publicDirty = true;
                }
            }
        }
        for (Map.Entry<RegionKey, HoverRegion> e : hoverRegions.entrySet()) {
            HoverRegion region = e.getValue();
            byte[] blob;
            long version;
            synchronized (region) {
                if (!region.publicDirty || region.publicData == null) {
                    continue;
                }

                blob = region.cachedPublicBlob != null ? region.cachedPublicBlob : region.publicData.serialize();
                region.cachedPublicBlob = blob;
                version = region.publicVersion;
                region.publicDirty = false;
            }
            Path file = pubPathOf(e.getKey());
            try {
                RegionStorage.writeAtomically(file, blob);
                Files.setLastModifiedTime(file, FileTime.fromMillis(version));
                saved++;
            } catch (IOException ex) {
                LOGGER.error("Failed to save {}", file, ex);
                synchronized (region) {
                    region.publicDirty = true;
                }
            }
        }
        for (Map.Entry<RegionKey, HoverRegion> e : hoverRegions.entrySet()) {
            HoverRegion region = e.getValue();
            byte[] blob;
            long version;
            synchronized (region) {
                if (!region.dirty) {
                    continue;
                }

                blob = region.cachedBlob != null ? region.cachedBlob : region.data.serialize();
                region.cachedBlob = blob;
                version = region.version;
                region.dirty = false;
            }
            hashes.ensure(e.getKey(), version, blob);
            Path file = pathOf(e.getKey());
            try {
                RegionStorage.writeAtomically(file, blob);
                Files.setLastModifiedTime(file, FileTime.fromMillis(version));
                saved++;
            } catch (IOException ex) {
                LOGGER.error("Failed to save {}", file, ex);
                synchronized (region) {
                    region.dirty = true;
                }
            }
        }
        try {
            index.save(root.resolve(RegionIndex.FILE_NAME));
        } catch (IOException ex) {
            LOGGER.error("Failed to save the index", ex);
        }
        try {
            hashes.save(root.resolve(RegionHashes.FILE_NAME));
        } catch (IOException ex) {
            LOGGER.error("Failed to save the hash registry", ex);
        }
        if (saved > 0) {
            LOGGER.info("SharedJourney: {} region(s) saved", saved);
        }
    }

    // ------------------------------------------------------------------ stats (spec §7)

    /** RAM / queue / thread state for /sj stats. */
    public String engineStats() {
        long dirty = regions.values().stream().filter(r -> r.dirty).count();
        long ramMb = regions.size() * (long) RegionKey.REGION_BLOCKS * RegionKey.REGION_BLOCKS * 4 / (1024 * 1024);
        return "Engine: " + workerCount + " thread(s), " + tasksInFlight.get() + " task(s) in flight | "
                + "RAM regions: " + regions.size() + " (~" + ramMb + " MB, " + dirty + " dirty) | "
                + "Index: " + index.size() + " entries | Render queue: " + queueSize() + " chunk(s) | "
                + "Quarantine: " + QuarantineService.pendingCount() + " chunk(s)";
    }

    private static final class RegionImage {
        final int[] pixels = new int[RegionKey.REGION_BLOCKS * RegionKey.REGION_BLOCKS];
        long version = System.currentTimeMillis();
        boolean dirty = false;
        byte[] cachedPng = null;

        /**
         * "Public" variant served to players not trusted for the region
         * (hidden-player quarantine): pixels frozen before the quarantined
         * writes, with their own version. Null when the region is clean
         * (public == real). Persisted as a .pub.png sidecar.
         */
        int[] publicPixels = null;

        long publicVersion = 0;
        boolean publicDirty = false;
        byte[] cachedPublicPng = null;
    }

    /** Hover sidecar of a region, with the same lifecycle as RegionImage. */
    private static final class HoverRegion {
        final HoverRegionData data;
        long version = System.currentTimeMillis();
        boolean dirty = false;
        byte[] cachedBlob = null;

        /** Public variant (hidden-player quarantine) — see RegionImage. */
        HoverRegionData publicData = null;

        long publicVersion = 0;
        boolean publicDirty = false;
        byte[] cachedPublicBlob = null;

        HoverRegion(HoverRegionData data) {
            this.data = data;
        }
    }
}
