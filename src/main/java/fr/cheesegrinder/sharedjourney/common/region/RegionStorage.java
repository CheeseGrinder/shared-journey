package fr.cheesegrinder.sharedjourney.common.region;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utilities for the on-disk layout of region storage. */
public final class RegionStorage {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern LEGACY_CAVE = Pattern.compile("cave_(-?\\d+)");

    private RegionStorage() {}

    /**
     * Migrates the legacy "cave_<band>" layout (same level as the other
     * layers) to "cave/<band>" (bands grouped under a parent folder). No-op
     * when there is nothing to migrate. Used server-side
     * (world/data/sharedjourney) and client-side (sharedjourney_cache).
     */
    public static void migrateLegacyCaveFolders(Path storageRoot) {
        if (!Files.isDirectory(storageRoot)) {
            return;
        }

        try (DirectoryStream<Path> dims = Files.newDirectoryStream(storageRoot)) {
            for (Path dimDir : dims) {
                if (!Files.isDirectory(dimDir)) {
                    continue;
                }

                migrateDimension(dimDir);
            }
        } catch (IOException e) {
            LOGGER.warn("SharedJourney: failed to migrate cave folders in {}", storageRoot, e);
        }
    }

    private static void migrateDimension(Path dimDir) throws IOException {
        try (DirectoryStream<Path> layers = Files.newDirectoryStream(dimDir)) {
            for (Path layerDir : layers) {
                Matcher m = LEGACY_CAVE.matcher(layerDir.getFileName().toString());
                if (!Files.isDirectory(layerDir) || !m.matches()) {
                    continue;
                }

                Path target = dimDir.resolve("cave").resolve(m.group(1));
                if (Files.exists(target)) {
                    continue;
                }

                Files.createDirectories(target.getParent());
                Files.move(layerDir, target);
                LOGGER.info("SharedJourney: cave folder migrated: {} -> {}", layerDir, target);
            }
        }
    }
}
