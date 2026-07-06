package fr.cheesegrinder.sharedjourney.server.render;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.awt.Color;

/**
 * Couche BIOME : une couleur par biome. Couleur d'herbe si le biome en
 * définit une, sinon couleur déterministe dérivée de l'identifiant du biome
 * (stable entre serveur et clients).
 */
final class BiomeRenderer {

    private BiomeRenderer() {}

    static int render(RenderContext ctx, int wx, int wz) {
        // Zoom BiomeManager branché sur le chunk (et pas level.getBiome) :
        // sûr depuis un thread de rendu, frontières bloc par bloc comme en jeu.
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
        // Couleurs saturées et lisibles
        float hue = ((h & 0xFFFF) % 360) / 360f;
        return Color.HSBtoRGB(hue, 0.55f, 0.80f) & 0xFFFFFF;
    }
}
