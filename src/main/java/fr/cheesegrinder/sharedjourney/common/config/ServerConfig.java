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
    public static ModConfigSpec.BooleanValue ALLOW_ON_DEMAND_REQUESTS;
    /** Rayon max du radar toléré côté serveur (anti-triche, plafonne le client). */
    public static ModConfigSpec.IntValue RADAR_MAX_RADIUS;

    private static final Map<String, EnumSet<MapLayer>> PARSED = new ConcurrentHashMap<>();

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

    public static void invalidateCache() {
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
