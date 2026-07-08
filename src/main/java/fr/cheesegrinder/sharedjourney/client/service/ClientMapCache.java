package fr.cheesegrinder.sharedjourney.client.service;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.neoforge.network.PacketDistributor;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * État client en mémoire. Le client ne rend JAMAIS la carte lui-même : il
 * affiche ce que le serveur a poussé (source de vérité), éventuellement
 * rechargé depuis le cache disque local à la reconnexion.
 */
public final class ClientMapCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ClientMapCache() {}

    // Annonces du serveur (couches autorisées, bandes cave, plafond radar).
    public static volatile Map<ResourceLocation, List<MapLayer>> layersByDim = Map.of();
    public static volatile List<Integer> caveBands = List.of();
    public static volatile int radarMaxRadius = 64;
    /** Joueurs ayant demandé à être cachés de la carte (diffusé par le serveur). */
    public static volatile Set<UUID> hiddenPlayers = Set.of();
    /** Positions des joueurs (non cachés) diffusées par le serveur (~1x/s). */
    public static volatile Map<UUID, Payloads.PlayerPositionsPayload.PlayerPos> playerPositions = Map.of();

    private static final Map<RegionKey, Region> REGIONS = new ConcurrentHashMap<>();
    private static final Map<RegionKey, Assembly> PENDING = new ConcurrentHashMap<>();
    /** Régions déjà cherchées (et absentes) sur le disque : évite de re-tenter chaque frame. */
    private static final Set<RegionKey> DISK_MISSES = ConcurrentHashMap.newKeySet();
    /** Anti-spam de requêtes à la demande. */
    public static final Map<RegionKey, Long> LAST_REQUESTED = new ConcurrentHashMap<>();

    /** Cap du cache d'infos de survol (chunks entiers de 256 colonnes). */
    private static final int HOVER_CHUNK_CAP = 512;

    /** Nouvelle tentative pour un chunk déjà demandé sans réponse (ms). */
    private static final long HOVER_RETRY_MS = 3_000;

    /** Espacement des préchargements de chunks voisins (ms, anti-spam serveur). */
    private static final long HOVER_PREFETCH_SPACING_MS = 60;

    /**
     * Infos de survol (biome/bloc/Y) reçues du serveur pour les chunks hors
     * de la zone chargée localement. LRU borné ; clé = chunk (cx, cz) packé.
     * Accédé uniquement depuis le thread client (réception via enqueueWork).
     */
    private static final Map<Long, HoverChunk> HOVER_CHUNKS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, HoverChunk> eldest) {
            return size() > HOVER_CHUNK_CAP;
        }
    };

    /** Anti-spam des requêtes de survol : dernier envoi par chunk. */
    private static final Map<Long, Long> HOVER_REQUESTED = new ConcurrentHashMap<>();

    private static long lastHoverRequestAt;

    public record HoverInfo(int y, String biomeId, String blockId) {}

    /** Chunk d'infos de survol palettisé, tel que reçu du serveur. */
    public record HoverChunk(
            short[] heights, byte[] blockIdx, List<String> blockPalette, byte[] biomeIdx, List<String> biomePalette) {

        HoverInfo at(int wx, int wz) {
            int i = (wz & 15) * 16 + (wx & 15);
            String block = palette(blockPalette, blockIdx[i]);
            String biome = palette(biomePalette, biomeIdx[((wz & 15) >> 2) * 4 + ((wx & 15) >> 2)]);
            return new HoverInfo(heights[i], biome, block);
        }

        private static String palette(List<String> palette, byte idx) {
            int i = idx & 0xFF;
            if (i < palette.size()) {
                return palette.get(i);
            }

            return "";
        }
    }

    /** Clé de chunk pour le cache d'infos de survol. */
    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static HoverInfo hoverInfo(int wx, int wz) {
        HoverChunk chunk = HOVER_CHUNKS.get(chunkKey(wx >> 4, wz >> 4));
        if (chunk == null) {
            return null;
        }

        return chunk.at(wx, wz);
    }

    public static void putHoverChunk(int cx, int cz, HoverChunk chunk) {
        HOVER_CHUNKS.put(chunkKey(cx, cz), chunk);
    }

    /**
     * Demande (throttlée) les infos de survol d'un chunk. Le chunk sous le
     * curseur part immédiatement (immediate=true) ; les préchargements des
     * voisins sont espacés pour respecter l'anti-spam du serveur.
     */
    public static void requestHoverChunk(int cx, int cz, boolean immediate) {
        long key = chunkKey(cx, cz);
        if (HOVER_CHUNKS.containsKey(key)) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = HOVER_REQUESTED.get(key);
        if (last != null && now - last < HOVER_RETRY_MS) {
            return;
        }

        if (!immediate && now - lastHoverRequestAt < HOVER_PREFETCH_SPACING_MS) {
            return;
        }

        lastHoverRequestAt = now;
        HOVER_REQUESTED.put(key, now);
        PacketDistributor.sendToServer(new Payloads.MapInfoRequestPayload(cx << 4, cz << 4));
    }

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

    // ------------------------------------------------------------------ accès

    /**
     * Région pour affichage. Si absente en RAM mais présente dans le cache
     * disque de la session, elle est chargée à la volée (une seule tentative).
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
        HOVER_CHUNKS.clear();
        HOVER_REQUESTED.clear();
    }

    public static int loadedCount() {
        return REGIONS.size();
    }

    public static int pendingCount() {
        return PENDING.size();
    }

    // ------------------------------------------------------------------ assemblage

    public static void acceptFragment(RegionKey key, long version, int part, int totalParts, byte[] data) {
        if (versionOf(key) >= version && REGIONS.containsKey(key)) {
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

            byte[] png = new byte[size];
            int off = 0;
            for (byte[] p : asm.parts) {
                System.arraycopy(p, 0, png, off, p.length);
                off += p.length;
            }

            uploadTexture(key, version, png);
            DiskCache.store(key, version, png); // persistance locale (spec §3.2)
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
            LOGGER.error("PNG de région invalide pour {}", key, e);
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

    /** Couches proposées pour la dimension courante (vérité serveur). */
    public static List<MapLayer> layersForCurrentDim() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return List.of();
        }

        return layersByDim.getOrDefault(mc.level.dimension().location(), List.of());
    }
}
