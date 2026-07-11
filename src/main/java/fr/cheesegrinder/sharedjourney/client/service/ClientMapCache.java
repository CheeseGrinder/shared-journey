package fr.cheesegrinder.sharedjourney.client.service;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;
import fr.cheesegrinder.sharedjourney.common.region.HoverRegionData;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    /** Players who asked to be hidden from the map (broadcast by the server). */
    public static volatile Set<UUID> hiddenPlayers = Set.of();
    /** Positions of (non-hidden) players broadcast by the server (~1x/s). */
    public static volatile Map<UUID, Payloads.PlayerPositionsPayload.PlayerPos> playerPositions = Map.of();
    /** Server regen running: the map veils chunks absent from regenDoneMasks. */
    public static volatile boolean regenActive;

    /** A map region position (chunk progress masks are layer-agnostic). */
    public record RegionPos(ResourceLocation dimension, int rx, int rz) {}

    /**
     * Per-region regen progress pushed by the server: bit (localZ*32 +
     * localX) set = chunk re-rendered. Absent region = nothing done yet.
     */
    public static final Map<RegionPos, long[]> regenDoneMasks = new ConcurrentHashMap<>();

    private static final Map<RegionKey, Region> REGIONS = new ConcurrentHashMap<>();
    private static final Map<RegionKey, Assembly> PENDING = new ConcurrentHashMap<>();
    /** Regions already looked up (and absent) on disk: avoids retrying every frame. */
    private static final Set<RegionKey> DISK_MISSES = ConcurrentHashMap.newKeySet();
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
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }

        RegionKey key = RegionKey.of(mc.level.dimension(), MapLayer.INFO, 0, wx >> 4, wz >> 4);
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

    /** A fully-assembled cached region texture, at the version it was received. */
    public record Region(long version, ResourceLocation texture) {}

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

    /**
     * Region for display. If absent from RAM but present in the session's
     * disk cache, it is loaded on the fly (single attempt).
     */
    public static Region getOrLoad(RegionKey key) {
        Region r = REGIONS.get(key);
        if (r != null) {
            return r;
        }

        if (DISK_MISSES.contains(key)) {
            return null;
        }

        long diskVersion = DiskCache.versionOf(key);
        if (diskVersion < 0) {
            DISK_MISSES.add(key);
            return null;
        }
        byte[] png = DiskCache.read(key);
        if (png == null) {
            DISK_MISSES.add(key);
            return null;
        }
        uploadTexture(key, diskVersion, png);
        return REGIONS.get(key);
    }

    public static long versionOf(RegionKey key) {
        Region r = REGIONS.get(key);
        if (r != null) {
            return r.version;
        }

        return DiskCache.versionOf(key);
    }

    public static void evict(RegionKey key) {
        Region r = REGIONS.remove(key);
        if (r != null) {
            Minecraft.getInstance().getTextureManager().release(r.texture());
        }

        DISK_MISSES.remove(key);
        HOVER_REGIONS.remove(key);
        HOVER_MISSES.remove(key);
    }

    public static void clear() {
        REGIONS.keySet().forEach(k -> {
            Region r = REGIONS.get(k);
            if (r != null) {
                Minecraft.getInstance().getTextureManager().release(r.texture());
            }
        });
        REGIONS.clear();
        PENDING.clear();
        DISK_MISSES.clear();
        LAST_REQUESTED.clear();
        HOVER_REGIONS.clear();
        HOVER_MISSES.clear();
        regenActive = false;
        regenDoneMasks.clear();
    }

    public static int loadedCount() {
        return REGIONS.size();
    }

    public static int pendingCount() {
        return PENDING.size();
    }

    // ------------------------------------------------------------------ assembly

    public static void acceptFragment(RegionKey key, long version, int part, int totalParts, byte[] data) {
        if (versionOf(key) >= version && (REGIONS.containsKey(key) || key.layer() == MapLayer.INFO)) {
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
            if (key.layer() == MapLayer.INFO) {
                DiskCache.store(key, version, blob);
                HOVER_REGIONS.remove(key);
                HOVER_MISSES.remove(key);
                return;
            }

            uploadTexture(key, version, blob);
            DiskCache.store(key, version, blob); // local persistence (spec §3.2)
        }
    }

    private static void uploadTexture(RegionKey key, long version, byte[] png) {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
            ResourceLocation rl = textureId(key);
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            REGIONS.put(key, new Region(version, rl));
            DISK_MISSES.remove(key);
        } catch (IOException e) {
            LOGGER.error("Invalid region PNG for {}", key, e);
        }
    }

    private static ResourceLocation textureId(RegionKey key) {
        String path = "dynamic/" + key.dimension().location().toString().replace(':', '_') + "/"
                + key.layer().folderName(key.caveBand()) + "/"
                + coord(key.rx()) + "_" + coord(key.rz());
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
