package fr.cheesegrinder.sharedjourney.client.service;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * Cache of localized display names resolved from raw identifiers, shared
 * by the HUD surfaces (minimap labels, fullscreen info bars). Those bars
 * are rebuilt every frame, and resolving a translatable component (or a
 * registry lookup for blocks) each time allocated strings and walked the
 * language map per frame for values that almost never change. Entries
 * are dropped when the selected language changes.
 *
 * <p>Render-thread only (like every caller).
 */
public final class DisplayNames {

    private static final Map<String, String> BIOMES = new HashMap<>();

    private static final Map<String, String> BLOCKS = new HashMap<>();

    /** Language the cached names were resolved in. */
    private static String language;

    private DisplayNames() {}

    /** Localized biome name from its "namespace:path" identifier. */
    public static String biome(String biomeId) {
        checkLanguage();
        return BIOMES.computeIfAbsent(biomeId, DisplayNames::resolveBiome);
    }

    /** Localized biome name from its registry location. */
    public static String biome(ResourceLocation biomeId) {
        checkLanguage();
        return BIOMES.computeIfAbsent(biomeId.toString(), id -> resolveBiome(biomeId));
    }

    /** Localized block name from its "namespace:path" identifier. */
    public static String block(String blockId) {
        checkLanguage();
        return BLOCKS.computeIfAbsent(blockId, DisplayNames::resolveBlock);
    }

    private static String resolveBiome(String biomeId) {
        return resolveBiome(ResourceLocation.parse(biomeId));
    }

    private static String resolveBiome(ResourceLocation loc) {
        return Component.translatable("biome." + loc.getNamespace() + "." + loc.getPath())
                .getString();
    }

    private static String resolveBlock(String blockId) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
        return block.getName().getString();
    }

    private static void checkLanguage() {
        String current = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!current.equals(language)) {
            language = current;
            BIOMES.clear();
            BLOCKS.clear();
        }
    }
}
