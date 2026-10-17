package fr.cheesegrinder.sharedjourney.client.overlay;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.common.network.ChunkOverlayPayloads;
import fr.cheesegrinder.sharedjourney.common.overlay.ChunkOverlayKind;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client state of the server-computed chunk overlays: which overlays the
 * server allows, the region bitmaps received so far, and the (throttled)
 * requests for the regions the maps display. Everything runs on the client
 * thread (frame rendering and payload handlers via {@code enqueueWork}).
 *
 * <p>A region is only requested when the client already holds its map
 * tile, so unexplored areas cost nothing; the server enforces the same rule
 * on its side. Slime data is static (asked once per region and session);
 * force-loaded data is pushed on change by the server and re-requested at a
 * low cadence as a safety net.
 */
public final class ChunkOverlayStore {

    /** Minimum delay before asking again for the same region (ms). */
    private static final long REQUEST_COOLDOWN_MS = 5_000;

    /** Safety-net refresh of a force-loaded region the server may have stopped tracking (ms). */
    private static final long FORCED_REFRESH_MS = 60_000;

    /** Minimum delay between two request packets of one overlay (above the server throttle). */
    private static final long SEND_MIN_INTERVAL_MS = 300;

    /** Region bitmaps kept in memory (oldest dropped first). */
    private static final int DATA_CAP = 4096;

    /** Request marks kept before the table is reset. */
    private static final int REQUESTED_CAP = 16_384;

    private record Key(ChunkOverlayKind kind, ResourceLocation dimension, int rx, int rz) {}

    private record Entry(long[] bits, long receivedAt) {}

    private static volatile boolean forcedAllowed;
    private static volatile boolean slimeAllowed;

    private static final Map<Key, Entry> DATA = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
            return size() > DATA_CAP;
        }
    };

    private static final Map<Key, Long> REQUESTED_AT = new HashMap<>();
    private static final Map<ChunkOverlayKind, List<Key>> PENDING = new EnumMap<>(ChunkOverlayKind.class);
    private static final Map<ChunkOverlayKind, Long> LAST_SENT = new EnumMap<>(ChunkOverlayKind.class);

    private ChunkOverlayStore() {}

    // ------------------------------------------------------------------ server announcements

    /** Does the server allow this overlay? False until the server announces it. */
    public static boolean isAllowed(ChunkOverlayKind kind) {
        return switch (kind) {
            case FORCED_CHUNKS -> forcedAllowed;
            case SLIME_CHUNKS -> slimeAllowed;
        };
    }

    /** Handler of {@code OverlaySettingsPayload}. */
    public static void acceptSettings(boolean forced, boolean slime) {
        forcedAllowed = forced;
        slimeAllowed = slime;
        for (ChunkOverlayKind kind : ChunkOverlayKind.values()) {
            if (!isAllowed(kind)) {
                drop(kind);
            }
        }
    }

    /** Handler of {@code OverlayRegionDataPayload}. */
    public static void acceptData(ChunkOverlayPayloads.OverlayRegionDataPayload payload) {
        ChunkOverlayKind kind = payload.kind();
        if (kind == null || !isAllowed(kind)) {
            return;
        }

        long now = System.currentTimeMillis();
        for (ChunkOverlayPayloads.RegionBits region : payload.regions()) {
            DATA.put(new Key(kind, payload.dimension(), region.rx(), region.rz()), new Entry(region.bits(), now));
        }
    }

    /** Forgets the data and requests of one overlay (disabled by the server). */
    private static void drop(ChunkOverlayKind kind) {
        DATA.keySet().removeIf(key -> key.kind() == kind);
        REQUESTED_AT.keySet().removeIf(key -> key.kind() == kind);
        PENDING.remove(kind);
    }

    /** Login: forgets the previous session's bitmaps (the server announcement is kept). */
    public static void clear() {
        DATA.clear();
        REQUESTED_AT.clear();
        PENDING.clear();
        LAST_SENT.clear();
    }

    /** Logout: forgets everything, including what the previous server allowed. */
    public static void reset() {
        clear();
        forcedAllowed = false;
        slimeAllowed = false;
    }

    // ------------------------------------------------------------------ lookups

    /**
     * Bitmap of a region: the data received so far (an empty array means "no
     * chunk flagged"), or null while it is unknown. Queues a request when the
     * region is due and already explored locally.
     */
    public static long[] lookup(ChunkOverlayKind kind, ResourceLocation dimension, int rx, int rz) {
        Key key = new Key(kind, dimension, rx, rz);
        Entry entry = DATA.get(key);
        long now = System.currentTimeMillis();
        if (entry != null) {
            if (kind == ChunkOverlayKind.FORCED_CHUNKS && now - entry.receivedAt() > FORCED_REFRESH_MS) {
                queue(key, now);
            }
            return entry.bits();
        }

        queue(key, now);
        return null;
    }

    /** Is the chunk flagged in the data already received? Never requests anything. */
    public static boolean isFlagged(ChunkOverlayKind kind, ResourceLocation dimension, int chunkX, int chunkZ) {
        Entry entry = DATA.get(new Key(kind, dimension, Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32)));
        return entry != null && ChunkOverlayKind.isSet(entry.bits(), chunkX, chunkZ);
    }

    private static void queue(Key key, long now) {
        Long last = REQUESTED_AT.get(key);
        if (last != null && now - last < REQUEST_COOLDOWN_MS) {
            return;
        }

        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, key.dimension());
        if (ClientMapCache.versionOf(new RegionKey(dim, MapLayer.INFO, 0, key.rx(), key.rz())) < 0) {
            return;
        }

        if (REQUESTED_AT.size() > REQUESTED_CAP) {
            REQUESTED_AT.clear();
        }

        REQUESTED_AT.put(key, now);
        PENDING.computeIfAbsent(key.kind(), k -> new ArrayList<>()).add(key);
    }

    // ------------------------------------------------------------------ requests

    /**
     * Sends the queued requests of one overlay (end of a frame): at most
     * {@link ChunkOverlayPayloads#MAX_REGIONS} regions of the player's
     * current dimension per packet, at most one packet per 300 ms. The rest
     * stays queued.
     */
    public static void flush(ChunkOverlayKind kind) {
        List<Key> pending = PENDING.get(kind);
        Minecraft mc = Minecraft.getInstance();
        if (pending == null || pending.isEmpty() || mc.level == null || mc.getConnection() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - LAST_SENT.getOrDefault(kind, 0L) < SEND_MIN_INTERVAL_MS) {
            return;
        }

        ResourceLocation current = mc.level.dimension().location();
        List<Key> batch = new ArrayList<>();
        Iterator<Key> it = pending.iterator();
        while (it.hasNext() && batch.size() < ChunkOverlayPayloads.MAX_REGIONS) {
            Key key = it.next();
            it.remove();
            if (key.dimension().equals(current)) {
                batch.add(key);
            }
        }

        if (batch.isEmpty()) {
            return;
        }

        int[] rxs = new int[batch.size()];
        int[] rzs = new int[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            rxs[i] = batch.get(i).rx();
            rzs[i] = batch.get(i).rz();
        }

        LAST_SENT.put(kind, now);
        PacketDistributor.sendToServer(new ChunkOverlayPayloads.OverlayRegionRequestPayload(kind, current, rxs, rzs));
    }
}
