package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.api.ChunkLayerRenderer;
import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.config.EngineServerConfig;
import fr.cheesegrinder.sharedjourney.common.config.LayersServerConfig;
import fr.cheesegrinder.sharedjourney.common.region.RegionIndex;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;
import fr.cheesegrinder.sharedjourney.common.region.RegionStorage;
import fr.cheesegrinder.sharedjourney.server.render.ChunkColorizer;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
    private final Map<RegionKey, RegionImage> regions = new ConcurrentHashMap<>();
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
        this.index.load(root.resolve("index.json"));

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

            submitRender(level, chunk);
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
        if (tasksInFlight.get() < workerCount * 8) {
            submitRender(level, chunk);
        } else {
            enqueueChunk(level, chunk.getPos().x, chunk.getPos().z);
        }
    }

    /** Submits the render of a resolved chunk to the worker pool. */
    private void submitRender(ServerLevel level, ChunkAccess chunk) {
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
                    renderChunk(level, chunk, neighbors);
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

    /** Renders every active layer of a chunk (runs on a worker). */
    private void renderChunk(ServerLevel level, ChunkAccess chunk, ChunkAccess[] neighbors) {
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
                    writeChunk(key, cp.x, cp.z, pixels);
                    touched.add(key);
                    caveUnlocks.remove(unlock);
                }
            } else {
                int[] pixels = ChunkColorizer.render(level, chunk, neighbors, layer, 0);
                RegionKey key = RegionKey.of(level.dimension(), layer, 0, cp.x, cp.z);
                writeChunk(key, cp.x, cp.z, pixels);
                touched.add(key);
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

    // ------------------------------------------------------------------ writes (workers)

    private void writeChunk(RegionKey key, int cx, int cz, int[] chunkPixels) {
        RegionImage img = getOrLoad(key, true);
        int ox = Math.floorMod(cx, RegionKey.REGION_CHUNKS) * 16;
        int oz = Math.floorMod(cz, RegionKey.REGION_CHUNKS) * 16;
        long version;

        synchronized (Objects.requireNonNull(img)) {
            for (int z = 0; z < 16; z++) {
                System.arraycopy(chunkPixels, z * 16, img.pixels, ox + (oz + z) * RegionKey.REGION_BLOCKS, 16);
            }
            img.version = Math.max(img.version + 1, System.currentTimeMillis());
            img.dirty = true;
            img.cachedPng = null;
            version = img.version;
        }
        index.put(key, version); // RAM registry; serialized by saveAll()
    }

    // ------------------------------------------------------------------ reads / versions

    /** Version of a region: index (truth) > file (mtime) > -1. */
    public long versionOf(RegionKey key) {
        long fromIndex = index.get(key);
        if (fromIndex >= 0) {
            return fromIndex;
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

    /** Encoded PNG of the region (cached until the region is rewritten). */
    public byte[] pngOf(RegionKey key) {
        RegionImage img = getOrLoad(key, false);
        if (img == null) {
            return null;
        }

        synchronized (img) {
            if (img.cachedPng != null) {
                return img.cachedPng;
            }

            byte[] png = encodePng(img.pixels);
            img.cachedPng = png;
            return png;
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

            Path file = pathOf(e.getKey());
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, png);
                Files.setLastModifiedTime(file, FileTime.fromMillis(version));
                saved++;
            } catch (IOException ex) {
                LOGGER.error("Failed to save {}", file, ex);
                synchronized (img) {
                    img.dirty = true;
                }
            }
        }
        try {
            index.save(root.resolve("index.json"));
        } catch (IOException ex) {
            LOGGER.error("Failed to save the index", ex);
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
                + "Index: " + index.size() + " entries | Render queue: " + queueSize() + " chunk(s)";
    }

    private static final class RegionImage {
        final int[] pixels = new int[RegionKey.REGION_BLOCKS * RegionKey.REGION_BLOCKS];
        long version = System.currentTimeMillis();
        boolean dirty = false;
        byte[] cachedPng = null;
    }
}
