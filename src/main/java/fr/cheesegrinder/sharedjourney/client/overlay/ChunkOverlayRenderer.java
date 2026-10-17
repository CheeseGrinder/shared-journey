package fr.cheesegrinder.sharedjourney.client.overlay;

import fr.cheesegrinder.sharedjourney.api.client.MapView;
import fr.cheesegrinder.sharedjourney.api.client.event.MapRenderEvent;
import fr.cheesegrinder.sharedjourney.common.overlay.ChunkOverlayKind;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Shared drawing code of the chunk overlays, written against the public
 * overlay API only ({@link MapRenderEvent} and {@link MapView}) so that the
 * two built-in overlays are also reference implementations for addons.
 *
 * <p>Each visible region's bitmap is drawn as horizontal runs of flagged
 * chunks (one filled rectangle per run) in a pose where 1 unit = 1 block,
 * anchored near the view centre to keep float precision at large
 * coordinates.
 */
final class ChunkOverlayRenderer {

    /** Safety bound on the regions scanned per frame (extreme zoom-out). */
    private static final int MAX_VISIBLE_REGIONS = 2_500;

    /** The diagonal of a rotating square minimap overshoots the view by sqrt(2). */
    private static final double ROTATION_OVERSHOOT = 1.4143;

    private ChunkOverlayRenderer() {}

    /**
     * Draws one overlay on the map described by the event.
     *
     * @param color   ARGB fill of a flagged chunk
     * @param minZoom below this zoom (GUI px per block) nothing is drawn nor requested
     */
    static void draw(MapRenderEvent event, ChunkOverlayKind kind, int color, float minZoom) {
        MapView view = event.getView();
        float zoom = view.zoomScale();
        if (zoom < minZoom) {
            return;
        }

        ResourceLocation dimension = view.dimension();
        double reach = Math.max(view.viewWidth(), view.viewHeight()) / 2.0 * ROTATION_OVERSHOOT / zoom + 16;
        int minRx = Math.floorDiv((int) Math.floor(view.centerX() - reach), RegionKey.REGION_BLOCKS);
        int maxRx = Math.floorDiv((int) Math.floor(view.centerX() + reach), RegionKey.REGION_BLOCKS);
        int minRz = Math.floorDiv((int) Math.floor(view.centerZ() - reach), RegionKey.REGION_BLOCKS);
        int maxRz = Math.floorDiv((int) Math.floor(view.centerZ() + reach), RegionKey.REGION_BLOCKS);
        if ((long) (maxRx - minRx + 1) * (maxRz - minRz + 1) > MAX_VISIBLE_REGIONS) {
            return;
        }

        GuiGraphics gg = event.getGraphics();
        int originX = (int) Math.floor(view.centerX());
        int originZ = (int) Math.floor(view.centerZ());
        gg.pose().pushPose();
        gg.pose().translate((float) view.screenX(originX), (float) view.screenY(originZ), 0f);
        gg.pose().scale(zoom, zoom, 1f);
        for (int rx = minRx; rx <= maxRx; rx++) {
            for (int rz = minRz; rz <= maxRz; rz++) {
                long[] bits = ChunkOverlayStore.lookup(kind, dimension, rx, rz);
                if (bits != null && bits.length == ChunkOverlayKind.REGION_WORDS) {
                    drawRegion(gg, bits, rx, rz, originX, originZ, color);
                }
            }
        }
        gg.pose().popPose();
        ChunkOverlayStore.flush(kind);
    }

    /** Fills the flagged chunks of one region, merging neighbours of a row into one rectangle. */
    private static void drawRegion(GuiGraphics gg, long[] bits, int rx, int rz, int originX, int originZ, int color) {
        int baseX = rx * RegionKey.REGION_BLOCKS - originX;
        int baseZ = rz * RegionKey.REGION_BLOCKS - originZ;
        for (int row = 0; row < RegionKey.REGION_CHUNKS; row++) {
            int bit = row * RegionKey.REGION_CHUNKS;
            long rowBits = (bits[bit >> 6] >>> (bit & 63)) & 0xFFFFFFFFL;
            int z0 = baseZ + row * 16;
            while (rowBits != 0) {
                int start = Long.numberOfTrailingZeros(rowBits);
                int length = Long.numberOfTrailingZeros(~(rowBits >>> start));
                rowBits &= ~(((1L << length) - 1) << start);
                gg.fill(baseX + start * 16, z0, baseX + (start + length) * 16, z0 + 16, color);
            }
        }
    }
}
