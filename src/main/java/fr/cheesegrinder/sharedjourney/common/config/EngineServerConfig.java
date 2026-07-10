package fr.cheesegrinder.sharedjourney.common.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "engine" section of the server config: render engine performance and
 * rendering options (biome blending, hidden blocks). Also owns the
 * hiddenBlocks parsing cache, read by the render threads.
 */
public final class EngineServerConfig {

    /** Generation pool threads: min(cores-2, maxWorkerThreads), floor 1 (spec §4). */
    public static ModConfigSpec.IntValue MAX_WORKER_THREADS;

    public static ModConfigSpec.IntValue RENDER_CHUNKS_PER_TICK;
    /** Biome color blending radius (water, grass, foliage): 0 = disabled. */
    public static ModConfigSpec.IntValue BIOME_BLEND_RADIUS;
    /** Blocks excluded from map rendering: "namespace:block" or "#namespace:tag". */
    public static ModConfigSpec.ConfigValue<List<? extends String>> HIDDEN_BLOCKS;
    /** Forced block colors, bypassing the texture palette: "namespace:block=#RRGGBB". */
    public static ModConfigSpec.ConfigValue<List<? extends String>> BLOCK_COLOR_OVERRIDES;

    // hiddenBlocks parsing cache (read by the render threads).
    // hiddenBlockSet acts as the guard: assigned last, reset to null on reload.
    private static volatile List<TagKey<Block>> hiddenBlockTags;
    private static volatile Set<Block> hiddenBlockSet;

    // blockColorOverrides parsing cache (read by the render threads).
    private static volatile Map<Block, Integer> blockColorOverrideMap;

    private EngineServerConfig() {}

    static void define(ModConfigSpec.Builder b) {
        b.push("engine");
        MAX_WORKER_THREADS = b.comment("Cap on generation pool threads (formula: min(cores-2, this), min 1).")
                .defineInRange("maxWorkerThreads", 4, 1, 32);
        RENDER_CHUNKS_PER_TICK = b.comment("Maximum chunks submitted to the render pool per server tick.")
                .defineInRange("renderChunksPerTick", 32, 1, 512);
        BIOME_BLEND_RADIUS = b.comment(
                        "Biome color blending radius (water, grass, foliage). 0 = disabled, 2 = vanilla equivalent.")
                .defineInRange("biomeBlendRadius", 2, 0, 7);
        HIDDEN_BLOCKS = b.comment(
                        "Blocks excluded from map rendering: the block below is painted instead.",
                        "Format: 'namespace:block' or '#namespace:tag'. Ex: 'minecraft:short_grass', '#minecraft:flowers'.")
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
        BLOCK_COLOR_OVERRIDES = b.comment(
                        "Forces a block's map color, bypassing the texture palette.",
                        "Format: 'namespace:block=#RRGGBB'. Ex: 'minecraft:stone=#7D7D7D'.")
                .defineListAllowEmpty(
                        "blockColorOverrides",
                        List.of(),
                        () -> "minecraft:stone=#7D7D7D",
                        EngineServerConfig::isValidColorOverride);
        b.pop();
    }

    static void invalidateCache() {
        hiddenBlockSet = null;
        blockColorOverrideMap = null;
    }

    /** Is the block excluded from map rendering (hiddenBlocks config)? */
    public static boolean isHiddenBlock(BlockState state) {
        Set<Block> blocks = hiddenBlockSet;
        if (blocks == null) {
            blocks = parseHiddenBlocks();
        }

        if (blocks.contains(state.getBlock())) {
            return true;
        }

        // Tags are gameplay-driven and drag in tree canopies (bees'
        // #minecraft:flowers contains cherry and flowering azalea LEAVES):
        // a tag match never hides leaves. Listing a leaves block explicitly
        // (above) is always honored.
        if (state.is(BlockTags.LEAVES)) {
            return false;
        }

        for (TagKey<Block> tag : hiddenBlockTags) {
            if (state.is(tag)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Parses hiddenBlocks into blocks + tags. Benign race between render
     * threads: each computes the same result; tags are assigned BEFORE the
     * set (which acts as the visibility guard).
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

    /** Valid entry: "namespace:block" or "#namespace:tag". */
    private static boolean isValidBlockEntry(Object o) {
        if (!(o instanceof String s)) {
            return false;
        }

        String id = s.startsWith("#") ? s.substring(1) : s;
        return ResourceLocation.tryParse(id.trim()) != null;
    }

    /** Configured color override of a block (0xRRGGBB), or null. */
    public static Integer blockColorOverride(Block block) {
        Map<Block, Integer> overrides = blockColorOverrideMap;
        if (overrides == null) {
            overrides = parseColorOverrides();
        }

        return overrides.get(block);
    }

    /**
     * Parses blockColorOverrides into a block → color map. Benign race
     * between render threads: each computes the same result.
     */
    private static Map<Block, Integer> parseColorOverrides() {
        Map<Block, Integer> overrides = new HashMap<>();

        for (String entry : BLOCK_COLOR_OVERRIDES.get()) {
            int eq = entry.indexOf('=');
            if (eq < 0) {
                continue;
            }

            ResourceLocation rl =
                    ResourceLocation.tryParse(entry.substring(0, eq).trim());
            Integer color = parseHexColor(entry.substring(eq + 1).trim());
            if (rl != null && color != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
                overrides.put(BuiltInRegistries.BLOCK.get(rl), color);
            }
        }

        blockColorOverrideMap = overrides;
        return overrides;
    }

    /** Parses "#RRGGBB" into 0xRRGGBB, or null if malformed. */
    private static Integer parseHexColor(String s) {
        if (!s.startsWith("#") || s.length() != 7) {
            return null;
        }

        try {
            return Integer.parseInt(s.substring(1), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Valid entry: "namespace:block=#RRGGBB". */
    private static boolean isValidColorOverride(Object o) {
        if (!(o instanceof String s)) {
            return false;
        }

        int eq = s.indexOf('=');
        if (eq < 0) {
            return false;
        }

        return ResourceLocation.tryParse(s.substring(0, eq).trim()) != null
                && parseHexColor(s.substring(eq + 1).trim()) != null;
    }
}
