package fr.cheesegrinder.sharedjourney.api;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.netty.buffer.ByteBuf;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Map render layers. Historically an enum; now a registry so that other
 * mods can add layers with free ids ({@link
 * fr.cheesegrinder.sharedjourney.api.event.LayerRegisterEvent}). Instances
 * are unique per id: identity comparison ({@code layer == MapLayer.CAVE})
 * remains valid everywhere.
 *
 * <p>CAVE comes in vertical "bands" of 16 blocks (band = floorDiv(y,16)),
 * each band being stored and synchronized separately. Custom layers have
 * no bands.
 *
 * <p>{@link #INFO} is NOT a displayable layer: it is the hover-data sidecar
 * (surface heights, blocks, biomes per region), which rides the same
 * region pipeline (index, delta sync, disk cache) as the image layers. It
 * never appears in the server's layer settings, the layer cycling UI or
 * the admin layer command.
 */
public final class MapLayer {

    private static final Map<String, MapLayer> BY_ID = new ConcurrentHashMap<>();
    private static final List<MapLayer> ALL = new CopyOnWriteArrayList<>();

    public static final MapLayer DAY = builtin("day");
    public static final MapLayer NIGHT = builtin("night");
    public static final MapLayer TOPO = builtin("topo");
    public static final MapLayer BIOME = builtin("biome");
    public static final MapLayer CAVE = builtin("cave");
    public static final MapLayer INFO = builtin("info");

    /**
     * Network codec: the layer id travels as a string (not an ordinal), so
     * a client can receive regions of a custom layer even without the
     * registering mod installed (lenient decode: unknown ids are
     * registered on the fly).
     */
    public static final StreamCodec<ByteBuf, MapLayer> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(MapLayer::register, MapLayer::id);

    private final String id;
    private final boolean builtin;

    private MapLayer(String id, boolean builtin) {
        this.id = id;
        this.builtin = builtin;
    }

    private static MapLayer builtin(String id) {
        MapLayer layer = new MapLayer(id, true);
        BY_ID.put(id, layer);
        ALL.add(layer);
        return layer;
    }

    /**
     * The layer registered under this id, created (custom layer) if
     * unknown. Ids are lowercase {@code [a-z0-9_]+}; custom layers should
     * be prefixed by their mod id ("mymod_something").
     */
    public static MapLayer register(String id) {
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid layer identifier: " + id);
        }

        return BY_ID.computeIfAbsent(id, key -> {
            MapLayer layer = new MapLayer(key, false);
            ALL.add(layer);
            return layer;
        });
    }

    /** Every known layer, registration order (built-ins first). */
    public static List<MapLayer> values() {
        return List.copyOf(ALL);
    }

    /**
     * The layer whose {@link #name()} matches (case-insensitive), enum
     * {@code valueOf} semantics: throws {@link IllegalArgumentException}
     * on unknown names (never registers).
     */
    public static MapLayer valueOf(String name) {
        MapLayer layer = name == null ? null : BY_ID.get(name.toLowerCase(Locale.ROOT));
        if (layer == null) {
            throw new IllegalArgumentException("Unknown map layer: " + name);
        }

        return layer;
    }

    /** Lowercase id ("day", "cave", "mymod_something"). */
    public String id() {
        return id;
    }

    /** Uppercase id — legacy enum name, kept for index.json/config compat. */
    public String name() {
        return id.toUpperCase(Locale.ROOT);
    }

    /** One of the 5 built-in display layers or INFO (not mod-provided)? */
    public boolean isBuiltin() {
        return builtin;
    }

    public String translationKey() {
        return "sharedjourney.layer." + id;
    }

    /**
     * On-disk folder path (relative to the dimension). CAVE bands are grouped
     * under a parent folder: "cave/<band>".
     */
    public String folderName(int caveBand) {
        return this == CAVE ? "cave/" + caveBand : id;
    }

    @Override
    public String toString() {
        return name();
    }
}
