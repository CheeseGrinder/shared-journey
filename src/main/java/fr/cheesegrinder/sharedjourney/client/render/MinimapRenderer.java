package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.api.client.MapMarker;
import fr.cheesegrinder.sharedjourney.api.client.MapView;
import fr.cheesegrinder.sharedjourney.api.client.event.MapLayerChangedEvent;
import fr.cheesegrinder.sharedjourney.api.client.event.MapRenderEvent;
import fr.cheesegrinder.sharedjourney.client.compat.JourneyMapFullscreenBridge;
import fr.cheesegrinder.sharedjourney.client.config.MapClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.MinimapClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.RadarClientConfig;
import fr.cheesegrinder.sharedjourney.client.event.ClientInputEvents;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.service.MapMarkerStore;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;
import fr.cheesegrinder.sharedjourney.common.util.Lang;
import fr.cheesegrinder.sharedjourney.common.util.UndergroundCheck;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import net.neoforged.neoforge.common.NeoForge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimap HUD (spec §6.1): server tiles, optional dynamic rotation
 * (rendered via pose matrices), keyboard zoom, round or square shape,
 * filterable entity radar whose radius is capped by the server
 * (anti-cheat), and waypoints.
 */
public final class MinimapRenderer {

    private MinimapRenderer() {}

    private static final float ZOOM_MIN = 0.25f;
    private static final float ZOOM_MAX = 4.0f;
    private static final int CIRCLE_SEGMENTS = 64;
    /** Width (px) of the alpha ramp antialiasing the circle edges. */
    private static final float FEATHER = 0.75f;
    /** Dark gray (Discord-style) visible under chunks not yet received. */
    public static final int BACKGROUND = 0xFF36393F;
    /** Violet veil over chunks not yet re-rendered by a running server regen. */
    public static final int STALE_OVERLAY = 0x66C05AE0;

    private static MapLayer currentLayer = null; // null = not yet initialized from config
    private static Boolean autoMode = null; // null = not yet initialized from config
    private static int caveBandIndex = 0;
    /** Screen pixels per block; negative = not yet seeded from zoomDefault. */
    private static float zoom = -1f;

    public static MapLayer currentLayer() {
        if (currentLayer == null) {
            try {
                currentLayer = MapLayer.valueOf(
                        MapClientConfig.DEFAULT_LAYER.get().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                currentLayer = MapLayer.DAY;
            }
        }
        return currentLayer;
    }

    /** Auto mode follows day/night and going underground. */
    public static boolean autoMode() {
        if (autoMode == null) {
            autoMode = MapClientConfig.AUTO_LAYER.get();
        }
        return autoMode;
    }

    /**
     * Actually displayed layer. In auto mode: CAVE if the player is
     * underground, else NIGHT at night, else DAY — among the layers
     * allowed by the server for the current dimension.
     */
    public static MapLayer displayedLayer() {
        Minecraft mc = Minecraft.getInstance();
        if (!autoMode() || mc.level == null || mc.player == null) {
            return currentLayer();
        }

        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (allowed.contains(MapLayer.CAVE)
                && MapClientConfig.SHOW_CAVE.get()
                && UndergroundCheck.isUnderground(mc.level, mc.player)) {
            return MapLayer.CAVE;
        }

        if (allowed.contains(MapLayer.NIGHT) && isNightTime(mc.level)) {
            return MapLayer.NIGHT;
        }

        if (allowed.contains(MapLayer.DAY) || allowed.isEmpty()) {
            return MapLayer.DAY;
        }

        return allowed.getFirst();
    }

    /**
     * Night based on world time (13000-23000, the bounds of /time set
     * night/day). Level.isNight() can't be used here: it depends on
     * skyDarken, which is only updated server-side — client-side it's
     * always 0.
     */
    private static boolean isNightTime(Level level) {
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return time >= 13000L && time < 23000L;
    }

    /**
     * Layer cycle: Auto -> layer 1 -> ... -> layer N -> Auto.
     * Picking a layer by hand suspends auto mode until it cycles back to Auto.
     */
    public static void cycleLayer() {
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (allowed.isEmpty()) {
            return;
        }

        if (autoMode()) {
            autoMode = false;
            currentLayer = allowed.getFirst();
        } else {
            int idx = allowed.indexOf(currentLayer());
            if (idx < 0 || idx == allowed.size() - 1) {
                autoMode = true;
            } else {
                currentLayer = allowed.get(idx + 1);
            }
        }

        NeoForge.EVENT_BUS.post(new MapLayerChangedEvent(displayedLayer(), true));
    }

    public static void setLayer(MapLayer layer) {
        autoMode = false;
        currentLayer = layer;
        NeoForge.EVENT_BUS.post(new MapLayerChangedEvent(layer, true));
    }

    /** Current minimap zoom, seeded from the zoomDefault config on first use. */
    private static float currentZoom() {
        if (zoom < 0f) {
            zoom = MinimapClientConfig.MINIMAP_ZOOM_DEFAULT.get().floatValue();
        }

        return zoom;
    }

    public static void zoomIn() {
        zoom = Math.min(ZOOM_MAX, currentZoom() * 1.25f);
    }

    public static void zoomOut() {
        zoom = Math.max(ZOOM_MIN, currentZoom() / 1.25f);
    }

    public static int currentCaveBand() {
        List<Integer> bands = ClientMapCache.caveBands;
        if (bands.isEmpty()) {
            return 0;
        }

        // Automatically follows the band the player is in, if available.
        Player p = Minecraft.getInstance().player;
        if (p != null) {
            int band = Math.floorDiv(p.blockPosition().getY(), 16);
            int i = bands.indexOf(band);
            if (i >= 0) {
                caveBandIndex = i;
            }
        }
        caveBandIndex = Math.min(caveBandIndex, bands.size() - 1);
        return bands.get(Math.max(0, caveBandIndex));
    }

    // ------------------------------------------------------------------

    public static void render(GuiGraphics gg, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui || !ClientInputEvents.minimapVisible) {
            return;
        }

        if (mc.getDebugOverlay().showDebugScreen()) {
            return;
        }

        if (!MinimapClientConfig.MINIMAP_ENABLED.get()) {
            return;
        }

        MapLayer layer = displayedLayer();
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (!allowed.isEmpty() && !allowed.contains(layer)) {
            layer = allowed.getFirst();
        }

        int size = MinimapClientConfig.MINIMAP_SIZE.get();
        int margin = 6;
        int sw = gg.guiWidth();
        int sh = gg.guiHeight();
        int x =
                switch (MinimapClientConfig.MINIMAP_CORNER.get()) {
                    case TOP_LEFT, BOTTOM_LEFT -> margin;
                    case TOP_RIGHT, BOTTOM_RIGHT -> sw - size - margin;
                };
        boolean topAnchored = MinimapClientConfig.MINIMAP_CORNER.get() == MinimapClientConfig.Corner.TOP_LEFT
                || MinimapClientConfig.MINIMAP_CORNER.get() == MinimapClientConfig.Corner.TOP_RIGHT;
        int y =
                switch (MinimapClientConfig.MINIMAP_CORNER.get()) {
                    case TOP_LEFT, TOP_RIGHT -> margin;
                    case BOTTOM_LEFT, BOTTOM_RIGHT -> sh - size - margin;
                };
        if (topAnchored) {
            // Reserve the time line above the map.
            y += 11;
        }

        boolean rotate = MinimapClientConfig.MINIMAP_ROTATE.get();
        boolean circle = MinimapClientConfig.MINIMAP_SHAPE.get() == MinimapClientConfig.Shape.CIRCLE;
        // In rotation mode, the map turns around the player so "forward" stays up.
        float yaw = player.getYRot();

        int half = size / 2;
        int cx = x + half, cy = y + half;

        // Gray background (not-yet-received areas); the round border is
        // drawn last, on top of the content.
        if (circle) {
            gg.flush();
            fillCircle(gg, cx, cy, half + 1, BACKGROUND);
            // Depth mask: the square's corners (outside the circle) become
            // "in front of" the content, which then fails the depth test there.
            maskCorners(gg, cx, cy, half, half + 2);
        } else {
            gg.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF202020);
            gg.fill(x, y, x + size, y + size, BACKGROUND);
        }

        double px = player.getX();
        double pz = player.getZ();
        int band = layer == MapLayer.CAVE ? currentCaveBand() : 0;
        var dim = player.level().dimension();

        gg.enableScissor(x, y, x + size, y + size);

        gg.pose().pushPose();
        if (rotate) {
            // Dynamic rotation (spec §6.1) around the minimap's center.
            gg.pose().translate(cx, cy, 0);
            gg.pose().mulPose(Axis.ZP.rotationDegrees(-yaw - 180f));
            gg.pose().translate(-cx, -cy, 0);
        }
        if (currentZoom() != 1.0f) {
            // Keyboard zoom: scale around the center (1 screen px = zoom blocks).
            gg.pose().translate(cx, cy, 0);
            gg.pose().scale(zoom, zoom, 1f);
            gg.pose().translate(-cx, -cy, 0);
        }

        // Window of blocks to cover. A circle is rotation-invariant; for a
        // rotating square, the diagonal overshoots (half * sqrt(2)).
        double factor = (rotate && !circle) ? 1.4143 : 1.0;
        int reach = (int) Math.ceil(half * factor / zoom) + 1;
        int minRx = Math.floorDiv((int) px - reach, RegionKey.REGION_BLOCKS);
        int maxRx = Math.floorDiv((int) px + reach, RegionKey.REGION_BLOCKS);
        int minRz = Math.floorDiv((int) pz - reach, RegionKey.REGION_BLOCKS);
        int maxRz = Math.floorDiv((int) pz + reach, RegionKey.REGION_BLOCKS);

        // Anchors the content on the player's exact (double) position:
        // everything is then drawn in world coordinates. The old per-tile/
        // line integer rounding made seams flicker at region boundaries
        // when the player moved.
        gg.pose().translate((float) (cx - px), (float) (cy - pz), 0f);

        for (int rx = minRx; rx <= maxRx; rx++) {
            for (int rz = minRz; rz <= maxRz; rz++) {
                RegionKey key = new RegionKey(dim, layer, layer == MapLayer.CAVE ? band : 0, rx, rz);
                ClientMapCache.Region region = ClientMapCache.getOrLoad(key);
                if (region == null) {
                    continue;
                }

                gg.blit(
                        region.texture(),
                        rx * RegionKey.REGION_BLOCKS,
                        rz * RegionKey.REGION_BLOCKS,
                        0f,
                        0f,
                        RegionKey.REGION_BLOCKS,
                        RegionKey.REGION_BLOCKS,
                        RegionKey.REGION_BLOCKS,
                        RegionKey.REGION_BLOCKS);
                if (ClientMapCache.regenActive) {
                    drawRegenVeil(gg, dim.location(), rx, rz, region.contentMask());
                }
            }
        }

        // ---- Chunk grid (boundaries every 16 blocks), in the content's
        // pose: follows rotation and zoom. Only shown from zoom 1.0 onward
        // (equivalent of the fullscreen map's 2048 label).
        if (MapClientConfig.SHOW_GRID.get() && zoom >= 1.0f) {
            int gridColor = 0x38000000;
            int gxStart = Math.floorDiv((int) px - reach, 16) * 16;
            int gzStart = Math.floorDiv((int) pz - reach, 16) * 16;
            for (int gx = gxStart; gx <= (int) px + reach; gx += 16) {
                drawGridLine(gg, true, gx, (int) pz - reach, (int) pz + reach, gridColor);
            }
            for (int gz = gzStart; gz <= (int) pz + reach; gz += 16) {
                drawGridLine(gg, false, gz, (int) px - reach, (int) px + reach, gridColor);
            }
        }

        // ---- Entity radar (spec §6.1): radius capped by the server
        if (RadarClientConfig.RADAR_ENABLED.get() && ClientMapCache.radarMaxRadius > 0) {
            int radius = Math.min(RadarClientConfig.RADAR_RADIUS.get(), ClientMapCache.radarMaxRadius);
            AABB box = player.getBoundingBox().inflate(radius, 32, radius);
            for (Entity e : player.level().getEntities(player, box)) {
                Integer color = EntityDots.colorFor(e);
                if (color == null) {
                    continue;
                }

                double ex = e.getX() - px, ez = e.getZ() - pz;
                if (ex * ex + ez * ez > (double) radius * radius) {
                    continue;
                }

                // Head icon at constant screen size, kept upright: the
                // content pose is zoom-scaled (and rotated in rotate
                // mode), so both are countered locally. Dot fallback.
                boolean head = false;
                if (RadarClientConfig.RADAR_MOB_HEADS.get()) {
                    gg.pose().pushPose();
                    gg.pose().translate((float) e.getX(), (float) e.getZ(), 0f);
                    if (rotate) {
                        gg.pose().mulPose(Axis.ZP.rotationDegrees(yaw + 180f));
                    }
                    gg.pose().scale(1f / zoom, 1f / zoom, 1f);
                    head = MobHeadIcons.draw(gg, e, 0f, 0f, MobHeadIcons.ICON_SIZE);
                    gg.pose().popPose();
                }
                if (!head) {
                    EntityDots.draw(gg, (int) Math.floor(e.getX()), (int) Math.floor(e.getZ()), color);
                }
            }
        }

        gg.pose().popPose();

        // Bridged JourneyMap plugin overlays (Create rails/trains, RNS
        // deposits...): drawn within the minimap's scissor, via a pose that
        // maps the "screen center" expected by plugins onto the minimap's
        // center. No overlays on the CAVE layer.
        if (layer != MapLayer.CAVE) {
            JourneyMapFullscreenBridge.fireMinimapRender(gg, cx, cy, px, pz, zoom, layer, rotate ? -yaw - 180f : 0f);
        }

        // Public API overlays (api.client.event.MapRenderEvent): drawn in
        // the minimap's scissor, in a pose where (0,0) is the map's
        // top-left corner — rotated with the map content when rotation is
        // on, so world-anchored draws land on the right spot.
        MapView apiView = new MinimapView(size, size, px, pz, zoom, dim.location(), layer, band);
        gg.pose().pushPose();
        if (rotate) {
            gg.pose().translate(cx, cy, 0);
            gg.pose().mulPose(Axis.ZP.rotationDegrees(-yaw - 180f));
            gg.pose().translate(-cx, -cy, 0);
        }
        gg.pose().translate(x, y, 0);
        NeoForge.EVENT_BUS.post(new MapRenderEvent(gg, apiView, deltaTracker.getGameTimeDeltaPartialTick(true)));
        gg.pose().popPose();

        gg.disableScissor();

        if (circle) {
            // Flush the batched content (clipped by the mask) then restore
            // the corners' depth so it doesn't interfere with later layers.
            gg.flush();
            resetDepth(gg, x - 2, y - 2, x + size + 2, y + size + 2);
            drawMapBorder(gg, cx, cy, half);
        }

        // ---- Waypoints in screen space: constant size, and pinned ON the
        // minimap's border when out of view (direction indicator).
        double theta = rotate ? Math.toRadians(-yaw - 180f) : 0;
        double cosT = Math.cos(theta);
        double sinT = Math.sin(theta);
        float maxR = half;

        // Declarative API markers (api.client.MapMarkerApi), below the
        // waypoints and player markers, with the same border pinning when
        // clampToEdge is set (radial on a circle, rect on a square).
        for (MapMarker marker : MapMarkerStore.collect(apiView)) {
            double ox = (marker.x() - px) * zoom;
            double oz = (marker.z() - pz) * zoom;
            double sx = ox * cosT - oz * sinT;
            double sy = ox * sinT + oz * cosT;
            if (marker.clampToEdge()) {
                if (circle) {
                    double r = Math.sqrt(sx * sx + sy * sy);
                    if (r > maxR) {
                        sx = sx / r * maxR;
                        sy = sy / r * maxR;
                    }
                } else {
                    sx = Math.clamp(sx, -maxR, maxR);
                    sy = Math.clamp(sy, -maxR, maxR);
                }
            } else if (circle ? Math.sqrt(sx * sx + sy * sy) > maxR : (Math.abs(sx) > maxR || Math.abs(sy) > maxR)) {
                continue;
            }

            MapMarkerRenderer.draw(gg, marker, cx + (float) sx, cy + (float) sy, 0.9f);
        }

        for (Waypoint wp : WaypointStore.forDimension(dim.location())) {
            if (!MapClientConfig.SHOW_WAYPOINTS.get() || !WaypointStore.isShown(wp)) {
                continue;
            }

            double ox = (wp.x() + 0.5 - px) * zoom;
            double oz = (wp.z() + 0.5 - pz) * zoom;
            double sx = ox * cosT - oz * sinT;
            double sy = ox * sinT + oz * cosT;
            if (circle) {
                double r = Math.sqrt(sx * sx + sy * sy);
                if (r > maxR) {
                    sx = sx / r * maxR;
                    sy = sy / r * maxR;
                }
            } else {
                sx = Math.clamp(sx, -maxR, maxR);
                sy = Math.clamp(sy, -maxR, maxR);
            }
            if (Waypoint.SOURCE_BANNER.equals(wp.source())) {
                EntityDots.drawBannerIcon(gg, cx + (float) sx, cy + (float) sy, wp.colorRgb(), 0.9f);
            } else {
                EntityDots.drawWaypointDiamond(gg, cx + (float) sx, cy + (float) sy, wp.colorRgb(), 0.9f);
            }
        }

        // ---- Other players' heads (server positions, no distance limit),
        // pinned to the border like the waypoints.
        if (RadarClientConfig.RADAR_PLAYERS.get()) {
            var selfId = player.getUUID();
            for (var pos : ClientMapCache.playerPositions.values()) {
                if (pos.id().equals(selfId) || !pos.dimension().equals(dim.location())) {
                    continue;
                }

                double wx = pos.x();
                double wz = pos.z();
                // Live position if the player is tracked locally (smoother).
                Player live = mc.level == null ? null : mc.level.getPlayerByUUID(pos.id());
                if (live != null) {
                    wx = live.getX();
                    wz = live.getZ();
                }

                double ox = (wx - px) * zoom;
                double oz = (wz - pz) * zoom;
                double sx = ox * cosT - oz * sinT;
                double sy = ox * sinT + oz * cosT;
                if (circle) {
                    double r = Math.sqrt(sx * sx + sy * sy);
                    if (r > maxR) {
                        sx = sx / r * maxR;
                        sy = sy / r * maxR;
                    }
                } else {
                    sx = Math.clamp(sx, -maxR, maxR);
                    sy = Math.clamp(sy, -maxR, maxR);
                }
                EntityDots.drawPlayerHead(gg, cx + (int) Math.round(sx), cy + (int) Math.round(sy), pos.id(), 8);
            }
        }

        // Player arrow at the center. When rotating, the player "looks up"
        // (fixed arrow); otherwise the arrow follows the yaw.
        EntityDots.drawPlayerArrow(gg, cx, cy, rotate ? 0f : yaw + 180f, 0.85f);

        // Cardinal points set inward from the edge (inside the map, not on
        // the border), following the map's rotation.
        drawCardinals(gg, mc, cx, cy, half - 8, rotate ? -yaw - 180f : 0f);

        // Labels (reduced scale) on translucent black pills: time +
        // period above the map, biome and coordinates below (or stacked
        // above if the map is bottom-anchored).
        drawSmallCentered(gg, mc, timeText(mc.level), cx, y - 9, 0xFFFFFF);
        String biome = mc.level == null
                ? ""
                : mc.level
                        .getBiome(player.blockPosition())
                        .unwrapKey()
                        .map(k -> Component.translatable("biome."
                                        + k.location().getNamespace() + "."
                                        + k.location().getPath())
                                .getString())
                        .orElse("");
        String coords = player.blockPosition().getX() + ", "
                + player.blockPosition().getY() + ", "
                + player.blockPosition().getZ();
        if (topAnchored) {
            drawSmallCentered(gg, mc, biome, cx, y + size + 4, 0xFFFFFF);
            if (MinimapClientConfig.SHOW_COORDS.get()) {
                drawSmallCentered(gg, mc, coords, cx, y + size + 13, 0xCCCCCC);
            }
        } else {
            drawSmallCentered(gg, mc, biome, cx, y - 18, 0xFFFFFF);
            if (MinimapClientConfig.SHOW_COORDS.get()) {
                drawSmallCentered(gg, mc, coords, cx, y - 27, 0xCCCCCC);
            }
        }
    }

    /**
     * Violet veil over the chunks of a region the running server regen has
     * not re-rendered yet (world-coordinate pose, shared with the
     * fullscreen map). Horizontal runs of stale chunks are merged into
     * single quads. No progress mask received for the region = nothing
     * done: the whole region is veiled.
     */
    public static void drawRegenVeil(GuiGraphics gg, ResourceLocation dimension, int rx, int rz, long[] contentMask) {
        int x0 = rx * RegionKey.REGION_BLOCKS;
        int z0 = rz * RegionKey.REGION_BLOCKS;
        long[] mask = ClientMapCache.regenDoneMasks.get(new ClientMapCache.RegionPos(dimension, rx, rz));
        int chunkPx = RegionKey.REGION_BLOCKS / RegionKey.REGION_CHUNKS;
        for (int cz = 0; cz < RegionKey.REGION_CHUNKS; cz++) {
            int runStart = -1;
            for (int cx = 0; cx <= RegionKey.REGION_CHUNKS; cx++) {
                // Stale = not re-rendered yet AND actually painted in the
                // displayed image: never-explored chunks (transparent in
                // the PNG) never get a done bit in plain "regen" mode and
                // used to stay veiled forever. The cx == REGION_CHUNKS
                // sentinel column only closes the last run: the masks must
                // not be read there (bit 1024 is out of range).
                boolean stale = false;
                if (cx < RegionKey.REGION_CHUNKS) {
                    int bit = cz * RegionKey.REGION_CHUNKS + cx;
                    boolean notDone = mask == null || (mask[bit >> 6] & (1L << (bit & 63))) == 0;
                    boolean painted = contentMask == null || (contentMask[bit >> 6] & (1L << (bit & 63))) != 0;
                    stale = notDone && painted;
                }
                if (stale && runStart < 0) {
                    runStart = cx;
                } else if (!stale && runStart >= 0) {
                    gg.fill(
                            x0 + runStart * chunkPx,
                            z0 + cz * chunkPx,
                            x0 + cx * chunkPx,
                            z0 + (cz + 1) * chunkPx,
                            STALE_OVERLAY);
                    runStart = -1;
                }
            }
        }
    }

    /**
     * Grid line at a world coordinate, drawn in the content pose with a
     * constant 1-screen-px thickness centered on the chunk boundary. A
     * plain 1-block-wide fill was zoom px thick and sat entirely on one
     * side of the boundary, reading as an offset grid.
     */
    private static void drawGridLine(GuiGraphics gg, boolean vertical, int at, int from, int to, int color) {
        var pose = gg.pose();
        pose.pushPose();
        if (vertical) {
            pose.translate((float) at, 0f, 0f);
            pose.scale(1f / zoom, 1f, 1f);
            pose.translate(-0.5f, 0f, 0f);
            gg.fill(0, from, 1, to, color);
        } else {
            pose.translate(0f, (float) at, 0f);
            pose.scale(1f, 1f / zoom, 1f);
            pose.translate(0f, -0.5f, 0f);
            gg.fill(from, 0, to, 1, color);
        }
        pose.popPose();
    }

    /** Translucent black background shared by every minimap label. */
    private static final int LABEL_BG = 0xA0101010;

    /**
     * Centered text at reduced scale (full-size labels eat up the
     * screen) on a translucent black rounded pill, so it stays readable
     * on any backdrop.
     */
    private static void drawSmallCentered(GuiGraphics gg, Minecraft mc, String text, int cx, int y, int color) {
        if (text.isEmpty()) {
            return;
        }

        gg.pose().pushPose();
        gg.pose().translate(cx, y, 0);
        gg.pose().scale(0.75f, 0.75f, 1f);
        float halfW = mc.font.width(text) / 2f + 1f;
        drawPill(gg, -halfW, halfW, 3.5f, 6f, LABEL_BG);
        gg.drawCenteredString(mc.font, text, 0, 0, color);
        gg.pose().popPose();
    }

    /**
     * Stadium ("pill") between the two end centers (xL, cy) and
     * (xR, cy) with the given end radius: solid convex fan up to
     * r - FEATHER/2, then a rim fading to transparent at r + FEATHER/2
     * (an offset stadium is a stadium with a bigger radius, so the rim
     * feathers the straight edges too).
     */
    private static void drawPill(GuiGraphics gg, float xL, float xR, float cy, float r, int argb) {
        Matrix4f mat = gg.pose().last().pose();
        prepareShapeState();
        float solid = Math.max(0f, r - FEATHER / 2f);
        List<float[]> inner = pillOutline(xL, xR, cy, solid);
        List<float[]> outer = pillOutline(xL, xR, cy, r + FEATHER / 2f);

        BufferBuilder fan =
                Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        fan.addVertex(mat, (xL + xR) / 2f, cy, 0).setColor(argb);
        for (float[] p : inner) {
            fan.addVertex(mat, p[0], p[1], 0).setColor(argb);
        }
        BufferUploader.drawWithShader(fan.buildOrThrow());

        int transparent = argb & 0x00FFFFFF;
        BufferBuilder rim =
                Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < inner.size(); i++) {
            rim.addVertex(mat, outer.get(i)[0], outer.get(i)[1], 0).setColor(transparent);
            rim.addVertex(mat, inner.get(i)[0], inner.get(i)[1], 0).setColor(argb);
        }
        BufferUploader.drawWithShader(rim.buildOrThrow());
        endShapeState();
    }

    /**
     * Neutral GL state for the immediate translucent shapes. Everything
     * is set explicitly: a leftover shader tint, an altered blend
     * function or a disabled blend from a previous render (bridged
     * plugin overlays, RenderType teardowns) silently turns the
     * translucent backgrounds opaque or invisible otherwise.
     *
     * <p>Face culling is DISABLED while shapes draw ({@link
     * #endShapeState} restores it): the fans/strips here are wound
     * clockwise on screen, which GL treats as back-facing (vanilla GUI
     * quads are counter-clockwise) — with culling on, the pill interior
     * was discarded while its feather rim drew, leaving an outline with
     * no background.
     */
    private static void prepareShapeState() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    /** Restores the GUI's default culling after a shape draw. */
    private static void endShapeState() {
        RenderSystem.enableCull();
    }

    /** Closed stadium outline: right arc, left arc, first point repeated. */
    private static List<float[]> pillOutline(float xL, float xR, float cy, float r) {
        int arcSegments = 16;
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i <= arcSegments; i++) {
            double a = -Math.PI / 2 + Math.PI * i / arcSegments;
            out.add(new float[] {xR + (float) (Math.cos(a) * r), cy + (float) (Math.sin(a) * r)});
        }
        for (int i = 0; i <= arcSegments; i++) {
            double a = Math.PI / 2 + Math.PI * i / arcSegments;
            out.add(new float[] {xL + (float) (Math.cos(a) * r), cy + (float) (Math.sin(a) * r)});
        }
        out.add(out.get(0).clone());
        return out;
    }

    /** Formatted world time (no seconds) + period (day, sunset, night, sunrise). */
    private static String timeText(Level level) {
        if (level == null) {
            return "";
        }

        long t = Math.floorMod(level.getDayTime(), 24000L);
        int hours = (int) ((t / 1000 + 6) % 24);
        int minutes = (int) (t % 1000 * 60 / 1000);
        return String.format("%02d:%02d ", hours, minutes)
                + Component.translatable(periodKey(t)).getString();
    }

    private static String periodKey(long dayTime) {
        if (dayTime >= 22300) {
            return Lang.TIME_SUNRISE;
        }
        if (dayTime >= 13700) {
            return Lang.TIME_NIGHT;
        }
        if (dayTime >= 12000) {
            return Lang.TIME_SUNSET;
        }
        return Lang.TIME_DAY;
    }

    /**
     * Horizontal shift to apply to vanilla potion effect icons: when the
     * minimap occupies the top-right corner, the icons slide to its left
     * (HudLayoutEvents translates the EFFECTS layer by that amount).
     */
    public static int effectIconsShift(Minecraft mc) {
        if (mc.player == null || mc.options.hideGui || !ClientInputEvents.minimapVisible) {
            return 0;
        }

        if (mc.getDebugOverlay().showDebugScreen() || !MinimapClientConfig.MINIMAP_ENABLED.get()) {
            return 0;
        }

        if (MinimapClientConfig.MINIMAP_CORNER.get() != MinimapClientConfig.Corner.TOP_RIGHT) {
            return 0;
        }

        return MinimapClientConfig.MINIMAP_SIZE.get() + 12;
    }

    /**
     * N/E/S/W letters inside the minimap, on a round badge.
     * thetaDeg = rotation of the map content (0 = north up);
     * the letters follow along.
     */
    private static void drawCardinals(GuiGraphics gg, Minecraft mc, int cx, int cy, float radius, float thetaDeg) {
        // The badges are drawn in immediate mode: flush the pending GUI
        // batch first to preserve the stacking order.
        gg.flush();
        double theta = Math.toRadians(thetaDeg);
        float cos = (float) Math.cos(theta);
        float sin = (float) Math.sin(theta);
        // North = (0,-1) and East = (1,0) on screen, rotated by the content's pose.
        drawCardinal(gg, mc, "N", cx + sin * radius, cy - cos * radius);
        drawCardinal(gg, mc, "E", cx + cos * radius, cy + sin * radius);
        drawCardinal(gg, mc, "S", cx - sin * radius, cy + cos * radius);
        drawCardinal(gg, mc, "W", cx - cos * radius, cy - sin * radius);
    }

    private static void drawCardinal(GuiGraphics gg, Minecraft mc, String letter, float x, float y) {
        int ix = Math.round(x);
        int iy = Math.round(y);
        // Translucent badge behind the letter (feathered disc), letter at
        // reduced scale to fit inside it.
        fillCircle(gg, ix, iy, 5f, 0xB0101010);
        gg.pose().pushPose();
        gg.pose().translate(ix, iy, 0);
        gg.pose().scale(0.75f, 0.75f, 1f);
        gg.drawCenteredString(mc.font, letter, 0, -4, 0xFFFFFF);
        gg.pose().popPose();
    }

    // ------------------------------------------------------------------ shapes (round mode)

    /**
     * Solid disc (triangle fan), drawn immediately, with an antialiased
     * rim: the disc is opaque up to radius - FEATHER/2 and fades to
     * transparent at radius + FEATHER/2 (the perceived radius stays
     * {@code radius}) — hard polygon edges are what made the round
     * minimap look jagged.
     */
    private static void fillCircle(GuiGraphics gg, float cx, float cy, float radius, int argb) {
        Matrix4f mat = gg.pose().last().pose();
        prepareShapeState();
        float solid = Math.max(0f, radius - FEATHER / 2f);
        BufferBuilder buf =
                Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buf.addVertex(mat, cx, cy, 0).setColor(argb);
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            buf.addVertex(mat, cx + (float) (Math.cos(a) * solid), cy + (float) (Math.sin(a) * solid), 0)
                    .setColor(argb);
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
        ringBand(mat, cx, cy, solid, argb, radius + FEATHER / 2f, argb & 0x00FFFFFF);
        endShapeState();
    }

    /**
     * Two-tone map border: a 1 px black liner on the map side, then a
     * gray band outside, every edge feathered (alpha or color ramps
     * interpolated per-pixel by the position/color shader — a cheap
     * antialias with no framebuffer or MSAA involved). The black liner
     * starts a quarter px INSIDE the mask radius so the per-pixel jagged
     * depth-mask cut of the content hides under it.
     */
    private static void drawMapBorder(GuiGraphics gg, float cx, float cy, float half) {
        Matrix4f mat = gg.pose().last().pose();
        prepareShapeState();
        int black = 0xFF141414;
        int gray = 0xFFA8A8A8;
        float rIn = half - 0.25f;
        float rMid = half + 0.25f;
        float rOut = half + 1.5f;
        ringBand(mat, cx, cy, rIn - FEATHER, black & 0x00FFFFFF, rIn, black);
        ringBand(mat, cx, cy, rIn, black, rMid - 0.25f, black);
        ringBand(mat, cx, cy, rMid - 0.25f, black, rMid + 0.25f, gray);
        ringBand(mat, cx, cy, rMid + 0.25f, gray, rOut, gray);
        ringBand(mat, cx, cy, rOut, gray, rOut + FEATHER, gray & 0x00FFFFFF);
        endShapeState();
    }

    /**
     * One circular band from (r0, argb0) to (r1, argb1), colors
     * interpolated across the band — the antialias workhorse of the
     * round shapes. Shader and blend state come from the caller.
     */
    private static void ringBand(Matrix4f mat, float cx, float cy, float r0, int argb0, float r1, int argb1) {
        BufferBuilder buf =
                Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            buf.addVertex(mat, cx + c * r1, cy + s * r1, 0).setColor(argb1);
            buf.addVertex(mat, cx + c * r0, cy + s * r0, 0).setColor(argb0);
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    /**
     * Writes the "square minus circle" ring into the depth buffer at a
     * depth CLOSER than the content: the corner pixels then fail the
     * LEQUAL test, which clips the map into a disc without a stencil
     * buffer. Color not written (colorMask off).
     */
    private static void maskCorners(GuiGraphics gg, float cx, float cy, float radius, float halfExt) {
        Matrix4f mat = gg.pose().last().pose();
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionShader);
        BufferBuilder buf =
                Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION);
        float z = 100f; // in front of the content (z = 0)
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            // Point on the bounding square's edge, in the same direction.
            float scale = halfExt / Math.max(Math.abs(c), Math.abs(s));
            buf.addVertex(mat, cx + c * scale, cy + s * scale, z);
            buf.addVertex(mat, cx + c * radius, cy + s * radius, z);
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.colorMask(true, true, true, true);
    }

    /** Restores a "far" depth over the whole area (undoes the mask). */
    private static void resetDepth(GuiGraphics gg, float x0, float y0, float x1, float y1) {
        Matrix4f mat = gg.pose().last().pose();
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionShader);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        float z = -9000f; // far behind all GUI rendering
        buf.addVertex(mat, x0, y0, z);
        buf.addVertex(mat, x0, y1, z);
        buf.addVertex(mat, x1, y1, z);
        buf.addVertex(mat, x1, y0, z);
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.colorMask(true, true, true, true);
    }

    /** MapView handed to MapRenderEvent listeners (view-local coordinates). */
    private record MinimapView(
            int viewWidth,
            int viewHeight,
            double centerX,
            double centerZ,
            float zoomScale,
            ResourceLocation dimension,
            MapLayer currentLayer,
            int caveBand)
            implements MapView {

        @Override
        public boolean isMinimap() {
            return true;
        }
    }
}
