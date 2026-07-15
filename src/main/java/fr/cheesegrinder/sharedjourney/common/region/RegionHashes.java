package fr.cheesegrinder.sharedjourney.common.region;

import fr.cheesegrinder.sharedjourney.common.util.Hashing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {RegionKey -> (version, SHA-256)} registry serialized to hashes.json,
 * server-side only. Records the hash of the exact bytes served to the
 * clients (region PNG or INFO hover blob) so the handshake can detect a
 * locally tampered client cache: same declared version but a different
 * recomputed hash means the file was modified and must be re-pushed.
 *
 * The hash is always paired with the version it was computed for: a lookup
 * for another version returns nothing (no stale comparison), and the pair is
 * refreshed whenever the region bytes are re-encoded.
 */
public final class RegionHashes {

    /** File name of the serialized registry, next to {@link RegionIndex#FILE_NAME}. */
    public static final String FILE_NAME = "hashes.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private record Entry(long version, String sha256) {}

    private final Map<RegionKey, Entry> entries = new ConcurrentHashMap<>();

    /** Hash recorded for exactly this (region, version) pair, or null. */
    public String hashFor(RegionKey key, long version) {
        Entry entry = entries.get(key);
        if (entry == null || entry.version() != version) {
            return null;
        }

        return entry.sha256();
    }

    /**
     * Makes sure the registry holds the hash of the given served bytes for
     * this (region, version) pair; the hash is only recomputed when the
     * recorded pair is missing or belongs to another version.
     */
    public void ensure(RegionKey key, long version, byte[] servedBytes) {
        Entry entry = entries.get(key);
        if (entry != null && entry.version() == version) {
            return;
        }

        entries.put(key, new Entry(version, Hashing.sha256Hex(servedBytes)));
    }

    // ------------------------------------------------------------------ IO

    public synchronized void load(Path file) {
        entries.clear();
        if (!Files.exists(file)) {
            return;
        }

        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, String> raw = GSON.fromJson(r, MAP_TYPE);
            if (raw == null) {
                return;
            }

            raw.forEach(this::loadEntry);
        } catch (Exception e) {
            // Corrupted registry: start from scratch, hashes are re-recorded
            // as regions get re-encoded (worst case: one redundant re-push).
        }
    }

    private void loadEntry(String rawKey, String rawValue) {
        RegionKey key = RegionKey.fromIndexKey(rawKey);
        int colon = rawValue == null ? -1 : rawValue.indexOf(':');
        if (key == null || colon <= 0) {
            return;
        }

        try {
            long version = Long.parseLong(rawValue.substring(0, colon));
            entries.put(key, new Entry(version, rawValue.substring(colon + 1)));
        } catch (NumberFormatException ignored) {
        }
    }

    public synchronized void save(Path file) throws IOException {
        Map<String, String> raw = new HashMap<>();
        entries.forEach((k, v) -> raw.put(k.indexKey(), v.version() + ":" + v.sha256()));
        RegionStorage.writeAtomically(file, GSON.toJson(raw, MAP_TYPE).getBytes(StandardCharsets.UTF_8));
    }
}
