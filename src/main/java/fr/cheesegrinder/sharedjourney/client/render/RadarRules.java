package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Radar categories: which entities the radar draws, and how, decided by
 * ordered JSON rules instead of fixed code. The first rule whose matcher
 * accepts an entity decides; the on/off toggles of the radar config
 * ({@code showHostile}, {@code showPassive}, {@code showPets},
 * {@code showVillagers}) gate the rules that name the matching category.
 *
 * <p>Files are layered like the mob icon settings (see {@link MobIconTweaks}):
 * <ol>
 *   <li>the bundled defaults, {@code assets/sharedjourney/sharedjourney/radar_rules/*.json},
 *       which reproduce the classic hostile / passive / pets / villagers
 *       behavior;</li>
 *   <li>any other mod or resource pack, {@code assets/<namespace>/sharedjourney/radar_rules/*.json};</li>
 *   <li>the user's {@code config/sharedjourney/radar_rules/*.json}, re-read
 *       by {@code /sj radar reload} without restarting.</li>
 * </ol>
 *
 * <p>The sources are merged so that a later source can both add and amend
 * rules: its rules are placed <em>before</em> those of the earlier sources
 * (a user rule beats a bundled one), a rule carrying the {@code id} of an
 * existing rule replaces it in place (or removes it with
 * {@code "enabled": false}), and a file with {@code "replace": true} first
 * discards everything below it. See {@link RadarRulesParser} for the file
 * format and {@link RadarRuleSettings.Matcher} for the semantics.
 *
 * <p>Only living, non-player entities are ever candidates (players are drawn
 * as skin heads from the server's positions). The type-level part of every
 * rule (type, namespace, tag, kind) is resolved once per entity type and
 * cached; only instance-level conditions (name, tamed, owner) are evaluated
 * per entity and per frame.
 */
public final class RadarRules {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Resource directory (under each namespace's assets) holding the rule files. */
    private static final String RESOURCE_DIR = "sharedjourney/radar_rules";

    private static final RadarRuleSettings.Rule[] NO_RULES = new RadarRuleSettings.Rule[0];

    /** The merged rules in force, with the per-entity-type resolution cache they own. */
    private static final class Table {

        static final Table EMPTY = new Table(List.of());

        private final RadarRuleSettings.Rule[] rules;
        private final Map<EntityType<?>, RadarRuleSettings.Rule[]> candidates = new ConcurrentHashMap<>();

        Table(List<RadarRuleSettings.Rule> rules) {
            this.rules = rules.toArray(RadarRuleSettings.Rule[]::new);
        }

        int size() {
            return rules.length;
        }

        void invalidate() {
            candidates.clear();
        }

        /**
         * The rules that may decide for {@code e}'s type, in order, cut after
         * the first rule that needs no per-entity check (nothing after it
         * can be reached).
         */
        RadarRuleSettings.Rule[] candidatesFor(Entity e) {
            EntityType<?> type = e.getType();
            RadarRuleSettings.Rule[] cached = candidates.get(type);
            if (cached != null) {
                return cached;
            }

            List<RadarRuleSettings.Rule> found = new ArrayList<>();
            for (RadarRuleSettings.Rule rule : rules) {
                if (!rule.matcher().matchesStatic(type, e)) {
                    continue;
                }

                found.add(rule);
                if (!rule.matcher().isDynamic()) {
                    break;
                }
            }

            RadarRuleSettings.Rule[] result = NO_RULES;
            if (!found.isEmpty()) {
                result = found.toArray(RadarRuleSettings.Rule[]::new);
            }

            candidates.put(type, result);
            return result;
        }
    }

    private static volatile Table table = Table.EMPTY;

    private RadarRules() {}

    /** Reload listener to register on the client: re-reads the files with the resources. */
    public static PreparableReloadListener reloadListener() {
        return new Listener();
    }

    /** Re-reads every rule file. Returns the number of rules now in force. */
    public static int reloadNow() {
        apply(load(Minecraft.getInstance().getResourceManager()));
        return table.size();
    }

    /** Forgets the per-type resolutions (entity type tags changed, e.g. on joining a server). */
    public static void invalidateCaches() {
        table.invalidate();
    }

    /**
     * The first rule accepting {@code e}, or null when none does. The
     * caller applies the visibility and the category toggle.
     */
    static RadarRuleSettings.Rule resolve(Entity e) {
        for (RadarRuleSettings.Rule rule : table.candidatesFor(e)) {
            if (!rule.matcher().isDynamic() || rule.matcher().matchesDynamic(e)) {
                return rule;
            }
        }

        return null;
    }

    private static void apply(Table loaded) {
        table = loaded;
        if (loaded.size() == 0) {
            LOGGER.warn("Radar rules: no rule in force, the entity radar will draw nothing");
        }
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
            acc.add(RadarRulesParser.parse(reader, id.toString()));
        } catch (IOException ex) {
            LOGGER.warn("Radar rules {}: could not be read", id, ex);
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
            LOGGER.warn("Radar rules: could not list {}", dir, ex);
        }
    }

    private static void readConfigFile(Path file, Accumulator acc) {
        try (Reader reader = Files.newBufferedReader(file)) {
            acc.add(RadarRulesParser.parse(reader, "config/" + file.getFileName()));
        } catch (IOException ex) {
            LOGGER.warn("Radar rules {}: could not be read", file, ex);
        }
    }

    /** The user's override folder, {@code config/sharedjourney/radar_rules}. */
    static Path configFolder() {
        return FMLPaths.CONFIGDIR.get().resolve(SharedJourneyConstants.MOD_ID).resolve("radar_rules");
    }

    /** Merges parsed files, lowest priority first, into the final {@link Table}. */
    private static final class Accumulator {

        /** Highest priority first, i.e. in matching order. */
        private final List<RadarRuleSettings.Rule> rules = new ArrayList<>();

        void add(RadarRuleSettings.Parsed parsed) {
            if (parsed == null) {
                return;
            }

            if (parsed.replace()) {
                rules.clear();
            }

            List<RadarRuleSettings.Rule> fresh = new ArrayList<>();
            for (RadarRuleSettings.Rule rule : parsed.rules()) {
                merge(rule, fresh);
            }

            rules.addAll(0, fresh);
        }

        /** Replaces or removes the rule with the same id, otherwise queues {@code rule} in front. */
        private void merge(RadarRuleSettings.Rule rule, List<RadarRuleSettings.Rule> fresh) {
            if (rule.id() != null && amendExisting(rule, fresh)) {
                return;
            }

            if (rule.enabled()) {
                fresh.add(rule);
            }
        }

        private boolean amendExisting(RadarRuleSettings.Rule rule, List<RadarRuleSettings.Rule> fresh) {
            return amend(rules, rule) || amend(fresh, rule);
        }

        private static boolean amend(List<RadarRuleSettings.Rule> list, RadarRuleSettings.Rule rule) {
            for (int i = 0; i < list.size(); i++) {
                if (rule.id().equals(list.get(i).id())) {
                    replaceAt(list, i, rule);
                    return true;
                }
            }

            return false;
        }

        private static void replaceAt(List<RadarRuleSettings.Rule> list, int index, RadarRuleSettings.Rule rule) {
            if (rule.enabled()) {
                list.set(index, rule);
            } else {
                list.remove(index);
            }
        }

        Table build() {
            return new Table(rules);
        }
    }

    /** Reads the files off-thread, swaps them in on the main thread. */
    private static final class Listener extends SimplePreparableReloadListener<Table> {

        @Override
        protected Table prepare(ResourceManager resources, ProfilerFiller profiler) {
            return load(resources);
        }

        @Override
        protected void apply(Table loaded, ResourceManager resources, ProfilerFiller profiler) {
            RadarRules.apply(loaded);
            LOGGER.info("Loaded {} radar rules", loaded.size());
        }
    }
}
