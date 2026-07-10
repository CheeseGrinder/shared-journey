package fr.cheesegrinder.sharedjourney.server.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.imageio.ImageIO;

/**
 * Computes the average color of a block's texture by walking its client
 * assets: blockstate JSON → model JSON (parent chain) → texture PNG →
 * average of opaque pixels.
 *
 * <p>Pure Java + Gson, no Minecraft classes: the same algorithm runs in the
 * offline vanilla palette generator (reading the vanilla client jar) and at
 * runtime (reading mod jars on a dedicated server, where vanilla client
 * assets are absent). Resources are abstracted as a lookup from resource
 * path (e.g. {@code assets/minecraft/blockstates/stone.json}) to stream.
 */
public final class TextureColorExtractor {

    /** Texture-key preference: map-relevant faces first (seen from above). */
    private static final String[] TEXTURE_KEY_PREFERENCE = {"top", "up", "end", "all", "texture", "particle", "side"};

    private static final int MAX_PARENT_DEPTH = 16;
    private static final int MAX_REF_DEPTH = 8;

    private TextureColorExtractor() {}

    /**
     * Average texture color of a block, or {@code null} when any step fails
     * (missing blockstate/model/texture, fully transparent texture...).
     *
     * @param blockId   block id, e.g. {@code minecraft:cherry_leaves}
     * @param resources resource path → stream (null when absent); a fresh
     *                  stream per call, closed by this method
     * @return 0xRRGGBB, or null if the color could not be extracted
     */
    public static Integer extract(String blockId, Function<String, InputStream> resources) {
        String namespace = namespaceOf(blockId);
        String path = pathOf(blockId);

        JsonObject blockstate = readJson(resources, "assets/" + namespace + "/blockstates/" + path + ".json");
        if (blockstate == null) {
            return null;
        }

        String modelId = firstModel(blockstate);
        if (modelId == null) {
            return null;
        }

        String textureId = resolveTexture(modelId, resources);
        if (textureId == null) {
            return null;
        }

        return averageTextureColor(textureId, resources);
    }

    /** First model referenced by a blockstate (first variant, or first multipart apply). */
    private static String firstModel(JsonObject blockstate) {
        if (blockstate.has("variants")) {
            JsonObject variants = blockstate.getAsJsonObject("variants");
            for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                String model = modelOfVariant(entry.getValue());
                if (model != null) {
                    return model;
                }
            }
            return null;
        }

        if (blockstate.has("multipart")) {
            JsonArray parts = blockstate.getAsJsonArray("multipart");
            for (JsonElement part : parts) {
                if (!part.isJsonObject() || !part.getAsJsonObject().has("apply")) {
                    continue;
                }

                String model = modelOfVariant(part.getAsJsonObject().get("apply"));
                if (model != null) {
                    return model;
                }
            }
        }

        return null;
    }

    /** Model id of a variant value (object, or array of weighted objects). */
    private static String modelOfVariant(JsonElement variant) {
        if (variant.isJsonArray()) {
            JsonArray array = variant.getAsJsonArray();
            variant = array.isEmpty() ? null : array.get(0);
        }

        if (variant == null || !variant.isJsonObject()) {
            return null;
        }

        JsonElement model = variant.getAsJsonObject().get("model");
        return model == null ? null : model.getAsString();
    }

    /**
     * Walks the model parent chain collecting the textures map (child
     * overrides parent), then picks the most map-relevant texture and
     * resolves {@code #reference} indirections.
     */
    private static String resolveTexture(String modelId, Function<String, InputStream> resources) {
        Map<String, String> textures = new LinkedHashMap<>();
        String current = modelId;

        for (int depth = 0; current != null && depth < MAX_PARENT_DEPTH; depth++) {
            if (current.startsWith("builtin/")) {
                break;
            }

            String namespace = namespaceOf(current);
            String path = pathOf(current);
            JsonObject model = readJson(resources, "assets/" + namespace + "/models/" + path + ".json");
            if (model == null) {
                break;
            }

            if (model.has("textures")) {
                JsonObject entries = model.getAsJsonObject("textures");
                for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                    textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                }
            }

            JsonElement parent = model.get("parent");
            current = parent == null ? null : parent.getAsString();
        }

        String value = pickTexture(textures);
        // "#top" style values point at another entry of the same map.
        for (int depth = 0; value != null && value.startsWith("#") && depth < MAX_REF_DEPTH; depth++) {
            value = textures.get(value.substring(1));
        }

        if (value == null || value.startsWith("#")) {
            return null;
        }

        return value;
    }

    /** Preferred texture entry, falling back to the first one declared. */
    private static String pickTexture(Map<String, String> textures) {
        for (String key : TEXTURE_KEY_PREFERENCE) {
            String value = textures.get(key);
            if (value != null) {
                return value;
            }
        }

        for (String value : textures.values()) {
            return value;
        }

        return null;
    }

    /** Average of opaque pixels (alpha > 127) of the texture's first animation frame. */
    private static Integer averageTextureColor(String textureId, Function<String, InputStream> resources) {
        String namespace = namespaceOf(textureId);
        String path = pathOf(textureId);
        BufferedImage image = readImage(resources, "assets/" + namespace + "/textures/" + path + ".png");
        if (image == null) {
            return null;
        }

        // Animated textures are vertical strips: only sample the first frame.
        int width = image.getWidth();
        int height = image.getHeight();
        if (height > width && height % width == 0) {
            height = width;
        }

        long r = 0;
        long g = 0;
        long b = 0;
        long count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = pixelArgb(image, x, y);
                if (((argb >>> 24) & 0xFF) <= 127) {
                    continue;
                }

                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
                b += argb & 0xFF;
                count++;
            }
        }

        if (count == 0) {
            return null;
        }

        return (int) ((r / count) << 16 | (g / count) << 8 | (b / count));
    }

    /**
     * Pixel as 0xAARRGGBB. For grayscale PNGs the raster is read directly:
     * {@code getRGB} would treat the values as linear gray and convert them
     * to sRGB, noticeably brightening them (e.g. stone 125 → 185).
     */
    private static int pixelArgb(BufferedImage image, int x, int y) {
        if (image.getColorModel().getColorSpace().getType() != ColorSpace.TYPE_GRAY) {
            return image.getRGB(x, y);
        }

        Raster raster = image.getRaster();
        int gray = scaleTo8Bits(raster.getSample(x, y, 0), image.getColorModel().getComponentSize(0));
        int alpha = 255;
        if (image.getColorModel().hasAlpha()) {
            alpha = scaleTo8Bits(
                    raster.getSample(x, y, 1), image.getColorModel().getComponentSize(1));
        }

        return (alpha << 24) | (gray << 16) | (gray << 8) | gray;
    }

    private static int scaleTo8Bits(int sample, int bits) {
        if (bits <= 8) {
            return sample;
        }

        return sample >> (bits - 8);
    }

    private static JsonObject readJson(Function<String, InputStream> resources, String resourcePath) {
        InputStream in = resources.apply(resourcePath);
        if (in == null) {
            return null;
        }

        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static BufferedImage readImage(Function<String, InputStream> resources, String resourcePath) {
        InputStream in = resources.apply(resourcePath);
        if (in == null) {
            return null;
        }

        try (InputStream stream = in) {
            return ImageIO.read(stream);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    private static String pathOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
