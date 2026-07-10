import com.google.gson.GsonBuilder;

import fr.cheesegrinder.sharedjourney.server.render.TextureColorExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline generator for the bundled vanilla palette: enumerates every
 * blockstate of the vanilla client jar and runs TextureColorExtractor on it.
 * Rerun this whenever the Minecraft version is bumped.
 *
 * <p>Not part of the mod build — compile and run it by hand (any Java 21,
 * only Gson on the classpath, TextureColorExtractor has no Minecraft
 * imports). From the repo root, adapting the jar paths:
 *
 * <pre>
 * javac -cp &lt;gson.jar&gt; -d tools/out \
 *     src/main/java/fr/cheesegrinder/sharedjourney/server/render/TextureColorExtractor.java \
 *     tools/PaletteGenerator.java
 * java -cp "tools/out;&lt;gson.jar&gt;" PaletteGenerator \
 *     ~/.gradle/caches/neoformruntime/artifacts/minecraft_&lt;version&gt;_client.jar \
 *     src/main/resources/assets/sharedjourney/palette/vanilla.json
 * </pre>
 */
public class PaletteGenerator {

    /**
     * Server-side mirror of vanilla's client-only tint constants
     * (BlockColors/FoliageColor): fixed-tint blocks whose texture is
     * grayscale get their effective color baked into the palette, since the
     * renderer cannot query the client tint registry. Biome-dependent tints
     * (oak leaves, grass...) are NOT baked: the renderer detects their
     * grayscale palette color and applies the biome tint at render time.
     */
    private static final Map<String, String> BAKED_TINTS = Map.of(
            "minecraft:birch_leaves", "#80A755",
            "minecraft:spruce_leaves", "#619961",
            "minecraft:mangrove_leaves", "#92C648",
            "minecraft:lily_pad", "#208030");

    public static void main(String[] args) throws Exception {
        Path jarPath = Path.of(args[0]);
        Path outPath = Path.of(args[1]);
        String prefix = "assets/minecraft/blockstates/";

        TreeMap<String, String> palette = new TreeMap<>();
        int total = 0;

        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Function<String, InputStream> resources = path -> {
                ZipEntry entry = zip.getEntry(path);
                try {
                    return entry == null ? null : zip.getInputStream(entry);
                } catch (IOException e) {
                    return null;
                }
            };

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(prefix) || !name.endsWith(".json")) {
                    continue;
                }

                String block = "minecraft:" + name.substring(prefix.length(), name.length() - 5);
                total++;
                Integer color = TextureColorExtractor.extract(block, resources);
                if (color != null) {
                    palette.put(block, String.format("#%06X", color));
                }
            }
        }

        palette.putAll(BAKED_TINTS);

        Files.createDirectories(outPath.getParent());
        try (Writer writer = Files.newBufferedWriter(outPath)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(palette, writer);
        }

        System.out.println("Extracted " + palette.size() + "/" + total + " blocks");
        String[] samples = {
            "minecraft:cherry_leaves", "minecraft:oak_leaves", "minecraft:stone",
            "minecraft:grass_block", "minecraft:sand", "minecraft:azalea_leaves",
            "minecraft:water", "minecraft:deepslate", "minecraft:cobblestone",
            "minecraft:birch_leaves", "minecraft:spruce_leaves", "minecraft:lily_pad"
        };
        for (String sample : samples) {
            System.out.println(sample + " = " + palette.get(sample));
        }
    }
}
