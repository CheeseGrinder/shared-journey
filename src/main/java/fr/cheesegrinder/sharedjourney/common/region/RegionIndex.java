package fr.cheesegrinder.sharedjourney.common.region;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {RegionKey -> Timestamp} registry serialized to index.json (spec §3.1/§3.2).
 * Used server-side (source of truth) and client-side (what the local cache
 * holds). The timestamp is a region's "version": it grows on every rewrite.
 */
public final class RegionIndex {

    /** File name of the serialized index, at the root of a map storage folder. */
    public static final String FILE_NAME = "index.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Long>>() {}.getType();

    private final Map<RegionKey, Long> entries = new ConcurrentHashMap<>();

    public long get(RegionKey key) {
        return entries.getOrDefault(key, -1L);
    }

    public void put(RegionKey key, long timestamp) {
        entries.put(key, timestamp);
    }

    public void remove(RegionKey key) {
        entries.remove(key);
    }

    public int size() {
        return entries.size();
    }

    public Map<RegionKey, Long> snapshot() {
        return new HashMap<>(entries);
    }

    /** Removes every entry of a given layer (client purge). Returns the removed keys. */
    public List<RegionKey> removeLayer(String layerFolderName) {
        List<RegionKey> removed = new ArrayList<>();
        entries.keySet().removeIf(k -> {
            boolean match = k.layer().folderName(k.caveBand()).equals(layerFolderName)
                    || k.layer().name().equalsIgnoreCase(layerFolderName);
            if (match) {
                removed.add(k);
            }

            return match;
        });

        return removed;
    }

    // ------------------------------------------------------------------ IO

    public synchronized void load(Path file) {
        entries.clear();
        if (!Files.exists(file)) {
            return;
        }

        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, Long> raw = GSON.fromJson(r, MAP_TYPE);
            if (raw == null) {
                return;
            }

            raw.forEach((k, v) -> {
                RegionKey key = RegionKey.fromIndexKey(k);
                if (key != null && v != null) {
                    entries.put(key, v);
                }
            });
        } catch (Exception e) {
            // Corrupted index: start from scratch, PNGs will be re-versioned via mtime.
        }
    }

    public synchronized void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Map<String, Long> raw = new HashMap<>();
        entries.forEach((k, v) -> raw.put(k.indexKey(), v));

        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(raw, MAP_TYPE, w);
        }
    }
}
