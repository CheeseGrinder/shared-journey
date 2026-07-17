package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Op-only rendering diagnostics block, drawn on the minimap HUD and the
 * fullscreen map when enabled. Toggled from {@code DebugScreen} (opened
 * from the settings screen, permission level 2+); the flag is runtime
 * only (reset each session, never persisted) — a non-op can never turn it
 * on. The key line for the Create rail flicker is {@code z×gui}: it tells
 * whether one block maps to a whole number of physical pixels
 * ({@code [aligned]}) or lands on a fractional ratio ({@code [fractional]}),
 * which is what NEAREST sampling flickers on. English text: op tooling,
 * project convention (like {@code /sj stats}).
 */
public final class DebugOverlay {

    /** Runtime toggle, flipped by DebugScreen. Not persisted. */
    public static boolean enabled = false;

    private DebugOverlay() {}

    /** Right margin (px) of the block from the screen edge. */
    private static final int MARGIN = 6;

    /**
     * Z the block is raised to so it sits above every map/UI element,
     * including the fullscreen context menu (z=500) and legend (z=300).
     */
    private static final int OVERLAY_Z = 600;

    /**
     * Draws the diagnostics block pinned to the RIGHT edge, vertically
     * centered — clear of the fullscreen map's left action bar — and
     * raised in z above everything else (context menu, legend, chrome).
     *
     * @param surface "minimap" or "fullscreen" (labels the source)
     * @param zoom    screen pixels per block of that surface
     * @param band    CAVE vertical band (shown only on the CAVE layer)
     */
    public static void render(
            GuiGraphics gg,
            int screenW,
            int screenH,
            String surface,
            float zoom,
            MapLayer layer,
            int band,
            ResourceLocation dim) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        double guiScale = mc.getWindow().getGuiScale();
        double snap = zoom * guiScale;
        boolean aligned = Math.abs(snap - Math.round(snap)) < 1.0e-3;

        List<String> lines = new ArrayList<>();
        lines.add("SJ debug — " + surface);
        lines.add(String.format(Locale.ROOT, "zoom     %.4f px/blk", zoom));
        lines.add(String.format(Locale.ROOT, "guiScale %.0f", guiScale));
        lines.add(String.format(Locale.ROOT, "z×gui   %.3f  %s", snap, aligned ? "[aligned]" : "[fractional]"));
        String layerLine = "layer    " + layer.name();
        if (layer == MapLayer.CAVE) {
            layerLine += "  band " + band;
        }
        lines.add(layerLine);
        lines.add("dim      " + dim.getPath());
        if (mc.player != null) {
            BlockPos p = mc.player.blockPosition();
            int rx = Math.floorDiv(p.getX(), RegionKey.REGION_BLOCKS);
            int rz = Math.floorDiv(p.getZ(), RegionKey.REGION_BLOCKS);
            lines.add(String.format(Locale.ROOT, "player   %d, %d, %d", p.getX(), p.getY(), p.getZ()));
            lines.add("region   " + rx + ", " + rz);
        }
        lines.add("fps      " + mc.getFps());

        int pad = 3;
        int lineH = font.lineHeight + 1;
        int boxW = 0;
        for (String s : lines) {
            boxW = Math.max(boxW, font.width(s));
        }
        int boxH = lines.size() * lineH;
        int left = screenW - boxW - MARGIN;
        int top = (screenH - boxH) / 2;

        var pose = gg.pose();
        pose.pushPose();
        pose.translate(0, 0, OVERLAY_Z);
        gg.fill(left - pad, top - pad, left + boxW + pad, top + boxH + pad, 0xC0101010);
        int y = top;
        for (String s : lines) {
            gg.drawString(font, s, left, y, 0xFFFFFF);
            y += lineH;
        }
        pose.popPose();
    }
}
