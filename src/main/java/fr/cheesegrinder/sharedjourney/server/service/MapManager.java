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
 * Source de vérité côté serveur (spec §3.1 et §4).
 *
 * - Régions 512x512 px en RAM, persistées en PNG dans
 *   world/data/sharedjourney/[dimension]/[layer]/region_X_Z.png
 * - index.json : registre {RegionKey -> Timestamp} (sérialisation du registre RAM)
 * - Moteur ASYNCHRONE : le tick serveur ne fait que dépiler la file de chunks
 *   "dirty" et soumettre les tâches à un pool de threads dimensionné
 *   min(coeurs-2, config.maxWorkerThreads), plancher 1. Le calcul des pixels,
 *   l'encodage PNG et les écritures disque se font hors du main thread.
 *   Note : les tâches lisent le ChunkAccess en lecture seule ; le chunk est
 *   résolu sur le main thread (getChunkNow) avant soumission.
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

    /** File de chunks à (re)rendre, dédupliquée (dirty marking, spec §4). */
    private final ArrayDeque<QueuedChunk> renderQueue = new ArrayDeque<>();

    private final Set<QueuedChunk> queued = new LinkedHashSet<>();

    private record QueuedChunk(ResourceKey<Level> dim, int cx, int cz) {}

    /**
     * Déverrouillages de bandes de grottes en attente de rendu (anti-exploit) :
     * une bande CAVE n'est peinte que si un joueur l'a réellement explorée.
     */
    private final Set<CaveUnlock> caveUnlocks = ConcurrentHashMap.newKeySet();

    private record CaveUnlock(ResourceKey<Level> dim, int band, int cx, int cz) {}

    private MapManager(MinecraftServer server, Map<String, ChunkLayerRenderer> customLayers) {
        this.server = server;
        this.customLayers = customLayers;
        this.root = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("sharedjourney");
        RegionStorage.migrateLegacyCaveFolders(root);
        this.index.load(root.resolve("index.json"));

        // Formule d'allocation de la spec §4.
        int cores = Runtime.getRuntime().availableProcessors();
        this.workerCount = Math.max(1, Math.min(cores - 2, EngineServerConfig.MAX_WORKER_THREADS.get()));
        this.workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "SharedJourney-Render");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        LOGGER.info(
                "SharedJourney : moteur de rendu initialisé ({} thread(s), index: {} région(s))",
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

    // ------------------------------------------------------------------ file de rendu

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
     * Tick serveur : résout les chunks sur le main thread (obligatoire) puis
     * délègue tout le calcul au pool. Coût main-thread quasi nul.
     */
    public void tick() {
        int budget = EngineServerConfig.RENDER_CHUNKS_PER_TICK.get();
        // Évite d'inonder le pool si le disque/CPU ne suit pas.
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
            // Déchargé entre-temps.
            if (chunk == null) {
                continue;
            }

            submitRender(level, chunk);
        }
    }

    /**
     * Rendu immédiat d'un chunk déjà résolu (référence issue d'un événement).
     * Indispensable pour les chunks fraîchement générés (prégénération
     * Chunky) : ils sont déchargés sitôt générés, une résolution différée au
     * tick suivant (getChunkNow) les manquerait systématiquement. Si le pool
     * est saturé, on retombe sur la file classique plutôt que de retenir en
     * mémoire un nombre illimité de chunks déchargés.
     */
    public void renderNow(ServerLevel level, ChunkAccess chunk) {
        if (tasksInFlight.get() < workerCount * 8) {
            submitRender(level, chunk);
        } else {
            enqueueChunk(level, chunk.getPos().x, chunk.getPos().z);
        }
    }

    /** Soumet le rendu d'un chunk résolu au pool de workers. */
    private void submitRender(ServerLevel level, ChunkAccess chunk) {
        // Voisinage 3x3 (biomes uniquement, statut BIOMES suffit, jamais de
        // génération) : le zoom de biomes du jeu lit jusqu'à une cellule au-delà
        // du chunk, il faut donc les voisins pour des frontières fidèles jusqu'au
        // bord. Résolu ici, sur le main thread ; un voisin absent sera approximé
        // par le chunk central dans ChunkColorizer.
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
                            "Echec de rendu du chunk {},{} en {}",
                            chunk.getPos().x,
                            chunk.getPos().z,
                            level.dimension().location(),
                            t);
                } finally {
                    tasksInFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            tasksInFlight.decrementAndGet(); // arrêt du serveur en cours
        }
    }

    /** Rendu de toutes les couches actives d'un chunk (exécuté sur un worker). */
    private void renderChunk(ServerLevel level, ChunkAccess chunk, ChunkAccess[] neighbors) {
        EnumSet<MapLayer> layers = LayersServerConfig.layersFor(level.dimension());
        ChunkPos cp = chunk.getPos();
        List<RegionKey> touched = new ArrayList<>(layers.size() + 4);
        for (MapLayer layer : layers) {
            if (layer == MapLayer.CAVE) {
                for (int band : LayersServerConfig.CAVE_BANDS.get()) {
                    RegionKey key = RegionKey.of(level.dimension(), layer, band, cp.x, cp.z);
                    CaveUnlock unlock = new CaveUnlock(level.dimension(), band, cp.x, cp.z);
                    // Anti-exploit : une bande de grotte n'est peinte que si un
                    // joueur l'a explorée (déverrouillage CaveTracker) ou si
                    // elle l'était déjà (mise à jour d'une zone connue).
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
        // Push immédiat aux joueurs concernés (sans attendre le delta périodique).
        SyncService.pushRegionUpdates(server, touched);
        // NB: les couches custom (LayerRegisterEvent) sont collectées mais leur
        // pipeline de stockage/sync n'est pas encore branché — voir README §Limites.
    }

    /** Clés de régions connues de l'index (copie, pour la régénération complète). */
    public Set<RegionKey> indexedRegions() {
        return index.snapshot().keySet();
    }

    /** Un chunk a-t-il déjà été peint (au moins un pixel opaque sur la 1re couche active) ? */
    public boolean isChunkRendered(ServerLevel level, int cx, int cz) {
        EnumSet<MapLayer> layers = LayersServerConfig.layersFor(level.dimension());
        if (layers.isEmpty()) {
            return true;
        }

        MapLayer probe = layers.iterator().next();
        int band = probe == MapLayer.CAVE ? firstCaveBand() : 0;
        return isChunkPainted(RegionKey.of(level.dimension(), probe, band, cx, cz), cx, cz);
    }

    /** Le chunk a-t-il au moins un pixel opaque sur la région donnée ? */
    private boolean isChunkPainted(RegionKey key, int cx, int cz) {
        // Astuce rapide : si l'index ne connaît pas la région, rien n'a été peint.
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
     * Marque une bande de grotte comme explorée par un joueur (CaveTracker) :
     * le chunk sera peint sur cette bande au prochain rendu. Sans effet si
     * la bande y est déjà peinte.
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

    // ------------------------------------------------------------------ écriture (workers)

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
        index.put(key, version); // registre RAM ; sérialisé par saveAll()
    }

    // ------------------------------------------------------------------ lecture / versions

    /** Version d'une région : index (vérité) > fichier (mtime) > -1. */
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

    /** PNG encodé de la région (mis en cache tant que la région n'est pas réécrite). */
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
            LOGGER.error("Echec d'encodage PNG", e);
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
                LOGGER.error("Echec de lecture de {}", file, e);
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
        String dim = key.dimension().location().getPath(); // "overworld" comme la spec
        if (!key.dimension().location().getNamespace().equals("minecraft")) {
            dim = key.dimension().location().toString().replace(':', '_');
        }
        return root.resolve(dim).resolve(key.layer().folderName(key.caveBand())).resolve(key.fileName());
    }

    // ------------------------------------------------------------------ persistance

    /** Flush asynchrone : sauvegarde régions modifiées + index.json (world save, arrêt). */
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
                LOGGER.error("Echec de sauvegarde de {}", file, ex);
                synchronized (img) {
                    img.dirty = true;
                }
            }
        }
        try {
            index.save(root.resolve("index.json"));
        } catch (IOException ex) {
            LOGGER.error("Echec de sauvegarde de l'index", ex);
        }
        if (saved > 0) {
            LOGGER.info("SharedJourney : {} région(s) sauvegardée(s)", saved);
        }
    }

    // ------------------------------------------------------------------ stats (spec §7)

    /** Etat RAM / file / threads pour /map stats. */
    public String engineStats() {
        long dirty = regions.values().stream().filter(r -> r.dirty).count();
        long ramMb = regions.size() * (long) RegionKey.REGION_BLOCKS * RegionKey.REGION_BLOCKS * 4 / (1024 * 1024);
        return "Moteur: " + workerCount + " thread(s), " + tasksInFlight.get() + " tâche(s) en cours | "
                + "Régions RAM: " + regions.size() + " (~" + ramMb + " Mo, " + dirty + " modifiées) | "
                + "Index: " + index.size() + " entrées | File de rendu: " + queueSize() + " chunk(s)";
    }

    private static final class RegionImage {
        final int[] pixels = new int[RegionKey.REGION_BLOCKS * RegionKey.REGION_BLOCKS];
        long version = System.currentTimeMillis();
        boolean dirty = false;
        byte[] cachedPng = null;
    }
}
