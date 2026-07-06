package fr.cheesegrinder.sharedjourney.client;

import com.mojang.logging.LogUtils;
import fr.cheesegrinder.sharedjourney.common.RegionIndex;
import fr.cheesegrinder.sharedjourney.common.RegionKey;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Cache local du client (spec §3.2) :
 * .minecraft/sharedjourney_cache/[serverId]/[dimension]/[layer]/region_X_Z.png
 * + index.json (ce que le client possède). serverId = IP du serveur, ou
 * "sp_<monde>" en solo. Les écritures se font sur le pool IO de Minecraft.
 */
public final class DiskCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Path currentRoot;
    private static final RegionIndex index = new RegionIndex();

    private DiskCache() {}

    // ------------------------------------------------------------------ session

    /** À appeler à la connexion : détermine le dossier du serveur/monde courant. */
    public static void openSession() {
        Minecraft mc = Minecraft.getInstance();
        String id;
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            id = sanitize(server.ip);
        } else if (mc.getSingleplayerServer() != null) {
            id = "sp_" + sanitize(mc.getSingleplayerServer().getWorldData().getLevelName());
        } else {
            id = "unknown";
        }
        currentRoot = mc.gameDirectory.toPath().resolve("sharedjourney_cache").resolve(id);
        index.load(currentRoot.resolve("index.json"));
        LOGGER.info("SharedJourney : cache local '{}' ({} région(s))", id, index.size());
    }

    public static void closeSession() {
        flushIndex();
        currentRoot = null;
    }

    public static boolean isOpen() { return currentRoot != null; }

    public static RegionIndex index() { return index; }

    private static String sanitize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    // ------------------------------------------------------------------ IO

    /** Écrit un PNG de région reçu du serveur (asynchrone) et met à jour l'index. */
    public static void store(RegionKey key, long version, byte[] png) {
        if (currentRoot == null || !ClientConfig.DISK_CACHE_ENABLED.get()) return;
        index.put(key, version);
        Path file = pathOf(key);
        Util.ioPool().execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, png);
            } catch (IOException e) {
                LOGGER.error("Echec d'écriture du cache {}", file, e);
            }
        });
    }

    /** Lit un PNG du cache disque, ou null. (Appel bloquant : lecture locale rapide.) */
    public static byte[] read(RegionKey key) {
        if (currentRoot == null) return null;
        Path file = pathOf(key);
        if (!Files.exists(file)) return null;
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
    }

    public static long versionOf(RegionKey key) {
        return currentRoot == null ? -1 : index.get(key);
    }

    public static void flushIndex() {
        if (currentRoot == null) return;
        try {
            index.save(currentRoot.resolve("index.json"));
        } catch (IOException e) {
            LOGGER.error("Echec de sauvegarde de l'index client", e);
        }
    }

    /**
     * Purge le cache local d'un calque ("day", "cave", "cave_2", ou "all")
     * pour la session courante (spec §7 : /map purge). Retourne le nombre
     * de fichiers supprimés.
     */
    public static int purge(String layerName) {
        if (currentRoot == null) return 0;
        String needle = layerName.toLowerCase(Locale.ROOT);
        int[] deleted = {0};
        var removedKeys = needle.equals("all")
                ? index.snapshot().keySet().stream().toList()
                : index.removeLayer(needle);
        if (needle.equals("all")) {
            removedKeys.forEach(index::remove);
        }
        for (RegionKey key : removedKeys) {
            try {
                if (Files.deleteIfExists(pathOf(key))) deleted[0]++;
            } catch (IOException ignored) {}
            ClientMapCache.evict(key);
        }
        flushIndex();
        return deleted[0];
    }

    private static Path pathOf(RegionKey key) {
        String dim = key.dimension().location().getNamespace().equals("minecraft")
                ? key.dimension().location().getPath()
                : key.dimension().location().toString().replace(':', '_');
        return currentRoot.resolve(dim)
                .resolve(key.layer().folderName(key.caveBand()))
                .resolve(key.fileName());
    }
}
