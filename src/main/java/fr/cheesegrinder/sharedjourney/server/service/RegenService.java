package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.common.config.LayersServerConfig;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;
import fr.cheesegrinder.sharedjourney.common.util.Lang;

import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Map regeneration (/sj admin regen), throttled so it does not choke the
 * server. Progress shown in a temporary boss bar.
 *
 * Two modes:
 * - regen        : re-renders ALREADY PAINTED chunks (from the region index).
 * - regen full   : scans Minecraft's region files (region/r.X.Z.mca) and
 *                  renders ALL chunks generated on disk — including those the
 *                  map has never seen (worlds pregenerated with Chunky, etc.).
 *
 * Guarantee: no terrain generation. Before loading, the chunk's NBT status
 * is read; only "minecraft:full" chunks are loaded and rendered
 * (proto-chunks at the edge of the explored area are skipped).
 */
public final class RegenService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Chunks (re)loaded per server tick: bounds the disk loading cost. */
    private static final int CHUNKS_PER_TICK = 4;
    /** Pauses loading when the render queue falls too far behind. */
    private static final int MAX_PENDING_RENDERS = 256;
    /** Region files: r.<rx>.<rz>.mca */
    private static final Pattern MCA_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /**
     * A region file (32x32 chunks) and its presence mask: bit
     * (localZ*32 + localX) set = chunk present on disk / to re-render.
     * Compact: ~140 bytes per region instead of one object per chunk, to
     * fit in RAM even on pregenerated worlds with millions of chunks.
     */
    private record Batch(ResourceKey<Level> dim, int rx, int rz, long[] mask) {
        int count() {
            int n = 0;
            for (long w : mask) {
                n += Long.bitCount(w);
            }

            return n;
        }
    }

    // State touched ONLY on the main thread (installed via server.execute).
    private static ArrayDeque<Batch> queue;
    private static Batch current;
    private static int currentBit;
    private static ServerBossEvent bossBar;
    private static int total;
    private static int done;
    /** Async disk scan in progress (full mode, before the queue is installed). */
    private static boolean scanning;
    /** Incremented on every start/cancel: invalidates stale async scans. */
    private static int epoch;

    /** A map region position, dimension included (chunk progress masks). */
    private record MaskKey(ResourceKey<Level> dim, int rx, int rz) {}

    // Per-chunk progress, pushed to clients so their map veils the chunks
    // not yet re-rendered. Main thread only, like the rest of the state.
    private static final Map<MaskKey, long[]> doneMasks = new HashMap<>();
    private static final Set<MaskKey> dirtyMasks = new HashSet<>();
    /** Ticks until the next dirty-mask push (~1x/s). */
    private static int maskBroadcastIn;

    private RegenService() {}

    public static boolean isRunning() {
        return queue != null || scanning;
    }

    // ------------------------------------------------------------------ start

    /**
     * "regen" mode: re-renders already painted chunks (from the region
     * index). Returns the number of chunks to process, or -1 if the engine
     * is not ready or a regen is already running.
     */
    public static int start(MinecraftServer server) {
        MapManager mgr = MapManager.get();
        if (mgr == null || isRunning()) {
            return -1;
        }

        announceStart();

        // Unique regions, across all layers/bands.
        Set<MaskKey> regions = new HashSet<>();
        for (RegionKey key : mgr.indexedRegions()) {
            regions.add(new MaskKey(key.dimension(), key.rx(), key.rz()));
        }

        ArrayDeque<Batch> q = new ArrayDeque<>();
        int count = 0;
        for (MaskKey region : regions) {
            ServerLevel level = server.getLevel(region.dim());
            if (level == null) {
                continue;
            }

            long[] mask = new long[16];
            int regionCount = 0;
            for (int cz = 0; cz < RegionKey.REGION_CHUNKS; cz++) {
                for (int cx = 0; cx < RegionKey.REGION_CHUNKS; cx++) {
                    int acx = region.rx() * RegionKey.REGION_CHUNKS + cx;
                    int acz = region.rz() * RegionKey.REGION_CHUNKS + cz;
                    if (mgr.isChunkRendered(level, acx, acz)) {
                        int bit = cz * RegionKey.REGION_CHUNKS + cx;
                        mask[bit >> 6] |= 1L << (bit & 63);
                        regionCount++;
                    }
                }
            }
            if (regionCount > 0) {
                q.add(new Batch(region.dim(), region.rx(), region.rz(), mask));
                count += regionCount;
            }
        }

        install(q, count);
        return total;
    }

    /**
     * "regen full" mode: scans the region/r.X.Z.mca files of every dimension
     * (async, the boss bar shows "scanning") then renders every chunk
     * present on disk. Returns false if the engine is not ready or a regen
     * is already running.
     */
    public static boolean startFull(MinecraftServer server) {
        if (MapManager.get() == null || isRunning()) {
            return false;
        }

        scanning = true;
        announceStart();
        final int myEpoch = ++epoch;
        bossBar = new ServerBossEvent(
                Component.translatable(Lang.REGEN_SCAN),
                BossEvent.BossBarColor.GREEN,
                BossEvent.BossBarOverlay.PROGRESS);
        bossBar.setProgress(0f);

        // Paths resolved on the main thread; file reads happen async.
        record Target(ResourceKey<Level> dim, Path regionDir) {}
        var targets = new ArrayList<Target>();
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        for (ServerLevel level : server.getAllLevels()) {
            if (LayersServerConfig.layersFor(level.dimension()).isEmpty()) {
                continue;
            }

            Path regionDir =
                    DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region");
            targets.add(new Target(level.dimension(), regionDir));
        }

        CompletableFuture.runAsync(
                        () -> {
                            ArrayDeque<Batch> q = new ArrayDeque<>();
                            int count = 0;
                            for (Target target : targets) {
                                if (!Files.isDirectory(target.regionDir())) {
                                    continue;
                                }

                                try (Stream<Path> files = Files.list(target.regionDir())) {
                                    for (Path file : (Iterable<Path>) files::iterator) {
                                        Matcher m = MCA_NAME.matcher(
                                                file.getFileName().toString());
                                        if (!m.matches()) {
                                            continue;
                                        }

                                        long[] mask = readPresenceMask(file);
                                        if (mask == null) {
                                            continue;
                                        }

                                        Batch batch = new Batch(
                                                target.dim(),
                                                Integer.parseInt(m.group(1)),
                                                Integer.parseInt(m.group(2)),
                                                mask);
                                        int n = batch.count();
                                        if (n > 0) {
                                            q.add(batch);
                                            count += n;
                                        }
                                    }
                                } catch (IOException e) {
                                    LOGGER.warn("SharedJourney: failed to scan {}", target.regionDir(), e);
                                }
                            }
                            final int finalCount = count;
                            server.execute(() -> {
                                // Cancelled or replaced during the scan: drop the result.
                                if (epoch != myEpoch || !scanning) {
                                    return;
                                }

                                scanning = false;
                                install(q, finalCount);
                                LOGGER.info(
                                        "SharedJourney: scan complete, {} chunk(s) to render in {} region(s)",
                                        finalCount,
                                        q.size());
                            });
                        },
                        Util.backgroundExecutor())
                .exceptionally(t -> {
                    LOGGER.error("SharedJourney: failed to scan region files", t);
                    server.execute(() -> {
                        if (epoch == myEpoch && scanning) {
                            cancel();
                        }
                    });
                    return null;
                });
        return true;
    }

    /** Marks the regen as started and announces it to every client. */
    private static void announceStart() {
        doneMasks.clear();
        dirtyMasks.clear();
        maskBroadcastIn = 0;
        PacketDistributor.sendToAllPlayers(new Payloads.RegenStatePayload(true));
    }

    /** Sends the current regen state and progress to a player joining mid-regen. */
    public static void sendStateTo(ServerPlayer player) {
        if (!isRunning()) {
            return;
        }

        PacketDistributor.sendToPlayer(player, new Payloads.RegenStatePayload(true));
        doneMasks.forEach((key, mask) -> PacketDistributor.sendToPlayer(
                player, new Payloads.RegenChunksPayload(key.dim().location(), key.rx(), key.rz(), mask)));
    }

    /** Records a re-rendered chunk in its region's progress mask. */
    private static void markDone(ResourceKey<Level> dim, int cx, int cz) {
        MaskKey key = new MaskKey(
                dim, Math.floorDiv(cx, RegionKey.REGION_CHUNKS), Math.floorDiv(cz, RegionKey.REGION_CHUNKS));
        int bit = Math.floorMod(cz, RegionKey.REGION_CHUNKS) * RegionKey.REGION_CHUNKS
                + Math.floorMod(cx, RegionKey.REGION_CHUNKS);
        long[] mask = doneMasks.computeIfAbsent(key, k -> new long[Payloads.RegenChunksPayload.MASK_WORDS]);
        mask[bit >> 6] |= 1L << (bit & 63);
        dirtyMasks.add(key);
    }

    /** Pushes the progress masks touched since the last push (~1x/s). */
    private static void broadcastDirtyMasks() {
        if (--maskBroadcastIn > 0 || dirtyMasks.isEmpty()) {
            return;
        }

        maskBroadcastIn = 20;
        for (MaskKey key : dirtyMasks) {
            PacketDistributor.sendToAllPlayers(
                    new Payloads.RegenChunksPayload(key.dim().location(), key.rx(), key.rz(), doneMasks.get(key)));
        }
        dirtyMasks.clear();
    }

    /** Installs the queue and (re)initializes the boss bar (main thread). */
    private static void install(ArrayDeque<Batch> q, int count) {
        queue = q;
        current = null;
        currentBit = 0;
        total = count;
        done = 0;
        if (bossBar == null) {
            bossBar = new ServerBossEvent(barName(), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        }
        bossBar.setName(barName());
        bossBar.setProgress(0f);
    }

    /**
     * Presence mask of the 1024 chunks of an .mca file: the header is a
     * table of 1024 4-byte offsets; zero offset = absent chunk. Reads only
     * 4 KB, no deserialization. Returns null when unreadable.
     */
    private static long[] readPresenceMask(Path mcaFile) {
        try (InputStream in = Files.newInputStream(mcaFile)) {
            byte[] header = new byte[4096];
            new DataInputStream(in).readFully(header);
            long[] mask = new long[16];
            for (int i = 0; i < 1024; i++) {
                int offset =
                        ((header[i * 4] & 0xFF) << 16) | ((header[i * 4 + 1] & 0xFF) << 8) | (header[i * 4 + 2] & 0xFF);
                int sectors = header[i * 4 + 3] & 0xFF;
                if (offset != 0 && sectors != 0) {
                    mask[i >> 6] |= 1L << (i & 63);
                }
            }
            return mask;
        } catch (IOException e) {
            LOGGER.warn("SharedJourney: unreadable header: {}", mcaFile, e);
            return null;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public static void cancel() {
        epoch++;
        scanning = false;
        if (bossBar != null) {
            bossBar.removeAllPlayers();
        }

        queue = null;
        current = null;
        bossBar = null;
        doneMasks.clear();
        dirtyMasks.clear();
        // Lift the clients' stale-chunk veil (guarded: cancel can run
        // during server shutdown, when broadcasting is no longer possible).
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            PacketDistributor.sendToAllPlayers(new Payloads.RegenStatePayload(false));
        }
    }

    /** Called every server tick (main thread). */
    public static void tick(MinecraftServer server) {
        if (!isRunning()) {
            return;
        }

        MapManager mgr = MapManager.get();
        if (mgr == null) {
            cancel();
            return;
        }

        if (bossBar != null) {
            // Also covers players who joined along the way (idempotent).
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                bossBar.addPlayer(p);
            }
        }
        // The async scan has not delivered the queue yet.
        if (scanning) {
            return;
        }

        if (mgr.queueSize() < MAX_PENDING_RENDERS) {
            for (int i = 0; i < CHUNKS_PER_TICK && advance(); i++) {
                int bit = currentBit - 1; // positioned by advance()
                int cx = current.rx() * RegionKey.REGION_CHUNKS + (bit & 31);
                int cz = current.rz() * RegionKey.REGION_CHUNKS + (bit >> 5);
                ServerLevel level = server.getLevel(current.dim());
                done++;
                if (level == null) {
                    continue;
                }

                // Processed either way: the client veil must lift even on
                // chunks that turn out not to be renderable.
                markDone(current.dim(), cx, cz);

                // Never any terrain generation: status checked before loading.
                if (!isChunkFull(level, cx, cz)) {
                    continue;
                }

                // Blocking but throttled load; the status was just checked,
                // the chunk exists complete on disk.
                level.getChunk(cx, cz);
                mgr.enqueueChunk(level, cx, cz);
            }
        }

        if (bossBar != null) {
            bossBar.setProgress(total == 0 ? 1f : (float) done / total);
            bossBar.setName(barName());
        }

        broadcastDirtyMasks();

        // End: wait for the render pool to finish before removing the bar.
        if (queue != null && queue.isEmpty() && current == null && mgr.queueSize() == 0 && mgr.tasksInFlight() == 0) {
            cancel();
        }
    }

    /**
     * Advances to the next set bit of the current batch (or the next one).
     * Returns false when the queue is exhausted. After returning true,
     * currentBit - 1 is the index of the chunk to process.
     */
    private static boolean advance() {
        while (true) {
            if (current == null) {
                current = queue.poll();
                if (current == null) {
                    return false;
                }

                currentBit = 0;
            }
            long[] mask = current.mask();
            while (currentBit < 1024) {
                long word = mask[currentBit >> 6] >>> (currentBit & 63);
                if (word == 0) {
                    currentBit = ((currentBit >> 6) + 1) << 6; // skip the empty word
                    continue;
                }
                currentBit += Long.numberOfTrailingZeros(word);
                currentBit++; // consumes the bit; the caller reads currentBit - 1
                return true;
            }
            current = null; // batch exhausted
        }
    }

    /**
     * Is the chunk fully generated ("minecraft:full") on disk?
     * NBT read through the vanilla IO worker; blocks the main thread for
     * the duration of one disk read, throttled by CHUNKS_PER_TICK.
     */
    private static boolean isChunkFull(ServerLevel level, int cx, int cz) {
        try {
            Optional<CompoundTag> tag =
                    level.getChunkSource().chunkMap.read(new ChunkPos(cx, cz)).join();
            return tag.isPresent() && tag.get().getString("Status").endsWith("full");
        } catch (Exception e) {
            LOGGER.warn(
                    "SharedJourney: unreadable status for chunk {},{} in {}",
                    cx,
                    cz,
                    level.dimension().location(),
                    e);
            return false;
        }
    }

    private static Component barName() {
        return Component.translatable(Lang.REGEN_BOSSBAR, done, total);
    }
}
