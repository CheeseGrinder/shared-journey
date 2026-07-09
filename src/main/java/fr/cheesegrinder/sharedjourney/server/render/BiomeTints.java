package fr.cheesegrinder.sharedjourney.server.render;

import fr.cheesegrinder.sharedjourney.common.config.EngineServerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Biome-dependent colors (grass, foliage, water) server-side.
 * Vanilla's texture colormaps are not loaded on a dedicated server: we
 * approximate the colormaps' temperature/downfall triangle (verified close
 * to the real values: plains ≈ 0x8CBD57 vs 0x91BD59).
 */
final class BiomeTints {

    private BiomeTints() {}

    /** Biome-dependent color sampler at a given position. */
    @FunctionalInterface
    interface Sampler {
        int colorAt(Biome biome, int wx, int wz);
    }

    /**
     * Color averaged over neighboring blocks (equivalent of the vanilla
     * client's biome blending): softens borders between biomes instead of a
     * hard edge. The radius comes from the server config (biomeBlendRadius,
     * 0 = disabled, 2 = vanilla equivalent).
     */
    static int blended(BiomeManager zoom, int wx, int y, int wz, Sampler sampler) {
        int radius = EngineServerConfig.BIOME_BLEND_RADIUS.get();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        if (radius <= 0) {
            return sampler.colorAt(zoom.getBiome(p.set(wx, y, wz)).value(), wx, wz) & 0xFFFFFF;
        }

        int r = 0;
        int g = 0;
        int b = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Biome biome = zoom.getBiome(p.set(wx + dx, y, wz + dz)).value();
                int c = sampler.colorAt(biome, wx + dx, wz + dz) & 0xFFFFFF;
                r += (c >>> 16) & 0xFF;
                g += (c >>> 8) & 0xFF;
                b += c & 0xFF;
            }
        }

        int samples = (2 * radius + 1) * (2 * radius + 1);
        return ((r / samples) << 16) | ((g / samples) << 8) | (b / samples);
    }

    static int grassColor(Biome biome, int wx, int wz) {
        var fx = biome.getSpecialEffects();
        int base = fx.getGrassColorOverride().orElseGet(() -> climateBlend(biome, 0xBFB755, 0x80B497, 0x47CD33));
        // Vanilla modifier (swamp, dark forest) — works server-side.
        return fx.getGrassColorModifier().modifyColor(wx, wz, base) & 0xFFFFFF;
    }

    static int foliageColor(Biome biome) {
        return biome.getSpecialEffects()
                .getFoliageColorOverride()
                .orElseGet(() -> climateBlend(biome, 0xAEA42A, 0x60A17B, 0x1ABF00));
    }

    /** Triangular hot-dry / cold / hot-wet interpolation of the colormaps. */
    private static int climateBlend(Biome biome, int hotDry, int cold, int hotWet) {
        float t = Math.clamp(biome.getBaseTemperature(), 0f, 1f);
        float d = Math.clamp(biome.getModifiedClimateSettings().downfall(), 0f, 1f);
        float r = d * t;
        float cw = 1f - t;
        int rr = clamp255(((hotDry >> 16) & 0xFF)
                + cw * (((cold >> 16) & 0xFF) - ((hotDry >> 16) & 0xFF))
                + r * (((hotWet >> 16) & 0xFF) - ((hotDry >> 16) & 0xFF)));
        int gg = clamp255(((hotDry >> 8) & 0xFF)
                + cw * (((cold >> 8) & 0xFF) - ((hotDry >> 8) & 0xFF))
                + r * (((hotWet >> 8) & 0xFF) - ((hotDry >> 8) & 0xFF)));
        int bb = clamp255(
                (hotDry & 0xFF) + cw * ((cold & 0xFF) - (hotDry & 0xFF)) + r * ((hotWet & 0xFF) - (hotDry & 0xFF)));
        return (rr << 16) | (gg << 8) | bb;
    }

    private static int clamp255(float v) {
        return Math.clamp((int) v, 0, 255);
    }
}
