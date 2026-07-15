package fr.cheesegrinder.sharedjourney.client.service;

import fr.cheesegrinder.sharedjourney.client.config.MapClientConfig;
import fr.cheesegrinder.sharedjourney.common.region.RegionIndex;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;
import fr.cheesegrinder.sharedjourney.common.region.RegionStorage;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Client local cache (spec §3.2):
 * .minecraft/sharedjourney_cache/[serverId]/[dimension]/[layer]/region_X_Z.png
 * + index.json (what the client owns). serverId = server IP, or
 * "sp_<world>" in singleplayer. Writes happen on Minecraft's IO pool.
 */
public final class DiskCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Path currentRoot;
    private static final RegionIndex index = new RegionIndex();

    private DiskCache() {}

    // ------------------------------------------------------------------ session

    /** Called on login: determines the current server/world folder. */
    public static void openSession() {
        Minecraft mc = Minecraft.getInstance();
        String id;
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            id = sanitize(server.ip);
        } else if (mc.getSingleplayerServer() != null) {
            // The world's FOLDER name, not the level.dat display name:
            // creating a second world called "New World" keeps the same
            // display name and only renames the folder ("New World (1)").
            // Keying on the name made both worlds share one cache folder
            // and blend their maps — and the handshake then reported the
            // blended index as owned, so the server never re-pushed the
            // right regions.
            Path worldDir = mc.getSingleplayerServer()
                    .getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath()
                    .normalize();
            id = "sp_" + sanitize(worldDir.getFileName().toString());
        } else {
            id = "unknown";
        }
        currentRoot = mc.gameDirectory.toPath().resolve("sharedjourney_cache").resolve(id);
        RegionStorage.migrateLegacyCaveFolders(currentRoot);
        index.load(currentRoot.resolve(RegionIndex.FILE_NAME));
        LOGGER.info("SharedJourney: local cache '{}' ({} region(s))", id, index.size());
    }

    public static void closeSession() {
        flushIndex();
        currentRoot = null;
    }

    public static boolean isOpen() {
        return currentRoot != null;
    }

    /** Root of the current session's cache folder, or null when closed. */
    public static Path sessionRoot() {
        return currentRoot;
    }

    public static RegionIndex index() {
        return index;
    }

    private static String sanitize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    // ------------------------------------------------------------------ IO

    /** Writes a region PNG received from the server (async) and updates the index. */
    public static void store(RegionKey key, long version, byte[] png) {
        if (currentRoot == null || !MapClientConfig.DISK_CACHE_ENABLED.get()) {
            return;
        }

        index.put(key, version);
        Path file = pathOf(key);
        Util.ioPool().execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, png);
            } catch (IOException e) {
                LOGGER.error("Failed to write cache {}", file, e);
            }
        });
    }

    /** Reads a PNG from the disk cache, or null. (Blocking call: fast local read.) */
    public static byte[] read(RegionKey key) {
        if (currentRoot == null) {
            return null;
        }

        Path file = pathOf(key);
        if (!Files.exists(file)) {
            return null;
        }

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
        if (currentRoot == null) {
            return;
        }

        try {
            index.save(currentRoot.resolve(RegionIndex.FILE_NAME));
        } catch (IOException e) {
            LOGGER.error("Failed to save the client index", e);
        }
    }

    /**
     * Purges the local cache of a layer ("day", "cave", "cave/2", or "all")
     * for the current session (spec §7: /sj purge). Returns the number of
     * deleted files.
     */
    public static int purge(String layerName) {
        if (currentRoot == null) {
            return 0;
        }

        String needle = layerName.toLowerCase(Locale.ROOT);
        int[] deleted = {0};
        var removedKeys =
                needle.equals("all") ? index.snapshot().keySet().stream().toList() : index.removeLayer(needle);
        if (needle.equals("all")) {
            removedKeys.forEach(index::remove);
        }
        for (RegionKey key : removedKeys) {
            try {
                if (Files.deleteIfExists(pathOf(key))) {
                    deleted[0]++;
                }
            } catch (IOException ignored) {
            }
            ClientMapCache.evict(key);
        }
        flushIndex();
        return deleted[0];
    }

    private static Path pathOf(RegionKey key) {
        String dim = key.dimension().location().getNamespace().equals("minecraft")
                ? key.dimension().location().getPath()
                : key.dimension().location().toString().replace(':', '_');
        return currentRoot
                .resolve(dim)
                .resolve(key.layer().folderName(key.caveBand()))
                .resolve(key.fileName());
    }
}
