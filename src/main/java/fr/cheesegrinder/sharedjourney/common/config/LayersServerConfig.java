package fr.cheesegrinder.sharedjourney.common.config;

import fr.cheesegrinder.sharedjourney.api.MapLayer;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "layers" section of the server config: active layers per dimension and
 * vertical bands of the CAVE layer. Also owns the parsing cache of the
 * dimension -> layers mappings.
 */
public final class LayersServerConfig {

    /** Shared layers per dimension: "namespace:dim=DAY,NIGHT,TOPO,BIOME,CAVE". */
    public static ModConfigSpec.ConfigValue<List<? extends String>> SHARED_LAYERS;

    public static ModConfigSpec.ConfigValue<List<? extends String>> DEFAULT_LAYERS;
    public static ModConfigSpec.ConfigValue<List<? extends Integer>> CAVE_BANDS;

    private static final Map<String, EnumSet<MapLayer>> PARSED = new ConcurrentHashMap<>();

    private LayersServerConfig() {}

    static void define(ModConfigSpec.Builder b) {
        b.push("layers");
        DEFAULT_LAYERS = b.comment("Layers active by default for dimensions not listed in sharedLayers.")
                .defineListAllowEmpty(
                        "defaultLayers",
                        List.of("DAY", "NIGHT", "TOPO", "BIOME"),
                        () -> "DAY",
                        LayersServerConfig::isValidLayer);
        SHARED_LAYERS = b.comment(
                        "Shared layers per dimension, format 'namespace:dimension=LAYER1,LAYER2'.",
                        "Example: 'minecraft:overworld=DAY,NIGHT,TOPO,BIOME,CAVE', 'minecraft:the_nether=CAVE'.")
                .defineListAllowEmpty(
                        "sharedLayers",
                        List.of(
                                "minecraft:overworld=DAY,NIGHT,TOPO,BIOME,CAVE",
                                "minecraft:the_nether=CAVE",
                                "minecraft:the_end=DAY"),
                        () -> "minecraft:overworld=DAY",
                        LayersServerConfig::isValidMapping);
        CAVE_BANDS = b.comment(
                        "Y bands rendered for CAVE (band = floor(y/16)). Ex: -1 => y=-16..-1.",
                        "Must cover the world height: overworld -64..127 => bands -4..7.")
                .defineListAllowEmpty(
                        "caveBands",
                        List.of(-4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7),
                        () -> 0,
                        o -> o instanceof Integer i && i >= -8 && i <= 20);
        b.pop();
    }

    static void invalidateCache() {
        PARSED.clear();
    }

    public static EnumSet<MapLayer> layersFor(ResourceKey<Level> dim) {
        String id = dim.location().toString();
        return PARSED.computeIfAbsent(id, k -> {
            for (String entry : SHARED_LAYERS.get()) {
                String[] parts = entry.split("=", 2);
                if (parts[0].trim().equals(k)) {
                    return parseLayers(parts[1]);
                }
            }
            EnumSet<MapLayer> def = EnumSet.noneOf(MapLayer.class);
            DEFAULT_LAYERS.get().forEach(s -> def.add(MapLayer.valueOf(s.trim().toUpperCase(Locale.ROOT))));
            return def;
        });
    }

    /** Enables/disables a layer for a dimension and persists it (admin command). */
    public static void setLayer(ResourceKey<Level> dim, MapLayer layer, boolean enabled) {
        String id = dim.location().toString();
        EnumSet<MapLayer> current = EnumSet.copyOf(layersFor(dim));
        if (enabled) {
            current.add(layer);
        } else {
            current.remove(layer);
        }

        List<String> newList = new ArrayList<>();
        boolean found = false;
        for (String entry : SHARED_LAYERS.get()) {
            if (entry.split("=", 2)[0].trim().equals(id)) {
                newList.add(id + "=" + join(current));
                found = true;
            } else {
                newList.add(entry);
            }
        }
        if (!found) {
            newList.add(id + "=" + join(current));
        }

        SHARED_LAYERS.set(newList);
        ServerConfig.invalidateCache();
    }

    private static EnumSet<MapLayer> parseLayers(String csv) {
        EnumSet<MapLayer> set = EnumSet.noneOf(MapLayer.class);
        for (String l : csv.split(",")) {
            String t = l.trim();
            if (!t.isEmpty()) {
                set.add(MapLayer.valueOf(t.toUpperCase(Locale.ROOT)));
            }
        }

        // The INFO data layer rides the region pipeline but is not a
        // render layer: never enabled through the config.
        set.remove(MapLayer.INFO);
        return set;
    }

    /** Valid display layer name ("DAY"... — INFO is never configurable). */
    public static boolean isValidLayer(Object o) {
        if (!(o instanceof String s)) {
            return false;
        }

        try {
            return MapLayer.valueOf(s.trim().toUpperCase(Locale.ROOT)) != MapLayer.INFO;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Valid "namespace:dimension=LAYER1,LAYER2" sharedLayers entry. */
    public static boolean isValidMapping(Object o) {
        if (!(o instanceof String s) || !s.contains("=")) {
            return false;
        }

        String[] parts = s.split("=", 2);
        if (ResourceLocation.tryParse(parts[0].trim()) == null) {
            return false;
        }

        // Empty layer list = every layer disabled for that dimension
        // (producible by setLayer and by the in-game layer editor).
        if (parts[1].isBlank()) {
            return true;
        }

        for (String l : parts[1].split(",")) {
            if (!isValidLayer(l)) {
                return false;
            }
        }

        return true;
    }

    private static String join(EnumSet<MapLayer> set) {
        return String.join(",", set.stream().map(Enum::name).toList());
    }
}
