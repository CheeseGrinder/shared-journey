package fr.cheesegrinder.sharedjourney.server.render;

/**
 * Couche TOPO : dégradé d'altitude (vert -> jaune/brun -> gris -> blanc),
 * eau en dégradé de bleu selon la profondeur, courbes de niveau tous les
 * 8 blocs.
 */
final class TopoRenderer {

    private TopoRenderer() {}

    static int render(RenderContext ctx, int wx, int wz) {
        int y = ctx.surfaceY(wx, wz);
        if (y <= ctx.chunk.getMinBuildHeight()) {
            return 0xFF000000;
        }

        ctx.pos.set(wx, y, wz);
        if (!ctx.chunk.getBlockState(ctx.pos).getFluidState().isEmpty()) {
            // Eau en dégradé de bleu selon profondeur relative au niveau de la mer
            int sea = ctx.level.getSeaLevel();
            float t = Math.clamp((sea - y) / 32f, 0f, 1f);
            int b = 255 - (int) (t * 140);
            return 0xFF000000 | (30 << 16) | (80 << 8) | b;
        }

        // Dégradé altitude : vert -> jaune/brun -> gris -> blanc
        int min = ctx.level.getSeaLevel();
        int max = ctx.chunk.getMaxBuildHeight();
        float t = Math.clamp((y - min) / (float) Math.max(1, max - min), 0f, 1f);
        int rgb;
        if (t < 0.33f) {
            rgb = ColorUtil.lerpRgb(0x2E8B37, 0xC8B454, t / 0.33f);
        } else if (t < 0.66f) {
            rgb = ColorUtil.lerpRgb(0xC8B454, 0x8A7355, (t - 0.33f) / 0.33f);
        } else {
            rgb = ColorUtil.lerpRgb(0x8A7355, 0xF2F2F2, (t - 0.66f) / 0.34f);
        }

        // Courbes de niveau tous les 8 blocs
        if (y % 8 == 0) {
            rgb = ColorUtil.scaleRgb(rgb, 0.72f);
        }

        return 0xFF000000 | rgb;
    }
}
