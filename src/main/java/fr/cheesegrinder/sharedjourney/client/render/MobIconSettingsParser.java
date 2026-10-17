package fr.cheesegrinder.sharedjourney.client.render;

import net.minecraft.resources.ResourceLocation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lenient reader of one mob icon settings file. Unknown fields are
 * ignored, a malformed entry is logged and skipped without discarding
 * the rest of the file (comments and trailing commas are tolerated by
 * Gson's parser, which makes hand-edited files forgiving).
 */
final class MobIconSettingsParser {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The content of one file. */
    record Parsed(
            MobIconSettings.GlobalEntry globals,
            Map<String, MobIconSettings.Entry> models,
            Map<ResourceLocation, MobIconSettings.Entry> entities) {}

    private MobIconSettingsParser() {}

    /** Parses {@code reader}; {@code source} only names the file in logs. Null when unreadable. */
    static Parsed parse(Reader reader, String source) {
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                LOGGER.warn("Mob icon settings {}: the root must be a JSON object", source);
                return null;
            }

            return parseRoot(root.getAsJsonObject(), source);
        } catch (RuntimeException ex) {
            LOGGER.warn("Mob icon settings {}: unreadable file", source, ex);
            return null;
        }
    }

    private static Parsed parseRoot(JsonObject root, String source) {
        MobIconSettings.GlobalEntry globals = MobIconSettings.GlobalEntry.EMPTY;
        if (root.has("global") && root.get("global").isJsonObject()) {
            globals = parseGlobals(root.getAsJsonObject("global"), source);
        }

        Map<String, MobIconSettings.Entry> models = new HashMap<>();
        if (root.has("models") && root.get("models").isJsonObject()) {
            parseModels(root.getAsJsonObject("models"), source, models);
        }

        Map<ResourceLocation, MobIconSettings.Entry> entities = new HashMap<>();
        if (root.has("entities") && root.get("entities").isJsonObject()) {
            parseEntities(root.getAsJsonObject("entities"), source, entities);
        }

        return new Parsed(globals, models, entities);
    }

    private static MobIconSettings.GlobalEntry parseGlobals(JsonObject json, String source) {
        return new MobIconSettings.GlobalEntry(
                number(json, "wholeModelScale", source),
                number(json, "maxFill", source),
                number(json, "minFill", source));
    }

    private static void parseModels(JsonObject json, String source, Map<String, MobIconSettings.Entry> out) {
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            MobIconSettings.Entry entry = parseEntry(e.getValue(), source + " models." + e.getKey());
            if (entry != null) {
                out.put(e.getKey(), entry);
            }
        }
    }

    private static void parseEntities(
            JsonObject json, String source, Map<ResourceLocation, MobIconSettings.Entry> out) {
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            if (id == null) {
                LOGGER.warn("Mob icon settings {}: invalid entity id '{}'", source, e.getKey());
                continue;
            }

            MobIconSettings.Entry entry = parseEntry(e.getValue(), source + " entities." + e.getKey());
            if (entry != null) {
                out.put(id, entry);
            }
        }
    }

    private static MobIconSettings.Entry parseEntry(JsonElement element, String where) {
        if (!element.isJsonObject()) {
            LOGGER.warn("Mob icon settings {}: the entry must be a JSON object", where);
            return null;
        }

        JsonObject json = element.getAsJsonObject();
        return new MobIconSettings.Entry(
                number(json, "rotX", where),
                number(json, "rotY", where),
                mode(json, where),
                number(json, "scale", where),
                text(json, "headPart", where),
                number(json, "offsetX", where),
                number(json, "offsetY", where));
    }

    private static Float number(JsonObject json, String key, String where) {
        if (!json.has(key)) {
            return null;
        }

        JsonElement value = json.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return value.getAsFloat();
        }

        LOGGER.warn("Mob icon settings {}: '{}' must be a number", where, key);
        return null;
    }

    private static String text(JsonObject json, String key, String where) {
        if (!json.has(key)) {
            return null;
        }

        JsonElement value = json.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }

        LOGGER.warn("Mob icon settings {}: '{}' must be a string", where, key);
        return null;
    }

    private static MobIconSettings.Mode mode(JsonObject json, String where) {
        String name = text(json, "mode", where);
        if (name == null) {
            return null;
        }

        try {
            return MobIconSettings.Mode.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("Mob icon settings {}: unknown mode '{}' (auto, head or whole)", where, name);
            return null;
        }
    }
}
