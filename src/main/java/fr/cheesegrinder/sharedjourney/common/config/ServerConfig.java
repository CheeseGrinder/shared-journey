package fr.cheesegrinder.sharedjourney.common.config;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Config SERVEUR. NeoForge implémente déjà la hiérarchie de la spec §8 :
 * `defaultconfigs/` (globale) est copiée puis écrasée par
 * `world/serverconfig/` (locale au monde) — c'est le "merge" demandé.
 * Éditable en jeu par un OP via l'écran de config NeoForge, ou /map admin layer.
 */
public final class ServerConfig {

    public static final ModConfigSpec SPEC;

    /** Couches partagées par dimension : "namespace:dim=DAY,NIGHT,TOPO,BIOME,CAVE". */
    public static ModConfigSpec.ConfigValue<List<? extends String>> SHARED_LAYERS;
    public static ModConfigSpec.ConfigValue<List<? extends String>> DEFAULT_LAYERS;
    public static ModConfigSpec.ConfigValue<List<? extends Integer>> CAVE_BANDS;

    /** Threads du pool de génération : min(coeurs-2, maxWorkerThreads), plancher 1 (spec §4). */
    public static ModConfigSpec.IntValue MAX_WORKER_THREADS;
    public static ModConfigSpec.IntValue PUSH_RADIUS_REGIONS;
    /** Limite de bande passante par joueur (spec §5 : max_kb_per_second). */
    public static ModConfigSpec.IntValue MAX_KB_PER_SECOND_PER_PLAYER;
    public static ModConfigSpec.IntValue SYNC_RATE_TICKS;
    public static ModConfigSpec.IntValue RENDER_CHUNKS_PER_TICK;
    /** Rayon de lissage des couleurs de biome (eau, herbe, feuillage) : 0 = désactivé. */
    public static ModConfigSpec.IntValue BIOME_BLEND_RADIUS;
    /** Blocs exclus du rendu de la carte : "namespace:bloc" ou "#namespace:tag". */
    public static ModConfigSpec.ConfigValue<List<? extends String>> HIDDEN_BLOCKS;
    public static ModConfigSpec.BooleanValue ALLOW_ON_DEMAND_REQUESTS;
    /** Rayon max du radar toléré côté serveur (anti-triche, plafonne le client). */
    public static ModConfigSpec.IntValue RADAR_MAX_RADIUS;

    private static final Map<String, EnumSet<MapLayer>> PARSED = new ConcurrentHashMap<>();

    // Cache du parsing de hiddenBlocks (lu par les threads de rendu).
    // hiddenBlockSet sert de garde : assigné en dernier, remis à null au reload.
    private static volatile List<TagKey<Block>> hiddenBlockTags;
    private static volatile Set<Block> hiddenBlockSet;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("layers");
        DEFAULT_LAYERS = b.comment("Couches actives par défaut pour les dimensions non listées.")
                .defineListAllowEmpty("defaultLayers", List.of("DAY", "NIGHT", "TOPO", "BIOME"),
                        () -> "DAY", ServerConfig::isValidLayer);
        SHARED_LAYERS = b.comment(
                        "Couches partagées par dimension, format 'namespace:dimension=LAYER1,LAYER2'.",
                        "Exemple: 'minecraft:overworld=DAY,NIGHT,TOPO,BIOME,CAVE', 'minecraft:the_nether=CAVE'.")
                .defineListAllowEmpty("sharedLayers",
                        List.of("minecraft:overworld=DAY,NIGHT,TOPO,BIOME,CAVE",
                                "minecraft:the_nether=CAVE",
                                "minecraft:the_end=DAY"),
                        () -> "minecraft:overworld=DAY", ServerConfig::isValidMapping);
        CAVE_BANDS = b.comment("Bandes Y rendues pour CAVE (bande = floor(y/16)). Ex: -1 => y=-16..-1.")
                .defineListAllowEmpty("caveBands", List.of(-2, -1, 0, 1, 2, 3),
                        () -> 0, o -> o instanceof Integer i && i >= -8 && i <= 20);
        b.pop();

        b.push("engine");
        MAX_WORKER_THREADS = b.comment("Plafond de threads du pool de génération (formule: min(coeurs-2, ceci), min 1).")
                .defineInRange("maxWorkerThreads", 4, 1, 32);
        RENDER_CHUNKS_PER_TICK = b.comment("Chunks soumis au pool de rendu au maximum par tick serveur.")
                .defineInRange("renderChunksPerTick", 32, 1, 512);
        BIOME_BLEND_RADIUS = b.comment("Rayon de lissage des couleurs de biome (eau, herbe, feuillage). 0 = désactivé, 2 = équivalent vanilla.")
                .defineInRange("biomeBlendRadius", 2, 0, 7);
        HIDDEN_BLOCKS = b.comment(
                        "Blocs exclus du rendu de la carte : le bloc situé dessous est peint à la place.",
                        "Format : 'namespace:bloc' ou '#namespace:tag'. Ex : 'minecraft:short_grass', '#minecraft:flowers'.")
                .defineListAllowEmpty("hiddenBlocks", List.of(
                                "#minecraft:flowers",
                                "#minecraft:saplings",
                                "minecraft:short_grass",
                                "minecraft:tall_grass",
                                "minecraft:fern",
                                "minecraft:large_fern",
                                "minecraft:dead_bush",
                                "minecraft:brown_mushroom",
                                "minecraft:red_mushroom",
                                "minecraft:torch",
                                "minecraft:wall_torch",
                                "minecraft:soul_torch",
                                "minecraft:soul_wall_torch",
                                "minecraft:cobweb"),
                        () -> "minecraft:short_grass", ServerConfig::isValidBlockEntry);
        b.pop();

        b.push("sync");
        PUSH_RADIUS_REGIONS = b.comment("Rayon en régions (512 blocs) synchronisé autour des joueurs.")
                .defineInRange("pushRadiusRegions", 2, 0, 8);
        MAX_KB_PER_SECOND_PER_PLAYER = b.comment("Bande passante max par joueur (Ko/s) pour l'envoi des tuiles.")
                .defineInRange("maxKbPerSecondPerPlayer", 512, 32, 8192);
        SYNC_RATE_TICKS = b.comment("Intervalle entre deux calculs de delta de sync par joueur (ticks).")
                .defineInRange("syncRateTicks", 40, 5, 1200);
        ALLOW_ON_DEMAND_REQUESTS = b.comment("Autoriser le client à demander des régions hors rayon (carte plein écran).")
                .define("allowOnDemandRequests", true);
        RADAR_MAX_RADIUS = b.comment("Rayon max (blocs) autorisé pour le radar d'entités des clients.")
                .defineInRange("radarMaxRadius", 64, 0, 128);
        b.pop();

        SPEC = b.build();
    }

    private static boolean isValidLayer(Object o) {
        if (!(o instanceof String s)) {
            return false;
        }

        try {
            MapLayer.valueOf(s.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isValidMapping(Object o) {
        if (!(o instanceof String s) || !s.contains("=")) {
            return false;
        }

        String[] parts = s.split("=", 2);
        if (ResourceLocation.tryParse(parts[0].trim()) == null) {
            return false;
        }

        for (String l : parts[1].split(",")) {
            if (!isValidLayer(l)) {
                return false;
            }
        }

        return true;
    }

    /** Entrée valide : "namespace:bloc" ou "#namespace:tag". */
    private static boolean isValidBlockEntry(Object o) {
        if (!(o instanceof String s)) {
            return false;
        }

        String id = s.startsWith("#") ? s.substring(1) : s;
        return ResourceLocation.tryParse(id.trim()) != null;
    }

    /** Le bloc est-il exclu du rendu de la carte (config hiddenBlocks) ? */
    public static boolean isHiddenBlock(BlockState state) {
        Set<Block> blocks = hiddenBlockSet;
        if (blocks == null) {
            blocks = parseHiddenBlocks();
        }

        if (blocks.contains(state.getBlock())) {
            return true;
        }

        for (TagKey<Block> tag : hiddenBlockTags) {
            if (state.is(tag)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Parse hiddenBlocks en blocs + tags. Course bénigne entre threads de
     * rendu : chacun calcule le même résultat ; les tags sont assignés AVANT
     * le set (qui sert de garde de visibilité).
     */
    private static Set<Block> parseHiddenBlocks() {
        Set<Block> blocks = new HashSet<>();
        List<TagKey<Block>> tags = new ArrayList<>();

        for (String entry : HIDDEN_BLOCKS.get()) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("#")) {
                ResourceLocation rl = ResourceLocation.tryParse(trimmed.substring(1));
                if (rl != null) {
                    tags.add(TagKey.create(Registries.BLOCK, rl));
                }
            } else {
                ResourceLocation rl = ResourceLocation.tryParse(trimmed);
                if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
                    blocks.add(BuiltInRegistries.BLOCK.get(rl));
                }
            }
        }

        hiddenBlockTags = tags;
        hiddenBlockSet = blocks;
        return blocks;
    }

    public static void invalidateCache() {
        PARSED.clear();
        hiddenBlockSet = null;
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

    private static EnumSet<MapLayer> parseLayers(String csv) {
        EnumSet<MapLayer> set = EnumSet.noneOf(MapLayer.class);
        for (String l : csv.split(",")) {
            String t = l.trim();
            if (!t.isEmpty()) {
                set.add(MapLayer.valueOf(t.toUpperCase(Locale.ROOT)));
            }
        }
        return set;
    }

    /** Active/désactive une couche pour une dimension et persiste (commande admin). */
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
        invalidateCache();
    }

    private static String join(EnumSet<MapLayer> set) {
        return String.join(",", set.stream().map(Enum::name).toList());
    }
}
