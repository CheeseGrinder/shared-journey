package fr.cheesegrinder.sharedjourney.common.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Section "engine" de la config serveur : performance du moteur de rendu et
 * options de rendu (lissage de biomes, blocs masqués). Porte le cache de
 * parsing de hiddenBlocks, consulté par les threads de rendu.
 */
public final class EngineServerConfig {

    /** Threads du pool de génération : min(coeurs-2, maxWorkerThreads), plancher 1 (spec §4). */
    public static ModConfigSpec.IntValue MAX_WORKER_THREADS;

    public static ModConfigSpec.IntValue RENDER_CHUNKS_PER_TICK;
    /** Rayon de lissage des couleurs de biome (eau, herbe, feuillage) : 0 = désactivé. */
    public static ModConfigSpec.IntValue BIOME_BLEND_RADIUS;
    /** Blocs exclus du rendu de la carte : "namespace:bloc" ou "#namespace:tag". */
    public static ModConfigSpec.ConfigValue<List<? extends String>> HIDDEN_BLOCKS;

    // Cache du parsing de hiddenBlocks (lu par les threads de rendu).
    // hiddenBlockSet sert de garde : assigné en dernier, remis à null au reload.
    private static volatile List<TagKey<Block>> hiddenBlockTags;
    private static volatile Set<Block> hiddenBlockSet;

    private EngineServerConfig() {}

    static void define(ModConfigSpec.Builder b) {
        b.push("engine");
        MAX_WORKER_THREADS = b.comment(
                        "Plafond de threads du pool de génération (formule: min(coeurs-2, ceci), min 1).")
                .defineInRange("maxWorkerThreads", 4, 1, 32);
        RENDER_CHUNKS_PER_TICK = b.comment("Chunks soumis au pool de rendu au maximum par tick serveur.")
                .defineInRange("renderChunksPerTick", 32, 1, 512);
        BIOME_BLEND_RADIUS = b.comment(
                        "Rayon de lissage des couleurs de biome (eau, herbe, feuillage). 0 = désactivé, 2 = équivalent vanilla.")
                .defineInRange("biomeBlendRadius", 2, 0, 7);
        HIDDEN_BLOCKS = b.comment(
                        "Blocs exclus du rendu de la carte : le bloc situé dessous est peint à la place.",
                        "Format : 'namespace:bloc' ou '#namespace:tag'. Ex : 'minecraft:short_grass', '#minecraft:flowers'.")
                .defineListAllowEmpty(
                        "hiddenBlocks",
                        List.of(
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
                        () -> "minecraft:short_grass",
                        EngineServerConfig::isValidBlockEntry);
        b.pop();
    }

    static void invalidateCache() {
        hiddenBlockSet = null;
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

    /** Entrée valide : "namespace:bloc" ou "#namespace:tag". */
    private static boolean isValidBlockEntry(Object o) {
        if (!(o instanceof String s)) {
            return false;
        }

        String id = s.startsWith("#") ? s.substring(1) : s;
        return ResourceLocation.tryParse(id.trim()) != null;
    }
}
