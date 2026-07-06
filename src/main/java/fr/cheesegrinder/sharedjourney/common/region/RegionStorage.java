package fr.cheesegrinder.sharedjourney.common.region;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utilitaires de disposition du stockage des régions sur disque. */
public final class RegionStorage {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern LEGACY_CAVE = Pattern.compile("cave_(-?\\d+)");

    private RegionStorage() {}

    /**
     * Migre l'ancienne disposition "cave_<bande>" (au même niveau que les
     * autres couches) vers "cave/<bande>" (bandes regroupées dans un dossier
     * parent). Sans effet s'il n'y a rien à migrer. Utilisé côté serveur
     * (world/data/sharedjourney) et côté client (sharedjourney_cache).
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
            LOGGER.warn("SharedJourney : échec de migration des dossiers de grottes dans {}", storageRoot, e);
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
                LOGGER.info("SharedJourney : dossier de grottes migré : {} -> {}", layerDir, target);
            }
        }
    }
}
