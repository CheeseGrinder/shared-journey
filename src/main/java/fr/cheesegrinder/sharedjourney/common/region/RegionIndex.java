package fr.cheesegrinder.sharedjourney.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registre {RegionKey -> Timestamp} sérialisé en index.json (spec §3.1/§3.2).
 * Utilisé côté serveur (vérité) et côté client (ce qu'il possède en cache).
 * Le timestamp est la "version" d'une région : il augmente à chaque réécriture.
 */
public final class RegionIndex {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Long>>() {}.getType();

    private final Map<RegionKey, Long> entries = new ConcurrentHashMap<>();

    public long get(RegionKey key) { return entries.getOrDefault(key, -1L); }

    public void put(RegionKey key, long timestamp) { entries.put(key, timestamp); }

    public void remove(RegionKey key) { entries.remove(key); }

    public int size() { return entries.size(); }

    public Map<RegionKey, Long> snapshot() { return new HashMap<>(entries); }

    /** Retire toutes les entrées d'une couche donnée (purge client). Retourne les clés retirées. */
    public java.util.List<RegionKey> removeLayer(String layerFolderName) {
        java.util.List<RegionKey> removed = new java.util.ArrayList<>();
        entries.keySet().removeIf(k -> {
            boolean match = k.layer().folderName(k.caveBand()).equals(layerFolderName)
                    || k.layer().name().equalsIgnoreCase(layerFolderName);
            if (match) removed.add(k);
            return match;
        });
        return removed;
    }

    // ------------------------------------------------------------------ IO

    public synchronized void load(Path file) {
        entries.clear();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, Long> raw = GSON.fromJson(r, MAP_TYPE);
            if (raw == null) return;
            raw.forEach((k, v) -> {
                RegionKey key = RegionKey.fromIndexKey(k);
                if (key != null && v != null) entries.put(key, v);
            });
        } catch (Exception e) {
            // Index corrompu : on repart de zéro, les PNG seront re-versionnés via mtime.
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
