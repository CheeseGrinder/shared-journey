package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;

import net.neoforged.fml.loading.FMLPaths;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Per-mob framing of the radar mob icons, read from JSON files instead
 * of being hardcoded, so a head that is too big or too small can be
 * fixed without a rebuild — for vanilla and modded mobs alike.
 *
 * <p>Files are layered, later sources overriding earlier ones field by
 * field (a file only has to give the values it changes):
 * <ol>
 *   <li>the bundled defaults, {@code assets/sharedjourney/sharedjourney/mob_icons/*.json};</li>
 *   <li>any other mod or resource pack, {@code assets/<namespace>/sharedjourney/mob_icons/*.json}
 *       (lower namespaces alphabetically first, resource pack order within a file);</li>
 *   <li>the user's {@code config/sharedjourney/mob_icons/*.json}, applied last
 *       and re-read by {@code /sj icons reload} without restarting.</li>
 * </ol>
 *
 * <p>A file has three optional sections: {@code global} (tuning shared by
 * every icon), {@code models} (keyed by model class name, so a modded mob
 * reusing a vanilla model inherits its fix) and {@code entities} (keyed by
 * entity type id, taking precedence over the model entry). See
 * {@link MobIconSettings.Tweak} for the per-mob fields.
 */
public final class MobIconTweaks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Resource directory (under each namespace's assets) holding the settings files. */
    private static final String RESOURCE_DIR = "sharedjourney/mob_icons";

    /** The merged settings currently in force. */
    private record Table(
            MobIconSettings.Globals globals,
            Map<String, MobIconSettings.Entry> models,
            Map<ResourceLocation, MobIconSettings.Entry> entities) {

        static final Table DEFAULT = new Table(MobIconSettings.Globals.DEFAULT, Map.of(), Map.of());

        int entryCount() {
            return models.size() + entities.size();
        }
    }

    private static volatile Table table = Table.DEFAULT;

    private MobIconTweaks() {}

    /** Reload listener to register on the client: re-reads the files with the resources. */
    public static PreparableReloadListener reloadListener() {
        return new Listener();
    }

    /**
     * Re-reads every settings file and drops the cached icons so they are
     * rebuilt with the new values. Returns the number of model and entity
     * entries now in force.
     */
    public static int reloadNow() {
        apply(load(Minecraft.getInstance().getResourceManager()));
        return table.entryCount();
    }

    /** Global tuning in force. */
    static MobIconSettings.Globals globals() {
        return table.globals();
    }

    /** Framing of {@code type}, rendered with {@code model}: entity entry over model entry over the defaults. */
    static MobIconSettings.Tweak forEntity(EntityType<?> type, EntityModel<?> model) {
        Table current = table;
        MobIconSettings.Entry entry = modelEntry(current, model.getClass());
        entry = entry.overlay(current.entities().get(BuiltInRegistries.ENTITY_TYPE.getKey(type)));
        return entry.resolve();
    }

    /** Where the framing of {@code type} comes from, for diagnostics. */
    static String sourceOf(EntityType<?> type, EntityModel<?> model) {
        Table current = table;
        if (current.entities().containsKey(BuiltInRegistries.ENTITY_TYPE.getKey(type))) {
            return "entity";
        }

        if (modelEntry(current, model.getClass()) != MobIconSettings.Entry.EMPTY) {
            return "model";
        }

        return "default";
    }

    /**
     * The model entry for {@code modelClass}, merging the entries of its
     * superclasses (the most specific class wins field by field).
     */
    private static MobIconSettings.Entry modelEntry(Table current, Class<?> modelClass) {
        MobIconSettings.Entry result = MobIconSettings.Entry.EMPTY;
        for (Class<?> c = modelClass; c != null && c != Object.class; c = c.getSuperclass()) {
            MobIconSettings.Entry found = current.models().get(c.getName());
            if (found != null) {
                result = found.overlay(result);
            }
        }

        return result;
    }

    private static void apply(Table loaded) {
        table = loaded;
        MobHeadIcons.resetAll();
    }

    // ------------------------------------------------------------------
    // Loading

    /** Reads bundled, pack and config files and merges them in order. */
    private static Table load(ResourceManager resources) {
        Accumulator acc = new Accumulator();
        loadResources(resources, acc);
        loadConfigFolder(acc);
        return acc.build();
    }

    private static void loadResources(ResourceManager resources, Accumulator acc) {
        Map<ResourceLocation, List<Resource>> stacks =
                resources.listResourceStacks(RESOURCE_DIR, id -> id.getPath().endsWith(".json"));
        List<ResourceLocation> ids = new ArrayList<>(stacks.keySet());
        ids.sort(Comparator.comparing((ResourceLocation id) -> !SharedJourneyConstants.MOD_ID.equals(id.getNamespace()))
                .thenComparing(ResourceLocation::getNamespace)
                .thenComparing(ResourceLocation::getPath));
        for (ResourceLocation id : ids) {
            // Lowest-priority pack first, so higher packs override it.
            for (Resource resource : stacks.get(id)) {
                readResource(resource, id, acc);
            }
        }
    }

    private static void readResource(Resource resource, ResourceLocation id, Accumulator acc) {
        try (Reader reader = resource.openAsReader()) {
            acc.add(MobIconSettingsParser.parse(reader, id.toString()));
        } catch (IOException ex) {
            LOGGER.warn("Mob icon settings {}: could not be read", id, ex);
        }
    }

    private static void loadConfigFolder(Accumulator acc) {
        Path dir = configFolder();
        if (!Files.isDirectory(dir)) {
            return;
        }

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path file : sorted) {
                readConfigFile(file, acc);
            }
        } catch (IOException ex) {
            LOGGER.warn("Mob icon settings: could not list {}", dir, ex);
        }
    }

    private static void readConfigFile(Path file, Accumulator acc) {
        try (Reader reader = Files.newBufferedReader(file)) {
            acc.add(MobIconSettingsParser.parse(reader, "config/" + file.getFileName()));
        } catch (IOException ex) {
            LOGGER.warn("Mob icon settings {}: could not be read", file, ex);
        }
    }

    /** The user's override folder, {@code config/sharedjourney/mob_icons}. */
    static Path configFolder() {
        return FMLPaths.CONFIGDIR.get().resolve(SharedJourneyConstants.MOD_ID).resolve("mob_icons");
    }

    /** Merges parsed files in order into the final {@link Table}. */
    private static final class Accumulator {

        private MobIconSettings.GlobalEntry globals = MobIconSettings.GlobalEntry.EMPTY;
        private final Map<String, MobIconSettings.Entry> models = new HashMap<>();
        private final Map<ResourceLocation, MobIconSettings.Entry> entities = new HashMap<>();

        void add(MobIconSettingsParser.Parsed parsed) {
            if (parsed == null) {
                return;
            }

            globals = globals.overlay(parsed.globals());
            parsed.models().forEach((key, entry) -> models.merge(key, entry, MobIconSettings.Entry::overlay));
            parsed.entities().forEach((key, entry) -> entities.merge(key, entry, MobIconSettings.Entry::overlay));
        }

        Table build() {
            return new Table(globals.resolve(), Map.copyOf(models), Map.copyOf(entities));
        }
    }

    /** Reads the files off-thread, swaps them in (and drops the icon cache) on the main thread. */
    private static final class Listener extends SimplePreparableReloadListener<Table> {

        @Override
        protected Table prepare(ResourceManager resources, ProfilerFiller profiler) {
            return load(resources);
        }

        @Override
        protected void apply(Table loaded, ResourceManager resources, ProfilerFiller profiler) {
            MobIconTweaks.apply(loaded);
            LOGGER.info("Loaded {} mob icon setting entries", loaded.entryCount());
        }
    }
}
