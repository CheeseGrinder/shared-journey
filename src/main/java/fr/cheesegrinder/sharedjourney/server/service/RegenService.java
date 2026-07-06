package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.common.config.ServerConfig;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

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

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Régénération de la carte (/sj admin regen), à un rythme throttlé pour ne
 * pas étouffer le serveur. Progression affichée dans une boss bar temporaire.
 *
 * Deux modes :
 * - regen        : re-rend les chunks DÉJÀ PEINTS (depuis l'index des régions).
 * - regen full   : scanne les fichiers de région de Minecraft (region/r.X.Z.mca)
 *                  et rend TOUS les chunks générés sur disque — y compris ceux
 *                  jamais vus par la carte (mondes prégénérés avec Chunky, etc.).
 *
 * Garantie : aucune génération de terrain. Avant chargement, le statut NBT du
 * chunk est lu ; seuls les chunks "minecraft:full" sont chargés et rendus
 * (les proto-chunks en bordure de zone explorée sont ignorés).
 */
public final class RegenService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Chunks (re)chargés par tick serveur : borne le coût du chargement disque. */
    private static final int CHUNKS_PER_TICK = 4;
    /** Pause du chargement si la file de rendu prend trop de retard. */
    private static final int MAX_PENDING_RENDERS = 256;
    /** Fichiers de région : r.<rx>.<rz>.mca */
    private static final Pattern MCA_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /**
     * Un fichier de région (32x32 chunks) et son masque de présence : bit
     * (localZ*32 + localX) levé = chunk présent sur disque / à re-rendre.
     * Compact : ~140 octets par région au lieu d'un objet par chunk, pour
     * tenir en RAM même sur des mondes prégénérés de plusieurs millions de
     * chunks.
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

    // Etat manipulé UNIQUEMENT sur le main thread (installé via server.execute).
    private static ArrayDeque<Batch> queue;
    private static Batch current;
    private static int currentBit;
    private static ServerBossEvent bossBar;
    private static int total;
    private static int done;
    /** Scan disque asynchrone en cours (mode full, avant installation de la file). */
    private static boolean scanning;
    /** Incrémenté à chaque start/cancel : invalide les scans async périmés. */
    private static int epoch;

    private RegenService() {}

    public static boolean isRunning() {
        return queue != null || scanning;
    }

    // ------------------------------------------------------------------ démarrage

    /**
     * Mode "regen" : re-rend les chunks déjà peints (depuis l'index des
     * régions). Retourne le nombre de chunks à traiter, ou -1 si le moteur
     * n'est pas prêt ou une regen déjà en cours.
     */
    public static int start(MinecraftServer server) {
        MapManager mgr = MapManager.get();
        if (mgr == null || isRunning()) {
            return -1;
        }

        // Régions uniques, toutes couches/bandes confondues.
        record RegionPos(ResourceKey<Level> dim, int rx, int rz) {}
        Set<RegionPos> regions = new HashSet<>();
        for (RegionKey key : mgr.indexedRegions()) {
            regions.add(new RegionPos(key.dimension(), key.rx(), key.rz()));
        }

        ArrayDeque<Batch> q = new ArrayDeque<>();
        int count = 0;
        for (RegionPos region : regions) {
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
     * Mode "regen full" : scanne les fichiers region/r.X.Z.mca de toutes les
     * dimensions (asynchrone, la boss bar affiche "scan en cours") puis rend
     * tous les chunks présents sur disque. Retourne false si le moteur n'est
     * pas prêt ou une regen déjà en cours.
     */
    public static boolean startFull(MinecraftServer server) {
        if (MapManager.get() == null || isRunning()) {
            return false;
        }

        scanning = true;
        final int myEpoch = ++epoch;
        bossBar = new ServerBossEvent(
                Component.translatable("sharedjourney.regen.scan"),
                BossEvent.BossBarColor.GREEN,
                BossEvent.BossBarOverlay.PROGRESS);
        bossBar.setProgress(0f);

        // Chemins résolus sur le main thread ; lecture des fichiers en async.
        record Target(ResourceKey<Level> dim, Path regionDir) {}
        var targets = new ArrayList<Target>();
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        for (ServerLevel level : server.getAllLevels()) {
            if (ServerConfig.layersFor(level.dimension()).isEmpty()) {
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
                                    LOGGER.warn("SharedJourney : échec du scan de {}", target.regionDir(), e);
                                }
                            }
                            final int finalCount = count;
                            server.execute(() -> {
                                // Annulée ou remplacée pendant le scan : on jette le résultat.
                                if (epoch != myEpoch || !scanning) {
                                    return;
                                }

                                scanning = false;
                                install(q, finalCount);
                                LOGGER.info(
                                        "SharedJourney : scan terminé, {} chunk(s) à rendre dans {} région(s)",
                                        finalCount,
                                        q.size());
                            });
                        },
                        Util.backgroundExecutor())
                .exceptionally(t -> {
                    LOGGER.error("SharedJourney : échec du scan des fichiers de région", t);
                    server.execute(() -> {
                        if (epoch == myEpoch && scanning) {
                            cancel();
                        }
                    });
                    return null;
                });
        return true;
    }

    /** Installe la file et (ré)initialise la boss bar (main thread). */
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
     * Masque de présence des 1024 chunks d'un fichier .mca : l'en-tête est une
     * table de 1024 offsets de 4 octets ; offset nul = chunk absent. Lecture
     * de 4 Ko seulement, aucune désérialisation. Retourne null si illisible.
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
            LOGGER.warn("SharedJourney : en-tête illisible : {}", mcaFile, e);
            return null;
        }
    }

    // ------------------------------------------------------------------ cycle de vie

    public static void cancel() {
        epoch++;
        scanning = false;
        if (bossBar != null) {
            bossBar.removeAllPlayers();
        }

        queue = null;
        current = null;
        bossBar = null;
    }

    /** Appelé chaque tick serveur (main thread). */
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
            // Couvre aussi les joueurs connectés en cours de route (idempotent).
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                bossBar.addPlayer(p);
            }
        }
        // Le scan async n'a pas encore livré la file.
        if (scanning) {
            return;
        }

        if (mgr.queueSize() < MAX_PENDING_RENDERS) {
            for (int i = 0; i < CHUNKS_PER_TICK && advance(); i++) {
                int bit = currentBit - 1; // positionné par advance()
                int cx = current.rx() * RegionKey.REGION_CHUNKS + (bit & 31);
                int cz = current.rz() * RegionKey.REGION_CHUNKS + (bit >> 5);
                ServerLevel level = server.getLevel(current.dim());
                done++;
                if (level == null) {
                    continue;
                }

                // Jamais de génération de terrain : statut vérifié avant chargement.
                if (!isChunkFull(level, cx, cz)) {
                    continue;
                }

                // Chargement bloquant mais throttlé ; le statut vient d'être
                // vérifié, le chunk existe complet sur disque.
                level.getChunk(cx, cz);
                mgr.enqueueChunk(level, cx, cz);
            }
        }

        if (bossBar != null) {
            bossBar.setProgress(total == 0 ? 1f : (float) done / total);
            bossBar.setName(barName());
        }

        // Fin : attend que le pool de rendu ait terminé avant de retirer la bar.
        if (queue != null && queue.isEmpty() && current == null && mgr.queueSize() == 0 && mgr.tasksInFlight() == 0) {
            cancel();
        }
    }

    /**
     * Avance jusqu'au prochain bit levé du batch courant (ou du suivant).
     * Retourne false quand la file est épuisée. Après un retour true,
     * currentBit - 1 est l'index du chunk à traiter.
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
                    currentBit = ((currentBit >> 6) + 1) << 6; // saute le mot vide
                    continue;
                }
                currentBit += Long.numberOfTrailingZeros(word);
                currentBit++; // consomme le bit ; l'appelant lit currentBit - 1
                return true;
            }
            current = null; // batch épuisé
        }
    }

    /**
     * Le chunk est-il entièrement généré ("minecraft:full") sur disque ?
     * Lecture NBT via le worker d'IO vanilla ; bloque le main thread le temps
     * d'une lecture disque, throttlée par CHUNKS_PER_TICK.
     */
    private static boolean isChunkFull(ServerLevel level, int cx, int cz) {
        try {
            Optional<CompoundTag> tag =
                    level.getChunkSource().chunkMap.read(new ChunkPos(cx, cz)).join();
            return tag.isPresent() && tag.get().getString("Status").endsWith("full");
        } catch (Exception e) {
            LOGGER.warn(
                    "SharedJourney : statut illisible pour le chunk {},{} en {}",
                    cx,
                    cz,
                    level.dimension().location(),
                    e);
            return false;
        }
    }

    private static Component barName() {
        return Component.translatable("sharedjourney.regen.bossbar", done, total);
    }
}
