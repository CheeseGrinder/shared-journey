package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.event.ClientInputEvents;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Locale;

/**
 * Minimap HUD (spec §6.1) : tuiles serveur, rotation dynamique optionnelle
 * (rendu via matrices de pose), zoom clavier, forme ronde ou carrée, radar
 * d'entités filtrable dont le rayon est plafonné par le serveur (anti-triche),
 * et waypoints.
 */
public final class MinimapRenderer {

    private MinimapRenderer() {}

    private static final float ZOOM_MIN = 0.25f;
    private static final float ZOOM_MAX = 4.0f;
    private static final int CIRCLE_SEGMENTS = 64;
    /** Gris sombre (style Discord) visible sous les chunks pas encore reçus. */
    public static final int BACKGROUND = 0xFF36393F;

    private static MapLayer currentLayer = null; // null = pas encore initialisée depuis la config
    private static Boolean autoMode = null;      // null = pas encore initialisé depuis la config
    private static int caveBandIndex = 0;
    private static float zoom = 1.0f; // pixels écran par bloc

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

    /** Le mode auto suit jour/nuit et le passage sous terre. */
    public static boolean autoMode() {
        if (autoMode == null) {
            autoMode = ClientConfig.AUTO_LAYER.get();
        }
        return autoMode;
    }

    /**
     * Couche réellement affichée. En mode auto : CAVE si le joueur est sous
     * terre, sinon NIGHT la nuit, sinon DAY — parmi les couches autorisées
     * par le serveur pour la dimension courante.
     */
    public static MapLayer displayedLayer() {
        Minecraft mc = Minecraft.getInstance();
        if (!autoMode() || mc.level == null || mc.player == null) {
            return currentLayer();
        }

        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (allowed.contains(MapLayer.CAVE) && isUnderground(mc.level, mc.player)) {
            return MapLayer.CAVE;
        }

        if (allowed.contains(MapLayer.NIGHT) && isNightTime(mc.level)) {
            return MapLayer.NIGHT;
        }

        if (allowed.contains(MapLayer.DAY) || allowed.isEmpty()) {
            return MapLayer.DAY;
        }

        return allowed.get(0);
    }

    /**
     * Nuit selon l'heure du monde (13000-23000, bornes de /time set night/day).
     * Level.isNight() est inutilisable ici : il dépend de skyDarken, qui n'est
     * mis à jour que côté serveur — côté client il vaut toujours 0.
     */
    private static boolean isNightTime(Level level) {
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return time >= 13000L && time < 23000L;
    }

    /** Même règle que le CaveTracker serveur : la surface est au-dessus des yeux. */
    private static boolean isUnderground(Level level, Player player) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                player.blockPosition().getX(), player.blockPosition().getZ());
        return surface > player.getEyeY();
    }

    /**
     * Cycle de couche : Auto -> couche 1 -> ... -> couche N -> Auto.
     * Choisir une couche à la main suspend le mode auto jusqu'au retour à Auto.
     */
    public static void cycleLayer() {
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (allowed.isEmpty()) {
            return;
        }

        if (autoMode()) {
            autoMode = false;
            currentLayer = allowed.get(0);
            return;
        }

        int idx = allowed.indexOf(currentLayer());
        if (idx < 0 || idx == allowed.size() - 1) {
            autoMode = true;
            return;
        }

        currentLayer = allowed.get(idx + 1);
    }

    public static void setLayer(MapLayer layer) {
        autoMode = false;
        currentLayer = layer;
    }

    public static void zoomIn() {
        zoom = Math.min(ZOOM_MAX, zoom * 1.25f);
    }

    public static void zoomOut() {
        zoom = Math.max(ZOOM_MIN, zoom / 1.25f);
    }

    public static int currentCaveBand() {
        List<Integer> bands = ClientMapCache.caveBands;
        if (bands.isEmpty()) {
            return 0;
        }

        // Suit automatiquement la bande où se trouve le joueur si elle est disponible.
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

    public static void render(GuiGraphics gg) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui || !ClientInputEvents.minimapVisible) {
            return;
        }

        if (mc.getDebugOverlay().showDebugScreen()) {
            return;
        }

        if (!ClientConfig.MINIMAP_ENABLED.get()) {
            return;
        }

        MapLayer layer = displayedLayer();
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (!allowed.isEmpty() && !allowed.contains(layer)) {
            layer = allowed.get(0);
        }

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
        boolean circle = ClientConfig.MINIMAP_SHAPE.get() == ClientConfig.Shape.CIRCLE;
        // En mode rotation, la carte tourne autour du joueur pour que "devant" soit en haut.
        float yaw = player.getYRot();

        int half = size / 2;
        int cx = x + half, cy = y + half;

        // Fond gris (zones pas encore reçues) ; la bordure ronde est dessinée
        // en dernier, par-dessus le contenu.
        if (circle) {
            gg.flush();
            fillCircle(gg, cx, cy, half + 1, BACKGROUND);
            // Masque de profondeur : les coins du carré (hors cercle) deviennent
            // "devant" le contenu, qui y échoue donc au test de profondeur.
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
            // Rotation dynamique (spec §6.1) autour du centre de la minimap.
            gg.pose().translate(cx, cy, 0);
            gg.pose().mulPose(Axis.ZP.rotationDegrees(-yaw - 180f));
            gg.pose().translate(-cx, -cy, 0);
        }
        if (zoom != 1.0f) {
            // Zoom clavier : échelle autour du centre (1 px écran = zoom blocs).
            gg.pose().translate(cx, cy, 0);
            gg.pose().scale(zoom, zoom, 1f);
            gg.pose().translate(-cx, -cy, 0);
        }

        // Fenêtre de blocs à couvrir. Un cercle est invariant par rotation ;
        // pour un carré en rotation, la diagonale dépasse (half * sqrt(2)).
        double factor = (rotate && !circle) ? 1.4143 : 1.0;
        int reach = (int) Math.ceil(half * factor / zoom) + 1;
        int minRx = Math.floorDiv((int) px - reach, RegionKey.REGION_BLOCKS);
        int maxRx = Math.floorDiv((int) px + reach, RegionKey.REGION_BLOCKS);
        int minRz = Math.floorDiv((int) pz - reach, RegionKey.REGION_BLOCKS);
        int maxRz = Math.floorDiv((int) pz + reach, RegionKey.REGION_BLOCKS);

        for (int rx = minRx; rx <= maxRx; rx++) {
            for (int rz = minRz; rz <= maxRz; rz++) {
                RegionKey key = new RegionKey(dim, layer, layer == MapLayer.CAVE ? band : 0, rx, rz);
                ClientMapCache.Region region = ClientMapCache.getOrLoad(key);
                if (region == null) {
                    continue;
                }

                int drawX = cx + (int) Math.floor(rx * (double) RegionKey.REGION_BLOCKS - px);
                int drawY = cy + (int) Math.floor(rz * (double) RegionKey.REGION_BLOCKS - pz);
                gg.blit(region.texture(), drawX, drawY, 0f, 0f,
                        RegionKey.REGION_BLOCKS, RegionKey.REGION_BLOCKS,
                        RegionKey.REGION_BLOCKS, RegionKey.REGION_BLOCKS);
            }
        }

        // ---- Waypoints de la dimension (points colorés, sous le radar)
        for (Waypoint wp : WaypointStore.forDimension(dim.location())) {
            if (!wp.visible()) {
                continue;
            }

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
                if (e instanceof Player && ClientConfig.RADAR_PLAYERS.get()) {
                    color = 0xFFFFFFFF;
                }
                else if (e instanceof Enemy && ClientConfig.RADAR_HOSTILE.get()) {
                    color = 0xFFFF4040;
                }
                else if (e instanceof Animal && ClientConfig.RADAR_PASSIVE.get()) {
                    color = 0xFF40FF40;
                }
                else {
                    continue;
                }

                double ex = e.getX() - px, ez = e.getZ() - pz;
                if (ex * ex + ez * ez > (double) radius * radius) {
                    continue;
                }

                int dx = cx + (int) Math.floor(ex);
                int dy = cy + (int) Math.floor(ez);
                gg.fill(dx - 1, dy - 1, dx + 2, dy + 2, 0xFF000000);
                gg.fill(dx, dy, dx + 1, dy + 1, color);
            }
        }

        gg.pose().popPose();
        gg.disableScissor();

        if (circle) {
            // Vide le contenu batché (clippé par le masque) puis restaure la
            // profondeur des coins pour ne pas gêner les couches suivantes.
            gg.flush();
            resetDepth(gg, x - 2, y - 2, x + size + 2, y + size + 2);
            drawRing(gg, cx, cy, half, half + 1.5f, 0xFF202020);
        }

        // Marqueur joueur au centre. En rotation, le joueur "regarde vers le haut" :
        // triangle fixe ; sinon flèche orientée selon le yaw.
        gg.pose().pushPose();
        gg.pose().translate(cx, cy, 0);
        if (!rotate) {
            gg.pose().mulPose(Axis.ZP.rotationDegrees(yaw + 180f));
        }

        gg.fill(-1, -4, 1, 1, 0xFF000000);
        gg.fill(-3, 0, 3, 2, 0xFF000000);
        gg.fill(0, -3, 1, 0, 0xFFFFFFFF);
        gg.fill(-2, 0, 2, 1, 0xFFFFFFFF);
        gg.pose().popPose();

        // Libellés : couche (+bande) et coordonnées, préfixée "Auto" le cas échéant
        Component label = Component.translatable(layer.translationKey());
        if (autoMode()) {
            label = Component.translatable("sharedjourney.layer.auto", label);
        }
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

    // ------------------------------------------------------------------ formes (mode rond)

    /** Disque plein (triangle fan), dessiné immédiatement. */
    private static void fillCircle(GuiGraphics gg, float cx, float cy, float radius, int argb) {
        Matrix4f mat = gg.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buf.addVertex(mat, cx, cy, 0).setColor(argb);
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            buf.addVertex(mat, cx + (float) (Math.cos(a) * radius),
                    cy + (float) (Math.sin(a) * radius), 0).setColor(argb);
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    /** Anneau (bordure du cercle), dessiné immédiatement. */
    private static void drawRing(GuiGraphics gg, float cx, float cy, float rIn, float rOut, int argb) {
        Matrix4f mat = gg.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            buf.addVertex(mat, cx + c * rOut, cy + s * rOut, 0).setColor(argb);
            buf.addVertex(mat, cx + c * rIn, cy + s * rIn, 0).setColor(argb);
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    /**
     * Écrit dans le tampon de profondeur l'anneau "carré moins cercle" à une
     * profondeur PLUS PROCHE que le contenu : les pixels des coins échouent
     * ensuite au test LEQUAL, ce qui découpe la carte en disque sans stencil.
     * Couleur non écrite (colorMask off).
     */
    private static void maskCorners(GuiGraphics gg, float cx, float cy, float radius, float halfExt) {
        Matrix4f mat = gg.pose().last().pose();
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionShader);
        BufferBuilder buf = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION);
        float z = 100f; // devant le contenu (z = 0)
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            // Point sur le bord du carré englobant, dans la même direction.
            float scale = halfExt / Math.max(Math.abs(c), Math.abs(s));
            buf.addVertex(mat, cx + c * scale, cy + s * scale, z);
            buf.addVertex(mat, cx + c * radius, cy + s * radius, z);
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.colorMask(true, true, true, true);
    }

    /** Restaure une profondeur "lointaine" sur toute la zone (annule le masque). */
    private static void resetDepth(GuiGraphics gg, float x0, float y0, float x1, float y1) {
        Matrix4f mat = gg.pose().last().pose();
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionShader);
        BufferBuilder buf = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        float z = -9000f; // très loin derrière tout le rendu GUI
        buf.addVertex(mat, x0, y0, z);
        buf.addVertex(mat, x0, y1, z);
        buf.addVertex(mat, x1, y1, z);
        buf.addVertex(mat, x1, y0, z);
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.colorMask(true, true, true, true);
    }
}
