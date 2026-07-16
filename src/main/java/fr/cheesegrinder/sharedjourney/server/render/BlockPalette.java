package fr.cheesegrinder.sharedjourney.server.render;

import fr.cheesegrinder.sharedjourney.common.config.EngineServerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block → map color resolution, replacing the coarse {@link MapColor}
 * palette with texture-derived colors (JourneyMap-like rendering, computed
 * server-side). Resolution chain, first hit wins:
 *
 * <ol>
 *   <li>server config {@code engine.blockColorOverrides} (the admin always
 *       has the last word);</li>
 *   <li>API registrations ({@link
 *       fr.cheesegrinder.sharedjourney.api.event.BlockColorRegisterEvent},
 *       collected at server startup);</li>
 *   <li>bundled vanilla palette ({@code palette/vanilla.json}, generated
 *       offline from the vanilla client jar — dedicated servers do not ship
 *       client textures, and bundling keeps singleplayer and dedicated
 *       renders identical);</li>
 *   <li>runtime texture extraction for modded blocks (mod jars carry their
 *       assets and are visible from the game classloader);</li>
 *   <li>{@link MapColor} fallback (vanilla map palette).</li>
 * </ol>
 *
 * <p>Resolutions are cached per block; the cache is cleared on config
 * reload since overrides may have changed.
 */
public final class BlockPalette {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Cache value meaning "no palette color, fall back to MapColor". */
    private static final int MISS = Integer.MIN_VALUE;

    private static final ConcurrentHashMap<Block, Integer> CACHE = new ConcurrentHashMap<>();

    private static volatile Map<String, Integer> bundledPalette;

    /** Colors registered through BlockColorRegisterEvent (server startup). */
    private static volatile Map<Block, Integer> apiColors = Map.of();

    private BlockPalette() {}

    /**
     * Installs the API color registrations (collected at server startup,
     * before the map engine starts). Clears the resolution cache: a
     * singleplayer client may start several servers in one session.
     */
    public static void setApiColors(Map<Block, Integer> colors) {
        apiColors = Map.copyOf(colors);
        CACHE.clear();
    }

    /** Map color (0xRRGGBB) of a block through the resolution chain. */
    static int color(BlockState state, BlockGetter level, BlockPos pos) {
        int resolved = CACHE.computeIfAbsent(state.getBlock(), BlockPalette::resolve);
        if (resolved != MISS) {
            return resolved;
        }

        // MapColor can depend on the state/position: never cached per block.
        MapColor mapColor = state.getMapColor(level, pos);
        return (mapColor == MapColor.NONE ? MapColor.STONE : mapColor).col;
    }

    /** Clears the resolution cache (config overrides may have changed). */
    public static void invalidateOverrides() {
        CACHE.clear();
    }

    private static int resolve(Block block) {
        Integer override = EngineServerConfig.blockColorOverride(block);
        if (override != null) {
            return override;
        }

        Integer api = apiColors.get(block);
        if (api != null) {
            return api;
        }

        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        Integer bundled = bundled().get(id);
        if (bundled != null) {
            return bundled;
        }

        Integer extracted = TextureColorExtractor.extract(id, BlockPalette::resource);
        if (extracted != null) {
            return extracted;
        }

        return MISS;
    }

    /** Bundled vanilla palette, parsed once (benign race: same result). */
    private static Map<String, Integer> bundled() {
        Map<String, Integer> palette = bundledPalette;
        if (palette == null) {
            palette = loadBundledPalette();
            bundledPalette = palette;
        }

        return palette;
    }

    private static Map<String, Integer> loadBundledPalette() {
        Map<String, Integer> palette = new HashMap<>();
        InputStream in = resource("assets/sharedjourney/palette/vanilla.json");
        if (in == null) {
            LOGGER.warn("Bundled vanilla palette not found, falling back to texture extraction / MapColor");
            return palette;
        }

        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String hex = entry.getValue().getAsString();
                palette.put(entry.getKey(), Integer.parseInt(hex.substring(1), 16));
            }
            LOGGER.info("Loaded bundled block palette: {} blocks", palette.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to read the bundled vanilla palette", e);
        }

        return palette;
    }

    /**
     * Resource lookup across the mod's own jar and the other mod jars.
     * Mods are loaded as named Java modules: a plain
     * {@code ClassLoader.getResourceAsStream} is defeated by module resource
     * encapsulation ({@code assets/x/y} is a valid package name), so own
     * resources go through the Class (same-module) lookup and other mods'
     * assets through their ModList file entry, keyed by namespace.
     */
    private static InputStream resource(String path) {
        InputStream own = BlockPalette.class.getResourceAsStream("/" + path);
        if (own != null) {
            return own;
        }

        return modJarResource(path);
    }

    /** Resource of another mod's jar, located by its asset namespace ("assets/<modid>/..."). */
    private static InputStream modJarResource(String path) {
        String[] parts = path.split("/");
        if (parts.length < 3) {
            return null;
        }

        IModFileInfo modFile = ModList.get().getModFileById(parts[1]);
        if (modFile == null) {
            return null;
        }

        try {
            Path file = modFile.getFile().findResource(parts);
            if (!Files.exists(file)) {
                return null;
            }

            return Files.newInputStream(file);
        } catch (Exception e) {
            return null;
        }
    }
}
