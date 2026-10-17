package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.client.config.RadarClientConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Data model of the radar rule files (see {@link RadarRules}): the compiled
 * matcher and effect of one rule, and the radar categories that tie a rule
 * to the on/off toggles of the radar config.
 */
final class RadarRuleSettings {

    private RadarRuleSettings() {}

    /**
     * Radar categories: a rule that names one is hidden while the matching
     * toggle of the radar config is off. Each carries the dot color used
     * when a rule does not set its own.
     */
    enum Category {
        HOSTILE(EntityDots.COLOR_HOSTILE),
        PASSIVE(EntityDots.COLOR_PASSIVE),
        PETS(EntityDots.COLOR_PET),
        VILLAGERS(EntityDots.COLOR_VILLAGER);

        private final int defaultColor;

        Category(int defaultColor) {
            this.defaultColor = defaultColor;
        }

        int defaultColor() {
            return defaultColor;
        }

        /** The toggle of this category in the radar config (read live, so the settings screen applies at once). */
        boolean isEnabled() {
            return switch (this) {
                case HOSTILE -> RadarClientConfig.RADAR_HOSTILE.get();
                case PASSIVE -> RadarClientConfig.RADAR_PASSIVE.get();
                case PETS -> RadarClientConfig.RADAR_PETS.get();
                case VILLAGERS -> RadarClientConfig.RADAR_VILLAGERS.get();
            };
        }
    }

    /** Class-based entity families, the building blocks of the vanilla classification. */
    enum Kind {
        /** Any living entity (armor stands included). */
        LIVING,
        /** Any {@link Mob}. */
        MOB,
        /** Any {@link Enemy} (hostile mobs, slimes, phantoms...). */
        ENEMY,
        /** Any {@link Animal} (land animals). */
        ANIMAL,
        /** Any {@link WaterAnimal} (squid, fish, dolphins...). */
        WATER_ANIMAL,
        /** Any {@link AbstractVillager} (villagers and wandering traders). */
        VILLAGER,
        /** Any {@link TamableAnimal} (wolves, cats, parrots...), tamed or not. */
        TAMABLE;

        boolean matches(Entity e) {
            return switch (this) {
                case LIVING -> e instanceof LivingEntity;
                case MOB -> e instanceof Mob;
                case ENEMY -> e instanceof Enemy;
                case ANIMAL -> e instanceof Animal;
                case WATER_ANIMAL -> e instanceof WaterAnimal;
                case VILLAGER -> e instanceof AbstractVillager;
                case TAMABLE -> e instanceof TamableAnimal;
            };
        }
    }

    /** Whom a tamed animal belongs to, relative to the local player. */
    enum Owner {
        SELF,
        OTHER
    }

    /**
     * Compiled matcher. Every specified field must match (AND); the values
     * of a list are alternatives (OR). A null/empty field is not tested.
     * The first group ({@code types}, {@code namespaces}, {@code tags},
     * {@code kinds}) depends on the entity type only and is resolved once
     * per type; the second ({@code names}, {@code tamed}, {@code owner})
     * depends on the entity instance and is evaluated each time.
     */
    record Matcher(
            Set<ResourceLocation> types,
            Set<String> namespaces,
            List<TagKey<EntityType<?>>> tags,
            Set<Kind> kinds,
            List<Pattern> names,
            Boolean tamed,
            Owner owner) {

        /** Whether the instance-dependent part has anything to test. */
        boolean isDynamic() {
            return !names.isEmpty() || tamed != null || owner != null;
        }

        /** The type-level conditions; {@code sample} is any instance of {@code type}. */
        boolean matchesStatic(EntityType<?> type, Entity sample) {
            if (!types.isEmpty() || !namespaces.isEmpty()) {
                ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                if (!types.contains(id) && !namespaces.contains(id.getNamespace())) {
                    return false;
                }
            }

            if (!tags.isEmpty() && !anyTag(type)) {
                return false;
            }

            return kinds.isEmpty() || anyKind(sample);
        }

        /** The instance-level conditions. */
        boolean matchesDynamic(Entity e) {
            if (tamed != null && isTame(e) != tamed) {
                return false;
            }

            if (owner != null && !ownedBy(e, owner)) {
                return false;
            }

            return names.isEmpty() || nameMatches(e);
        }

        private boolean anyTag(EntityType<?> type) {
            for (TagKey<EntityType<?>> tag : tags) {
                if (type.is(tag)) {
                    return true;
                }
            }

            return false;
        }

        private boolean anyKind(Entity sample) {
            for (Kind kind : kinds) {
                if (kind.matches(sample)) {
                    return true;
                }
            }

            return false;
        }

        private boolean nameMatches(Entity e) {
            if (!e.hasCustomName()) {
                return false;
            }

            String text = e.getCustomName().getString();
            for (Pattern pattern : names) {
                if (pattern.matcher(text).matches()) {
                    return true;
                }
            }

            return false;
        }

        private static boolean isTame(Entity e) {
            return e instanceof TamableAnimal animal && animal.isTame();
        }

        private static boolean ownedBy(Entity e, Owner wanted) {
            if (!(e instanceof TamableAnimal animal) || !animal.isTame()) {
                return false;
            }

            UUID ownerId = animal.getOwnerUUID();
            if (ownerId == null) {
                return false;
            }

            boolean mine = Minecraft.getInstance().player != null
                    && ownerId.equals(Minecraft.getInstance().player.getUUID());
            return (wanted == Owner.SELF) == mine;
        }
    }

    /**
     * One compiled rule. {@code id} (nullable) lets a later file replace
     * or disable this rule in place; {@code enabled = false} marks a
     * tombstone that only removes the rule with the same id. {@code
     * category} (nullable) ties the rule to a radar toggle; {@code visible
     * = false} hides what the rule matches whatever the toggles say.
     */
    record Rule(
            String id, boolean enabled, Matcher matcher, Category category, boolean visible, EntityDots.Style style) {}

    /** The content of one file: its {@code replace} flag and its rules, in file order. */
    record Parsed(boolean replace, List<Rule> rules) {}
}
