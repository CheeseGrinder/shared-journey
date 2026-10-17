package fr.cheesegrinder.sharedjourney.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import net.neoforged.fml.loading.FMLPaths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Diagnostics behind {@code /sj icons dump}: writes what the icon
 * framing resolved for every registered living entity type (head or
 * whole model, size of the frame, final scale, where the settings come
 * from), so oversized or tiny icons — modded mobs included — can be
 * spotted at a glance and fixed with a settings entry.
 *
 * <p>Entities are only instantiated, never added to the world, and the
 * geometry is rebuilt here instead of going through the icon cache: the
 * dump never renders an icon nor alters what is cached.
 */
public final class MobIconDump {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Number of dumped mobs, and the file they were written to. */
    public record Result(int mobs, Path file) {}

    private MobIconDump() {}

    /** Writes the dump next to the settings folder; throws when it cannot be written. */
    public static Result write() throws IOException {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            throw new IOException("not in a world");
        }

        JsonObject root = new JsonObject();
        List<EntityType<?>> types = BuiltInRegistries.ENTITY_TYPE.stream()
                .sorted(Comparator.comparing((EntityType<?> t) ->
                        BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()))
                .toList();
        for (EntityType<?> type : types) {
            describe(mc, type, root);
        }

        Path file = FMLPaths.CONFIGDIR.get().resolve("sharedjourney").resolve("mob_icons_dump.json");
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(root, writer);
        }

        return new Result(root.size(), file);
    }

    /** Adds the entry of one entity type, silently skipping those that cannot be rendered as an icon. */
    private static void describe(Minecraft mc, EntityType<?> type, JsonObject out) {
        Entity entity = instantiate(mc, type);
        if (!(entity instanceof LivingEntity)) {
            return;
        }

        if (!(mc.getEntityRenderDispatcher().getRenderer(entity) instanceof LivingEntityRenderer<?, ?> living)) {
            return;
        }

        EntityModel<?> model = living.getModel();
        MobHeadIcons.Geometry geometry = MobHeadIcons.build(entity, model);
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        out.add(id.toString(), entry(type, model, geometry));
    }

    private static JsonObject entry(EntityType<?> type, EntityModel<?> model, MobHeadIcons.Geometry geometry) {
        JsonObject json = new JsonObject();
        json.addProperty("shows", shows(geometry));
        json.addProperty("frameBlocks", round(geometry.span()));
        json.addProperty("scale", round(geometry.scale()));
        json.addProperty("fill", round(geometry.span() * geometry.scale() / MobIconAtlas.CELL));
        json.addProperty("settingsFrom", MobIconTweaks.sourceOf(type, model));
        json.addProperty("model", model.getClass().getName());
        return json;
    }

    private static String shows(MobHeadIcons.Geometry geometry) {
        if (geometry.parts() == null) {
            return "failed";
        }

        if (geometry.parts().isEmpty()) {
            return "whole";
        }

        return "head";
    }

    private static Entity instantiate(Minecraft mc, EntityType<?> type) {
        try {
            return type.create(mc.level);
        } catch (Exception ex) {
            LOGGER.debug("Mob icon dump: cannot instantiate {}", type, ex);
            return null;
        }
    }

    private static double round(float value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
