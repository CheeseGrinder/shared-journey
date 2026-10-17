package fr.cheesegrinder.sharedjourney.client.service;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.config.MapClientConfig;
import fr.cheesegrinder.sharedjourney.common.network.OpsConfigPayloads;
import fr.cheesegrinder.sharedjourney.common.network.PlayerVisibilityPayloads;
import fr.cheesegrinder.sharedjourney.common.network.RegenPayloads;
import fr.cheesegrinder.sharedjourney.common.region.HoverRegionData;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory client state. The client NEVER renders the map itself: it
 * displays what the server pushed (source of truth), possibly reloaded from
 * the local disk cache on reconnect.
 */
public final class ClientMapCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ClientMapCache() {}

    // Server announcements (allowed layers, cave bands, radar cap).
    public static volatile Map<ResourceLocation, List<MapLayer>> layersByDim = Map.of();
    public static volatile List<Integer> caveBands = List.of();
    public static volatile int radarMaxRadius = 64;
    /**
     * Server admin toggle for death waypoints (defaults to true so behavior
     * before the first handshake matches a server that allows them).
     */
    public static volatile boolean deathWaypointsEnabled = true;
    /**
     * True when the server persists this player's DIMENSION waypoints
     * server-side (default). Routes their CRUD through the network instead
     * of the local file; see WaypointStore. Defaults to true so a client
     * connecting before the handshake completes does not briefly write to
     * its local file.
     */
    public static volatile boolean serverManagesWaypoints = true;
    /**
     * Last server config snapshot received for the ops tab of the settings
     * screen (null until the server answers an OpsConfigRequestPayload —
     * i.e. for every non-op player).
     */
    public static volatile OpsConfigPayloads.OpsConfigPayload opsConfig;
    /** Players who asked to be hidden from the map (broadcast by the server). */
    public static volatile Set<UUID> hiddenPlayers = Set.of();
    /** Positions of (non-hidden) players broadcast by the server (~1x/s). */
    public static volatile Map<UUID, PlayerVisibilityPayloads.PlayerPositionsPayload.PlayerPos> playerPositions =
            Map.of();
    /** Server regen running: the map veils chunks absent from regenDoneMasks. */
    public static volatile boolean regenActive;
    /**
     * Numeric regen progress (full regen, or a local re-render this player
     * requested), for the fullscreen map's progress bar. Null when idle.
     */
    public static volatile RegenPayloads.RegenProgressPayload regenProgress;

    /** A map region position (chunk progress masks are layer-agnostic). */
    public record RegionPos(ResourceLocation dimension, int rx, int rz) {}

    /**
     * Per-region regen progress pushed by the server: bit (localZ*32 +
     * localX) set = chunk re-rendered. Absent region = nothing done yet.
     */
    public static final Map<RegionPos, long[]> regenDoneMasks = new ConcurrentHashMap<>();

    /**
     * One resident texture: a region at one level of detail (0 = full 512x512
     * image, 1..{@link LodChain#MAX_LEVEL} = halved that many times).
     */
    private record TileKey(RegionKey key, int level) {}

    /** A resident texture and its cost, with the last time a draw asked for it (LRU). */
    private static final class Tile {
        final Region region;
        final long bytes;
        volatile long lastUsedNanos = System.nanoTime();

        Tile(Region region, long bytes) {
            this.region = region;
            this.bytes = bytes;
        }
    }

    private static final Map<TileKey, Tile> TILES = new ConcurrentHashMap<>();
    /** Memory held by {@link #TILES}, see {@link #tileCost}. */
    private static final AtomicLong RESIDENT_BYTES = new AtomicLong();

    private static final Map<RegionKey, Assembly> PENDING = new ConcurrentHashMap<>();
    /** Regions already looked up (and absent) on disk: avoids retrying every frame. */
    private static final Set<RegionKey> DISK_MISSES = ConcurrentHashMap.newKeySet();
    /** Maximum asynchronous disk loads running or waiting for upload at once. */
    private static final int MAX_LOADS_IN_FLIGHT = 8;

    /**
     * Upload cost allowed per frame, in texels: four full-resolution textures
     * (spreads the GL cost). Reduced levels are cheaper, so many more of them
     * fit in the same frame budget.
     */
    private static final long MAX_UPLOAD_TEXELS_PER_FRAME = 4L * RegionKey.REGION_BLOCKS * RegionKey.REGION_BLOCKS;

    /** Upper bound on the number of uploads per frame, whatever their size. */
    private static final int MAX_UPLOADS_PER_FRAME = 32;

    /** A texture drawn within this window is never evicted by the memory cap. */
    private static final long EVICTION_PROTECTION_NANOS = 1_000_000_000L;

    private static final long MIB = 1024L * 1024L;

    /** True when reduced levels can be produced: LOD enabled and the disk cache is the source. Set once per frame. */
    private static volatile boolean lodAvailable;

    /** Memory cap in bytes, refreshed once per frame from the config. */
    private static volatile long textureBudgetBytes = 256 * MIB;

    /** A cached region decoded off-thread; {@code image} is null when the read or decode failed. */
    private record LoadedRegion(TileKey tile, long version, NativeImage image, long[] contentMask) {}

    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SharedJourney-RegionLoader");
        t.setDaemon(true);
        return t;
    });

    /**
     * Tiles whose decode was started and not yet uploaded (or discarded),
     * with the newest version wanted: a result older than that is dropped
     * because a newer decode is on its way.
     */
    private static final Map<TileKey, Long> LOADING = new ConcurrentHashMap<>();

    private static final Queue<LoadedRegion> LOADED = new ConcurrentLinkedQueue<>();

    /** On-demand request anti-spam. */
    public static final Map<RegionKey, Long> LAST_REQUESTED = new ConcurrentHashMap<>();

    /** Parsed hover sidecar cache cap (whole regions, ~800 KB each). */
    private static final int HOVER_REGION_CAP = 8;

    /**
     * Hover sidecars (INFO pseudo-layer) parsed from the local disk cache.
     * Bounded LRU; entries are invalidated when a newer sidecar arrives.
     * Accessed only from the client thread (reception via enqueueWork).
     */
    private static final Map<RegionKey, HoverRegionData> HOVER_REGIONS = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RegionKey, HoverRegionData> eldest) {
            return size() > HOVER_REGION_CAP;
        }
    };

    /** Sidecars absent from disk: avoids retrying the read every frame. */
    private static final Set<RegionKey> HOVER_MISSES = new HashSet<>();

    /** Biome/block/Y info of a hovered map column. */
    public record HoverInfo(int y, String biomeId, String blockId) {}

    /**
     * Hover info (biome/block/Y) of a column, read from the local hover
     * sidecars pushed by the server with the region sync. Null while the
     * column's chunk has no synced data yet.
     */
    public static HoverInfo hoverInfo(int wx, int wz) {
        return hoverInfo(MapLayer.INFO, 0, wx, wz);
    }

    /** Hover info of a column on one CAVE band (floor block and height), from its INFO_CAVE sidecar. */
    public static HoverInfo caveHoverInfo(int band, int wx, int wz) {
        return hoverInfo(MapLayer.INFO_CAVE, band, wx, wz);
    }

    private static HoverInfo hoverInfo(MapLayer layer, int band, int wx, int wz) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }

        RegionKey key = RegionKey.of(mc.level.dimension(), layer, band, wx >> 4, wz >> 4);
        HoverRegionData data = HOVER_REGIONS.get(key);
        if (data == null) {
            if (HOVER_MISSES.contains(key)) {
                return null;
            }

            byte[] blob = DiskCache.read(key);
            data = blob == null ? null : HoverRegionData.deserialize(blob);
            if (data == null) {
                HOVER_MISSES.add(key);
                return null;
            }

            HOVER_REGIONS.put(key, data);
        }

        HoverRegionData.HoverColumn column =
                data.at(Math.floorMod(wx, RegionKey.REGION_BLOCKS), Math.floorMod(wz, RegionKey.REGION_BLOCKS));
        if (column == null) {
            return null;
        }

        return new HoverInfo(column.y(), column.biomeId(), column.blockId());
    }

    /**
     * Does the chunk hold painted content in the cached image of region
     * {@code key}? False when the region is not cached; an image without a
     * content mask counts as painted.
     */
    public static boolean isChunkPainted(RegionKey key, int chunkX, int chunkZ) {
        // The mask is the same at every level of detail: take whatever is
        // resident, else load the cheapest level.
        Region region = peek(key, LodChain.MAX_LEVEL);
        if (region == null) {
            region = getOrLoad(key, LodChain.MAX_LEVEL);
        }
        if (region == null) {
            return false;
        }

        long[] mask = region.contentMask();
        if (mask == null) {
            return true;
        }

        int bit = Math.floorMod(chunkZ, RegionKey.REGION_CHUNKS) * RegionKey.REGION_CHUNKS
                + Math.floorMod(chunkX, RegionKey.REGION_CHUNKS);
        return (mask[bit >> 6] & (1L << (bit & 63))) != 0;
    }

    /**
     * A fully-assembled cached region texture, at the version it was
     * received. {@code contentMask} marks the chunks that hold at least one
     * painted (non-transparent) pixel — bit (localZ*32 + localX), same
     * layout as the regen masks — so overlays like the regen veil can skip
     * never-explored chunks. {@code level} is the level of detail: the
     * texture is {@code 512 >> level} texels wide and always covers the whole
     * region, so {@link #draw} paints it over 512 blocks whatever the level.
     */
    public record Region(long version, ResourceLocation texture, long[] contentMask, int level) {

        /** Paints the region with its top-left corner at (x, z) of the current pose (1 unit = 1 block). */
        public void draw(GuiGraphics gg, int x, int z) {
            int size = LodChain.sizeOf(level);
            gg.blit(texture, x, z, RegionKey.REGION_BLOCKS, RegionKey.REGION_BLOCKS, 0f, 0f, size, size, size, size);
        }
    }

    private static final class Assembly {
        final long version;
        final byte[][] parts;
        int received;

        Assembly(long version, int total) {
            this.version = version;
            this.parts = new byte[total][];
        }
    }

    // ------------------------------------------------------------------ access

    /** Full-resolution region for display; see {@link #getOrLoad(RegionKey, int)}. */
    public static Region getOrLoad(RegionKey key) {
        return getOrLoad(key, 0);
    }

    /**
     * Level of detail to draw regions at, for a view showing
     * {@code pixelsPerBlock} physical pixels per block (GUI zoom times GUI
     * scale): the coarsest level that still has a texel for every screen
     * pixel. Always 0 when reduced levels are unavailable (disabled in the
     * config, or no disk cache to build them from).
     */
    public static int lodLevelFor(double pixelsPerBlock) {
        if (!lodAvailable) {
            return 0;
        }

        return LodChain.levelFor(pixelsPerBlock * RegionKey.REGION_BLOCKS);
    }

    /**
     * Region for display at the given level of detail, or null when nothing
     * of it is available YET. If the wanted level is absent from RAM but the
     * region is in the session's disk cache, an asynchronous load is started
     * (file read, PNG decode and downscale on a worker thread, texture upload
     * on the render thread by {@link #pumpLoads()}). Meanwhile the best
     * texture of the same region that IS resident is returned (a finer one
     * first, else a coarser one), so zooming never blanks the map; the caller
     * retries on the next frame and always draws through {@link Region#draw},
     * which accounts for the returned texture's own level. At most
     * {@link #MAX_LOADS_IN_FLIGHT} loads run at once; the others are retried
     * on later frames.
     */
    public static Region getOrLoad(RegionKey key, int level) {
        TileKey wanted = new TileKey(key, effectiveLevel(key, level));
        Tile tile = TILES.get(wanted);
        if (tile != null) {
            return use(tile);
        }

        startLoad(wanted);
        tile = findResident(key, wanted.level());
        if (tile == null) {
            return null;
        }

        return use(tile);
    }

    /** The region if some level of it is already in RAM (the closest to the full level); never starts a load. */
    public static Region peek(RegionKey key) {
        return peek(key, 0);
    }

    /** The resident texture of the region closest to {@code level}, or null; never starts a load. */
    public static Region peek(RegionKey key, int level) {
        Tile tile = findResident(key, level);
        if (tile == null) {
            return null;
        }

        return use(tile);
    }

    private static int effectiveLevel(RegionKey key, int level) {
        if (level <= 0 || !lodAvailable || key.layer().isData()) {
            return 0;
        }

        return Math.min(level, LodChain.MAX_LEVEL);
    }

    /** Resident tile at {@code level}, else the nearest finer one, else the nearest coarser one. */
    private static Tile findResident(RegionKey key, int level) {
        int wanted = Math.clamp(level, 0, LodChain.MAX_LEVEL);
        Tile exact = TILES.get(new TileKey(key, wanted));
        if (exact != null) {
            return exact;
        }

        for (int finer = wanted - 1; finer >= 0; finer--) {
            Tile tile = TILES.get(new TileKey(key, finer));
            if (tile != null) {
                return tile;
            }
        }
        for (int coarser = wanted + 1; coarser <= LodChain.MAX_LEVEL; coarser++) {
            Tile tile = TILES.get(new TileKey(key, coarser));
            if (tile != null) {
                return tile;
            }
        }

        return null;
    }

    /** Marks a tile as drawn now (LRU) and returns its region. */
    private static Region use(Tile tile) {
        tile.lastUsedNanos = System.nanoTime();
        return tile.region;
    }

    /** Starts the asynchronous load of a tile from the disk cache, unless one is running, missing or capped. */
    private static void startLoad(TileKey tile) {
        if (DISK_MISSES.contains(tile.key()) || LOADING.containsKey(tile) || LOADING.size() >= MAX_LOADS_IN_FLIGHT) {
            return;
        }

        long diskVersion = DiskCache.versionOf(tile.key());
        if (diskVersion < 0) {
            DISK_MISSES.add(tile.key());
            return;
        }

        LOADING.put(tile, diskVersion);
        LOADER.execute(() -> decodeTile(tile, diskVersion, null));
    }

    /**
     * Worker thread: decodes a tile and queues it for upload.
     *
     * @param knownPng the region PNG when the caller already holds it (a
     *                 fresh network push), else it is read from the disk cache
     */
    private static void decodeTile(TileKey tile, long version, byte[] knownPng) {
        LoadedRegion result = new LoadedRegion(tile, version, null, null);
        try {
            NativeImage image =
                    tile.level() == 0 ? decodeFull(tile.key(), knownPng) : decodeLod(tile, version, knownPng);
            if (image != null) {
                result = new LoadedRegion(tile, version, image, computeContentMask(image));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Invalid cached region PNG for {}", tile, e);
        }

        LOADED.add(result);
    }

    private static NativeImage decodeFull(RegionKey key, byte[] knownPng) throws IOException {
        byte[] png = knownPng != null ? knownPng : DiskCache.read(key);
        if (png == null) {
            return null;
        }

        return NativeImage.read(new ByteArrayInputStream(png));
    }

    /**
     * A reduced level. Levels from {@link LodChain#MIN_STORED_LEVEL} up come
     * from the region's sidecar file when it matches the version; otherwise
     * (and for the levels not stored) the region image is decoded and the
     * whole chain rebuilt, and the sidecar is rewritten.
     */
    private static NativeImage decodeLod(TileKey tile, long version, byte[] knownPng) throws IOException {
        int level = tile.level();
        if (knownPng == null && level >= LodChain.MIN_STORED_LEVEL) {
            LodChain.Decoded stored = LodChain.decode(DiskCache.readLod(tile.key()));
            if (stored != null && stored.sourceVersion() == version) {
                return toImage(stored.levels()[level], level);
            }
        }

        byte[] png = knownPng != null ? knownPng : DiskCache.read(tile.key());
        if (png == null) {
            return null;
        }

        int[][] levels;
        try (NativeImage full = NativeImage.read(new ByteArrayInputStream(png))) {
            levels = LodChain.build(pixelsOf(full));
        }

        DiskCache.storeLod(tile.key(), LodChain.encode(version, levels));
        return toImage(levels[level], level);
    }

    private static int[] pixelsOf(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = image.getPixelRGBA(x, y);
            }
        }

        return pixels;
    }

    private static NativeImage toImage(int[] pixels, int level) {
        int size = LodChain.sizeOf(level);
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setPixelRGBA(x, y, pixels[y * size + x]);
            }
        }

        return image;
    }

    /**
     * Render thread, once per frame before drawing regions: refreshes the
     * per-frame settings, uploads the textures decoded by the loader threads
     * (bounded by {@link #MAX_UPLOAD_TEXELS_PER_FRAME} texels and
     * {@link #MAX_UPLOADS_PER_FRAME} uploads, to spread the GL cost over
     * frames), then releases the least recently drawn textures if the memory
     * cap is exceeded.
     */
    public static void pumpLoads() {
        lodAvailable = MapClientConfig.LOD_ENABLED.get() && DiskCache.isPersisting();
        textureBudgetBytes = MapClientConfig.TEXTURE_BUDGET_MB.get() * MIB;
        long texels = 0;
        int uploaded = 0;
        while (uploaded < MAX_UPLOADS_PER_FRAME && texels < MAX_UPLOAD_TEXELS_PER_FRAME) {
            LoadedRegion loaded = LOADED.poll();
            if (loaded == null) {
                break;
            }

            if (acceptLoaded(loaded)) {
                uploaded++;
                int size = LodChain.sizeOf(loaded.tile().level());
                texels += (long) size * size;
            }
        }

        trimToBudget();
    }

    /** Registers a decoded tile; true when a texture was actually uploaded. */
    private static boolean acceptLoaded(LoadedRegion loaded) {
        TileKey tile = loaded.tile();
        Long wanted = LOADING.get(tile);
        // Evicted or cleared while the worker was running, or a newer decode
        // of the same tile is on its way: drop the result.
        if (wanted == null || loaded.version() < wanted) {
            closeQuietly(loaded.image());
            return false;
        }

        LOADING.remove(tile);
        if (loaded.image() == null) {
            DISK_MISSES.add(tile.key());
            return false;
        }

        // A newer version may have arrived from the server meanwhile.
        Tile existing = TILES.get(tile);
        if (existing != null && existing.region.version() >= loaded.version()) {
            closeQuietly(loaded.image());
            return false;
        }

        registerTexture(tile, loaded.version(), loaded.image(), loaded.contentMask());
        return true;
    }

    private static void closeQuietly(NativeImage image) {
        if (image != null) {
            image.close();
        }
    }

    /**
     * Frees the least recently drawn tiles until the resident memory is back
     * under 90% of the cap. Tiles drawn within the last second are never
     * touched, so the visible set cannot thrash, and neither are tiles of a
     * region absent from the disk cache (they could not be reloaded).
     */
    private static void trimToBudget() {
        long budget = textureBudgetBytes;
        if (RESIDENT_BYTES.get() <= budget) {
            return;
        }

        long now = System.nanoTime();
        List<Map.Entry<TileKey, Tile>> idle = new ArrayList<>();
        for (Map.Entry<TileKey, Tile> entry : TILES.entrySet()) {
            // A region that is not in the disk cache could not be reloaded:
            // it only lives in this texture.
            boolean idleNow = now - entry.getValue().lastUsedNanos > EVICTION_PROTECTION_NANOS;
            if (idleNow && DiskCache.versionOf(entry.getKey().key()) >= 0) {
                idle.add(entry);
            }
        }

        idle.sort(Comparator.comparingLong(entry -> entry.getValue().lastUsedNanos));
        long target = budget - budget / 10;
        for (Map.Entry<TileKey, Tile> entry : idle) {
            if (RESIDENT_BYTES.get() <= target) {
                return;
            }

            removeTile(entry.getKey());
        }
    }

    /** Drops one resident tile and releases its texture. */
    private static void removeTile(TileKey tile) {
        Tile removed = TILES.remove(tile);
        if (removed != null) {
            RESIDENT_BYTES.addAndGet(-removed.bytes);
            Minecraft.getInstance().getTextureManager().release(removed.region.texture());
        }
    }

    /**
     * Newest version known for a region: its resident tiles (any level), or
     * the disk cache index.
     */
    public static long versionOf(RegionKey key) {
        long best = DiskCache.versionOf(key);
        for (int level = 0; level <= LodChain.MAX_LEVEL; level++) {
            Tile tile = TILES.get(new TileKey(key, level));
            if (tile != null) {
                best = Math.max(best, tile.region.version());
            }
        }

        return best;
    }

    /** True when some level of the region is resident. */
    private static boolean isResident(RegionKey key) {
        for (int level = 0; level <= LodChain.MAX_LEVEL; level++) {
            if (TILES.containsKey(new TileKey(key, level))) {
                return true;
            }
        }

        return false;
    }

    public static void evict(RegionKey key) {
        for (int level = 0; level <= LodChain.MAX_LEVEL; level++) {
            TileKey tile = new TileKey(key, level);
            removeTile(tile);
            LOADING.remove(tile);
        }

        DISK_MISSES.remove(key);
        HOVER_REGIONS.remove(key);
        HOVER_MISSES.remove(key);
    }

    public static void clear() {
        for (TileKey tile : new ArrayList<>(TILES.keySet())) {
            removeTile(tile);
        }

        TILES.clear();
        RESIDENT_BYTES.set(0);
        PENDING.clear();
        DISK_MISSES.clear();
        LOADING.clear();
        LoadedRegion stale = LOADED.poll();
        while (stale != null) {
            closeQuietly(stale.image());
            stale = LOADED.poll();
        }

        LAST_REQUESTED.clear();
        HOVER_REGIONS.clear();
        HOVER_MISSES.clear();
        regenActive = false;
        regenDoneMasks.clear();
        regenProgress = null;
    }

    /** Number of resident textures (all levels). */
    public static int loadedCount() {
        return TILES.size();
    }

    /** Resident textures per level of detail (index = level). */
    public static int[] tilesPerLevel() {
        int[] counts = new int[LodChain.MAX_LEVEL + 1];
        for (TileKey tile : TILES.keySet()) {
            counts[tile.level()]++;
        }

        return counts;
    }

    /** Memory held by the resident textures, see {@link #tileCost}. */
    public static long residentBytes() {
        return RESIDENT_BYTES.get();
    }

    public static int pendingCount() {
        return PENDING.size();
    }

    // ------------------------------------------------------------------ assembly

    public static void acceptFragment(RegionKey key, long version, int part, int totalParts, byte[] data) {
        if (versionOf(key) >= version && (isResident(key) || key.layer().isData())) {
            return;
        }

        Assembly asm = PENDING.compute(
                key,
                (k, existing) -> (existing == null || existing.version < version)
                        ? new Assembly(version, totalParts)
                        : existing);
        if (asm.version != version || part < 0 || part >= asm.parts.length) {
            return;
        }

        if (asm.parts[part] == null) {
            asm.parts[part] = data;
            asm.received++;
        }
        if (asm.received == asm.parts.length) {
            PENDING.remove(key, asm);
            int size = 0;
            for (byte[] p : asm.parts) {
                size += p.length;
            }

            byte[] blob = new byte[size];
            int off = 0;
            for (byte[] p : asm.parts) {
                System.arraycopy(p, 0, blob, off, p.length);
                off += p.length;
            }

            // Hover sidecar: no texture, just persisted and re-parsed lazily.
            if (key.layer().isData()) {
                DiskCache.store(key, version, blob);
                HOVER_REGIONS.remove(key);
                HOVER_MISSES.remove(key);
                return;
            }

            applyRegion(key, version, blob);
        }
    }

    /**
     * A region image completed: persists it (local persistence, spec §3.2),
     * then brings the displayed textures up to date. With the disk cache on,
     * only the levels already resident (or loading) are re-decoded, off the
     * render thread, from the bytes in hand; the old texture stays drawn
     * until the new one is ready, and a region nobody displays costs no
     * texture at all (it loads from disk when first drawn). Without the disk
     * cache the region would be lost, so it is decoded and uploaded at once.
     */
    private static void applyRegion(RegionKey key, long version, byte[] png) {
        boolean persisted = DiskCache.isPersisting();
        DiskCache.store(key, version, png);
        if (!persisted) {
            uploadTexture(key, version, png);
            return;
        }

        DISK_MISSES.remove(key);
        for (int level = 0; level <= LodChain.MAX_LEVEL; level++) {
            TileKey tile = new TileKey(key, level);
            if (TILES.containsKey(tile) || LOADING.containsKey(tile)) {
                LOADING.merge(tile, version, Math::max);
                LOADER.execute(() -> decodeTile(tile, version, png));
            }
        }
    }

    /** Network path without a disk cache (render thread): decodes and uploads a pushed region synchronously. */
    private static void uploadTexture(RegionKey key, long version, byte[] png) {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
            registerTexture(new TileKey(key, 0), version, image, computeContentMask(image));
        } catch (IOException e) {
            LOGGER.error("Invalid region PNG for {}", key, e);
        }
    }

    /** Render thread: turns a decoded image into a registered GL texture. */
    private static void registerTexture(TileKey tile, long version, NativeImage image, long[] contentMask) {
        ResourceLocation rl = textureId(tile);
        DynamicTexture texture = new DynamicTexture(image);
        // Region quads are drawn edge to edge under a fractional
        // zoom/pan transform; with the GL default REPEAT wrap, edge
        // samples occasionally land on exactly u/v = 1.0 (float
        // precision) and wrap to the OPPOSITE row/column of the same
        // region — a 1px line of shifted pixels along region borders.
        GlStateManager._bindTexture(texture.getId());
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (tile.level() > 0) {
            // Reduced levels are already box-filtered and drawn at roughly
            // one texel per screen pixel: bilinear sampling keeps panning
            // smooth, where the full level stays pixel-exact (NEAREST).
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        }

        GlStateManager._bindTexture(0);
        // Registering over the same id closes the texture it replaces.
        Minecraft.getInstance().getTextureManager().register(rl, texture);
        long bytes = tileCost(tile.level());
        Tile previous = TILES.put(tile, new Tile(new Region(version, rl, contentMask, tile.level()), bytes));
        if (previous != null) {
            RESIDENT_BYTES.addAndGet(-previous.bytes);
        }

        RESIDENT_BYTES.addAndGet(bytes);
        DISK_MISSES.remove(tile.key());
    }

    /**
     * Memory a resident tile holds: the GPU texture plus the CPU-side pixel
     * copy the {@link DynamicTexture} keeps, i.e. twice the RGBA size.
     */
    private static long tileCost(int level) {
        long size = LodChain.sizeOf(level);
        return size * size * 4L * 2L;
    }

    /**
     * Per-chunk "has painted content" bits of a region image: bit
     * (localZ*32 + localX) set when the 16x16 pixel cell holds at least
     * one non-transparent pixel. Never-explored chunks stay fully
     * transparent in the PNG, and overlays (regen veil) must not paint
     * over them. One-time scan at upload.
     */
    private static long[] computeContentMask(NativeImage image) {
        long[] mask = new long[16];
        int chunks = RegionKey.REGION_CHUNKS;
        int chunkPx = image.getWidth() / chunks;
        for (int cz = 0; cz < chunks; cz++) {
            for (int cx = 0; cx < chunks; cx++) {
                if (cellHasContent(image, cx * chunkPx, cz * chunkPx, chunkPx)) {
                    int bit = cz * chunks + cx;
                    mask[bit >> 6] |= 1L << (bit & 63);
                }
            }
        }
        return mask;
    }

    /** True when one pixel of the (size x size) cell has a non-zero alpha. */
    private static boolean cellHasContent(NativeImage image, int x0, int y0, int size) {
        for (int y = y0; y < y0 + size; y++) {
            for (int x = x0; x < x0 + size; x++) {
                if ((image.getPixelRGBA(x, y) >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ResourceLocation textureId(TileKey tile) {
        RegionKey key = tile.key();
        String path = "dynamic/" + key.dimension().location().toString().replace(':', '_') + "/"
                + key.layer().folderName(key.caveBand()) + "/"
                + coord(key.rx()) + "_" + coord(key.rz())
                + (tile.level() == 0 ? "" : "_l" + tile.level());
        return ResourceLocation.fromNamespaceAndPath(SharedJourneyConstants.MOD_ID, path);
    }

    private static String coord(int v) {
        return v < 0 ? "m" + (-v) : String.valueOf(v);
    }

    // ------------------------------------------------------------------

    /** Layers offered for the current dimension (server truth). */
    public static List<MapLayer> layersForCurrentDim() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return List.of();
        }

        return layersByDim.getOrDefault(mc.level.dimension().location(), List.of());
    }
}
