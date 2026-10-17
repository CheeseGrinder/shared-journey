package fr.cheesegrinder.sharedjourney.client.render;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lenient reader of one radar rule file. A malformed rule is logged and
 * skipped without discarding the rest of the file; comments and trailing
 * commas are tolerated by Gson's parser. A rule is dropped (never
 * half-applied) when it has an unknown key or an invalid value, because
 * ignoring a mistyped matcher would silently turn the rule into a
 * catch-all. Keys starting with an underscore are free-form comments.
 */
final class RadarRulesParser {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Every key a rule may carry. */
    private static final Set<String> RULE_KEYS = Set.of(
            "id",
            "enabled",
            "type",
            "tag",
            "kind",
            "name",
            "tamed",
            "owner",
            "category",
            "visible",
            "color",
            "style",
            "iconScale");

    private static final float MIN_ICON_SCALE = 0.25f;
    private static final float MAX_ICON_SCALE = 4f;

    private RadarRulesParser() {}

    /** Parses {@code reader}; {@code source} only names the file in logs. Null when unreadable. */
    static RadarRuleSettings.Parsed parse(Reader reader, String source) {
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                LOGGER.warn("Radar rules {}: the root must be a JSON object", source);
                return null;
            }

            return parseRoot(root.getAsJsonObject(), source);
        } catch (RuntimeException ex) {
            LOGGER.warn("Radar rules {}: unreadable file", source, ex);
            return null;
        }
    }

    private static RadarRuleSettings.Parsed parseRoot(JsonObject root, String source) {
        boolean replace = false;
        if (root.has("replace")) {
            JsonElement value = root.get("replace");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                replace = value.getAsBoolean();
            } else {
                LOGGER.warn("Radar rules {}: 'replace' must be a boolean", source);
            }
        }

        List<RadarRuleSettings.Rule> rules = new ArrayList<>();
        if (root.has("rules")) {
            if (root.get("rules").isJsonArray()) {
                parseRules(root.getAsJsonArray("rules"), source, rules);
            } else {
                LOGGER.warn("Radar rules {}: 'rules' must be an array", source);
            }
        }

        return new RadarRuleSettings.Parsed(replace, rules);
    }

    private static void parseRules(JsonArray array, String source, List<RadarRuleSettings.Rule> out) {
        for (int i = 0; i < array.size(); i++) {
            String where = source + " rules[" + i + "]";
            try {
                out.add(parseRule(array.get(i)));
            } catch (IllegalArgumentException ex) {
                LOGGER.warn("Radar rules {}: rule skipped: {}", where, ex.getMessage());
            }
        }
    }

    private static RadarRuleSettings.Rule parseRule(JsonElement element) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("the rule must be a JSON object");
        }

        JsonObject json = element.getAsJsonObject();
        checkKeys(json);

        String id = text(json, "id");
        boolean enabled = bool(json, "enabled", true);
        boolean visible = bool(json, "visible", true);
        RadarRuleSettings.Matcher matcher = parseMatcher(json);
        RadarRuleSettings.Category category = category(json);
        EntityDots.Style style = parseStyle(json, category);
        if (!enabled && id == null) {
            throw new IllegalArgumentException("'enabled': false only makes sense with an 'id' to disable");
        }

        return new RadarRuleSettings.Rule(id, enabled, matcher, category, visible, style);
    }

    private static void checkKeys(JsonObject json) {
        for (String key : json.keySet()) {
            if (!key.startsWith("_") && !RULE_KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown key '" + key + "'");
            }
        }
    }

    // ------------------------------------------------------------------
    // Matcher

    private static RadarRuleSettings.Matcher parseMatcher(JsonObject json) {
        Set<ResourceLocation> types = new HashSet<>();
        Set<String> namespaces = new HashSet<>();
        for (String entry : strings(json, "type")) {
            addType(entry, types, namespaces);
        }

        List<TagKey<EntityType<?>>> tags = new ArrayList<>();
        for (String entry : strings(json, "tag")) {
            tags.add(TagKey.create(Registries.ENTITY_TYPE, resourceLocation(entry, "tag")));
        }

        Set<RadarRuleSettings.Kind> kinds = EnumSet.noneOf(RadarRuleSettings.Kind.class);
        for (String entry : strings(json, "kind")) {
            kinds.add(enumValue(RadarRuleSettings.Kind.class, entry, "kind"));
        }

        List<Pattern> names = new ArrayList<>();
        for (String entry : strings(json, "name")) {
            names.add(glob(entry));
        }

        String ownerName = text(json, "owner");
        RadarRuleSettings.Owner owner = null;
        if (ownerName != null) {
            owner = enumValue(RadarRuleSettings.Owner.class, ownerName, "owner");
        }

        return new RadarRuleSettings.Matcher(
                Set.copyOf(types),
                Set.copyOf(namespaces),
                List.copyOf(tags),
                kinds,
                List.copyOf(names),
                boolOrNull(json, "tamed"),
                owner);
    }

    /** {@code namespace:*} matches a whole namespace, anything else is one entity type id. */
    private static void addType(String entry, Set<ResourceLocation> types, Set<String> namespaces) {
        if (entry.endsWith(":*")) {
            String namespace = entry.substring(0, entry.length() - 2);
            ResourceLocation probe = ResourceLocation.tryParse(namespace + ":probe");
            if (probe == null) {
                throw new IllegalArgumentException("invalid namespace in type '" + entry + "'");
            }

            namespaces.add(namespace);
            return;
        }

        types.add(resourceLocation(entry, "type"));
    }

    private static ResourceLocation resourceLocation(String entry, String key) {
        String cleaned = entry;
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1);
        }

        ResourceLocation id = ResourceLocation.tryParse(cleaned);
        if (id == null) {
            throw new IllegalArgumentException("'" + key + "': invalid id '" + entry + "'");
        }

        return id;
    }

    /** Case-insensitive glob where {@code *} is any run of characters and {@code ?} any single one. */
    private static Pattern glob(String text) {
        StringBuilder regex = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }

        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

    // ------------------------------------------------------------------
    // Effect

    private static RadarRuleSettings.Category category(JsonObject json) {
        String name = text(json, "category");
        if (name == null) {
            return null;
        }

        return enumValue(RadarRuleSettings.Category.class, name, "category");
    }

    private static EntityDots.Style parseStyle(JsonObject json, RadarRuleSettings.Category category) {
        int color = EntityDots.COLOR_PASSIVE;
        if (category != null) {
            color = category.defaultColor();
        }
        if (json.has("color")) {
            color = color(text(json, "color"));
        }

        boolean dotOnly = false;
        String style = text(json, "style");
        if (style != null) {
            dotOnly = parseDotOnly(style);
        }

        return new EntityDots.Style(color, dotOnly, iconScale(json));
    }

    private static boolean parseDotOnly(String style) {
        String lower = style.toLowerCase(Locale.ROOT);
        if (lower.equals("dot")) {
            return true;
        }
        if (lower.equals("auto")) {
            return false;
        }

        throw new IllegalArgumentException("'style' must be 'auto' or 'dot', got '" + style + "'");
    }

    private static float iconScale(JsonObject json) {
        if (!json.has("iconScale")) {
            return 1f;
        }

        JsonElement value = json.get("iconScale");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("'iconScale' must be a number");
        }

        float scale = value.getAsFloat();
        if (Float.isNaN(scale) || scale <= 0f) {
            throw new IllegalArgumentException("'iconScale' must be positive");
        }

        return Math.clamp(scale, MIN_ICON_SCALE, MAX_ICON_SCALE);
    }

    /** {@code #RRGGBB} (opaque) or {@code #AARRGGBB}; the leading {@code #} is optional. */
    private static int color(String text) {
        if (text == null) {
            throw new IllegalArgumentException("'color' must be a string like \"#RRGGBB\"");
        }

        String hex = text;
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }

        if (hex.length() != 6 && hex.length() != 8) {
            throw new IllegalArgumentException("'color' must be #RRGGBB or #AARRGGBB, got '" + text + "'");
        }

        try {
            long value = Long.parseLong(hex, 16);
            if (hex.length() == 6) {
                value |= 0xFF000000L;
            }

            return (int) value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("'color' is not hexadecimal: '" + text + "'");
        }
    }

    // ------------------------------------------------------------------
    // Primitive readers (each throws IllegalArgumentException on a wrong type)

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name, String key) {
        try {
            return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("'" + key + "': unknown value '" + name + "'");
        }
    }

    /** A string or a non-empty array of strings; an absent key gives an empty list. */
    private static List<String> strings(JsonObject json, String key) {
        if (!json.has(key)) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        JsonElement value = json.get(key);
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                out.add(stringValue(item, key));
            }
        } else {
            out.add(stringValue(value, key));
        }

        if (out.isEmpty()) {
            throw new IllegalArgumentException("'" + key + "' must not be empty");
        }

        return out;
    }

    private static String stringValue(JsonElement value, String key) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }

        throw new IllegalArgumentException("'" + key + "' must be a string or an array of strings");
    }

    private static String text(JsonObject json, String key) {
        if (!json.has(key)) {
            return null;
        }

        JsonElement value = json.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }

        throw new IllegalArgumentException("'" + key + "' must be a string");
    }

    private static Boolean boolOrNull(JsonObject json, String key) {
        if (!json.has(key)) {
            return null;
        }

        JsonElement value = json.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }

        throw new IllegalArgumentException("'" + key + "' must be a boolean");
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        Boolean value = boolOrNull(json, key);
        if (value == null) {
            return fallback;
        }

        return value;
    }
}
