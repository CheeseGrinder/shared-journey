package fr.cheesegrinder.sharedjourney.client;

import com.mojang.math.Axis;
import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.common.RegionKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Locale;

/**
 * Minimap HUD (spec §6.1) : tuiles serveur, rotation dynamique optionnelle
 * (rendu via matrices de pose), radar d'entités filtrable dont le rayon est
 * plafonné par le serveur (anti-triche), et waypoints.
 */
public final class MinimapRenderer {

    private MinimapRenderer() {}

    private static MapLayer currentLayer = null; // null = pas encore initialisée depuis la config
    private static int caveBandIndex = 0;

    public static MapLayer currentLayer() {
        if (currentLayer == null) {
            try {
                currentLayer = MapLayer.valueOf(ClientConfig.DEFAULT_LAYER.get().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                currentLayer = MapLayer.DAY;
            }
        }
        return currentLayer;
    }

    /** Passe à la couche suivante parmi celles autorisées par le serveur pour la dimension. */
    public static void cycleLayer() {
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (allowed.isEmpty()) return;
        MapLayer cur = currentLayer();
        int idx = allowed.indexOf(cur);
        currentLayer = allowed.get((idx + 1) % allowed.size());
    }

    public static void setLayer(MapLayer layer) { currentLayer = layer; }

    public static int currentCaveBand() {
        List<Integer> bands = ClientMapCache.caveBands;
        if (bands.isEmpty()) return 0;
        // Suit automatiquement la bande où se trouve le joueur si elle est disponible.
        Player p = Minecraft.getInstance().player;
        if (p != null) {
            int band = Math.floorDiv(p.blockPosition().getY(), 16);
            int i = bands.indexOf(band);
            if (i >= 0) caveBandIndex = i;
        }
        caveBandIndex = Math.min(caveBandIndex, bands.size() - 1);
        return bands.get(Math.max(0, caveBandIndex));
    }

    // ------------------------------------------------------------------

    public static void render(GuiGraphics gg) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui || !ClientEvents.minimapVisible) return;
        if (mc.getDebugOverlay().showDebugScreen()) return;
        if (!ClientConfig.MINIMAP_ENABLED.get()) return;

        MapLayer layer = currentLayer();
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (!allowed.isEmpty() && !allowed.contains(layer)) layer = allowed.get(0);

        int size = ClientConfig.MINIMAP_SIZE.get();
        int margin = 6;
        int sw = gg.guiWidth();
        int sh = gg.guiHeight();
        int x = switch (ClientConfig.MINIMAP_CORNER.get()) {
            case TOP_LEFT, BOTTOM_LEFT -> margin;
            case TOP_RIGHT, BOTTOM_RIGHT -> sw - size - margin;
        };
        int y = switch (ClientConfig.MINIMAP_CORNER.get()) {
            case TOP_LEFT, TOP_RIGHT -> margin;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> sh - size - margin;
        };

        boolean rotate = ClientConfig.MINIMAP_ROTATE.get();
        // En mode rotation, la carte tourne autour du joueur pour que "devant" soit en haut.
        float yaw = player.getYRot();

        // Fond + bordure
        gg.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF202020);
        gg.fill(x, y, x + size, y + size, 0xFF000000);

        double px = player.getX();
        double pz = player.getZ();
        int band = layer == MapLayer.CAVE ? currentCaveBand() : 0;
        var dim = player.level().dimension();
        int half = size / 2;
        int cx = x + half, cy = y + half;

        gg.enableScissor(x, y, x + size, y + size);

        gg.pose().pushPose();
        if (rotate) {
            // Rotation dynamique (spec §6.1) autour du centre de la minimap.
            gg.pose().translate(cx, cy, 0);
            gg.pose().mulPose(Axis.ZP.rotationDegrees(-yaw - 180f));
            gg.pose().translate(-cx, -cy, 0);
        }

        // 1 pixel écran = 1 bloc. En rotation, la diagonale dépasse : on élargit
        // la fenêtre de régions à couvrir (half * sqrt(2)).
        int reach = rotate ? (int) Math.ceil(half * 1.4143) : half;
        int minRx = Math.floorDiv((int) px - reach, RegionKey.REGION_BLOCKS);
        int maxRx = Math.floorDiv((int) px + reach, RegionKey.REGION_BLOCKS);
        int minRz = Math.floorDiv((int) pz - reach, RegionKey.REGION_BLOCKS);
        int maxRz = Math.floorDiv((int) pz + reach, RegionKey.REGION_BLOCKS);

        for (int rx = minRx; rx <= maxRx; rx++) {
            for (int rz = minRz; rz <= maxRz; rz++) {
                RegionKey key = new RegionKey(dim, layer, layer == MapLayer.CAVE ? band : 0, rx, rz);
                ClientMapCache.Region region = ClientMapCache.getOrLoad(key);
                if (region == null) continue;
                int drawX = cx + (int) Math.floor(rx * (double) RegionKey.REGION_BLOCKS - px);
                int drawY = cy + (int) Math.floor(rz * (double) RegionKey.REGION_BLOCKS - pz);
                gg.blit(region.texture(), drawX, drawY, 0f, 0f,
                        RegionKey.REGION_BLOCKS, RegionKey.REGION_BLOCKS,
                        RegionKey.REGION_BLOCKS, RegionKey.REGION_BLOCKS);
            }
        }

        // ---- Waypoints de la dimension (points colorés, sous le radar)
        for (Waypoint wp : WaypointStore.forDimension(dim.location())) {
            if (!wp.visible()) continue;
            int dx = cx + (int) Math.floor(wp.x() - px);
            int dy = cy + (int) Math.floor(wp.z() - pz);
            gg.fill(dx - 2, dy - 2, dx + 3, dy + 3, 0xFF000000);
            gg.fill(dx - 1, dy - 1, dx + 2, dy + 2, 0xFF000000 | wp.colorRgb());
        }

        // ---- Radar d'entités (spec §6.1) : rayon plafonné par le serveur
        if (ClientConfig.RADAR_ENABLED.get() && ClientMapCache.radarMaxRadius > 0) {
            int radius = Math.min(ClientConfig.RADAR_RADIUS.get(), ClientMapCache.radarMaxRadius);
            AABB box = player.getBoundingBox().inflate(radius, 32, radius);
            for (Entity e : player.level().getEntities(player, box)) {
                int color;
                if (e instanceof Player && ClientConfig.RADAR_PLAYERS.get()) color = 0xFFFFFFFF;
                else if (e instanceof Enemy && ClientConfig.RADAR_HOSTILE.get()) color = 0xFFFF4040;
                else if (e instanceof Animal && ClientConfig.RADAR_PASSIVE.get()) color = 0xFF40FF40;
                else continue;
                double ex = e.getX() - px, ez = e.getZ() - pz;
                if (ex * ex + ez * ez > (double) radius * radius) continue;
                int dx = cx + (int) Math.floor(ex);
                int dy = cy + (int) Math.floor(ez);
                gg.fill(dx - 1, dy - 1, dx + 2, dy + 2, 0xFF000000);
                gg.fill(dx, dy, dx + 1, dy + 1, color);
            }
        }

        gg.pose().popPose();
        gg.disableScissor();

        // Marqueur joueur au centre. En rotation, le joueur "regarde vers le haut" :
        // triangle fixe ; sinon flèche orientée selon le yaw.
        gg.pose().pushPose();
        gg.pose().translate(cx, cy, 0);
        if (!rotate) gg.pose().mulPose(Axis.ZP.rotationDegrees(yaw + 180f));
        gg.fill(-1, -4, 1, 1, 0xFF000000);
        gg.fill(-3, 0, 3, 2, 0xFF000000);
        gg.fill(0, -3, 1, 0, 0xFFFFFFFF);
        gg.fill(-2, 0, 2, 1, 0xFFFFFFFF);
        gg.pose().popPose();

        // Libellés : couche (+bande) et coordonnées
        Component label = Component.translatable(layer.translationKey());
        String text = label.getString() + (layer == MapLayer.CAVE ? " y" + (band * 16) + ".." + (band * 16 + 15) : "");
        boolean topAnchored = ClientConfig.MINIMAP_CORNER.get() == ClientConfig.Corner.TOP_LEFT
                || ClientConfig.MINIMAP_CORNER.get() == ClientConfig.Corner.TOP_RIGHT;
        int textY = topAnchored ? y + size + 3 : y - 24;
        gg.drawCenteredString(mc.font, text, x + half, textY, 0xFFFFFF);
        if (ClientConfig.SHOW_COORDS.get()) {
            String coords = player.blockPosition().getX() + ", "
                    + player.blockPosition().getY() + ", " + player.blockPosition().getZ();
            gg.drawCenteredString(mc.font, coords, x + half, textY + 11, 0xAAAAAA);
        }
    }
}
