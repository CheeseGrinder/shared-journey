package fr.cheesegrinder.sharedjourney.server.render;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.awt.Color;

/**
 * BIOME layer: one color per biome. Grass color when the biome defines one,
 * otherwise a deterministic color derived from the biome identifier (stable
 * across server and clients).
 */
final class BiomeRenderer {

    private BiomeRenderer() {}

    static int render(RenderContext ctx, int wx, int wz) {
        // BiomeManager zoom plugged into the chunk (not level.getBiome):
        // safe from a render thread, block-by-block borders like in-game.
        Holder<Biome> biome = ctx.zoom.getBiome(ctx.pos.set(wx, ctx.level.getSeaLevel(), wz));
        var effects = biome.value().getSpecialEffects();
        int rgb = effects.getGrassColorOverride()
                .orElseGet(() -> hashColor(biome.unwrapKey()
                        .map(ResourceKey::location)
                        .orElse(ResourceLocation.withDefaultNamespace("plains"))));
        return 0xFF000000 | rgb;
    }

    private static int hashColor(ResourceLocation id) {
        int h = id.toString().hashCode();
        // Saturated, readable colors
        float hue = ((h & 0xFFFF) % 360) / 360f;
        return Color.HSBtoRGB(hue, 0.55f, 0.80f) & 0xFFFFFF;
    }
}
