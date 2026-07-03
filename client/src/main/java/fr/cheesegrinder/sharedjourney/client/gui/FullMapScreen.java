package fr.cheesegrinder.sharedjourney.client.gui;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.MinimapRenderer;
import fr.cheesegrinder.sharedjourney.client.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.Payloads;
import fr.cheesegrinder.sharedjourney.common.RegionKey;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Carte plein écran (spec §6.2) :
 * - glisser pour se déplacer (pan), molette pour zoomer
 * - clic droit : créer un waypoint à l'endroit cliqué
 * - clic gauche sur un waypoint : l'éditer (nom, couleur, suppression)
 * - bouton (ou touche virgule) pour changer de couche ; +/- pour la bande CAVE
 * Les régions manquantes/périmées visibles sont demandées au serveur (throttle).
 */
public class FullMapScreen extends Screen {

    private static final long REQUEST_COOLDOWN_MS = 5_000;
    private static final int WAYPOINT_CLICK_PX = 6;

    private double centerX;
    private double centerZ;
    private float zoom = 1.0f; // pixels écran par bloc
    private MapLayer layer;
    private int bandIndex;
    private boolean dragged;

    private Button layerButton;
    private Button bandMinus;
    private Button bandPlus;

    public FullMapScreen() {
        super(Component.literal("SharedJourney"));
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) {
            centerX = player.getX();
            centerZ = player.getZ();
        }
        layer = MinimapRenderer.currentLayer();
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (!allowed.isEmpty() && !allowed.contains(layer)) layer = allowed.get(0);
        bandIndex = Math.max(0, ClientMapCache.caveBands.indexOf(MinimapRenderer.currentCaveBand()));
    }

    @Override
    protected void init() {
        layerButton = addRenderableWidget(Button.builder(layerLabel(), b -> cycleLayer())
                .bounds(width / 2 - 60, height - 26, 120, 20).build());
        bandMinus = addRenderableWidget(Button.builder(Component.literal("-"), b -> changeBand(-1))
                .bounds(width / 2 - 90, height - 26, 20, 20).build());
        bandPlus = addRenderableWidget(Button.builder(Component.literal("+"), b -> changeBand(+1))
                .bounds(width / 2 + 70, height - 26, 20, 20).build());
        updateBandButtons();
    }

    private Component layerLabel() {
        String s = Component.translatable(layer.translationKey()).getString();
        if (layer == MapLayer.CAVE && !ClientMapCache.caveBands.isEmpty()) {
            int band = currentBand();
            s += " y" + (band * 16) + ".." + (band * 16 + 15);
        }
        return Component.literal(s);
    }

    private void cycleLayer() {
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (allowed.isEmpty()) return;
        int idx = allowed.indexOf(layer);
        layer = allowed.get((idx + 1) % allowed.size());
        MinimapRenderer.setLayer(layer);
        layerButton.setMessage(layerLabel());
        updateBandButtons();
    }

    private void changeBand(int delta) {
        List<Integer> bands = ClientMapCache.caveBands;
        if (bands.isEmpty()) return;
        bandIndex = Math.floorMod(bandIndex + delta, bands.size());
        layerButton.setMessage(layerLabel());
    }

    private int currentBand() {
        List<Integer> bands = ClientMapCache.caveBands;
        if (bands.isEmpty()) return 0;
        bandIndex = Math.min(bandIndex, bands.size() - 1);
        return bands.get(Math.max(0, bandIndex));
    }

    private void updateBandButtons() {
        boolean cave = layer == MapLayer.CAVE;
        bandMinus.visible = cave;
        bandPlus.visible = cave;
    }

    // ------------------------------------------------------------------ conversions écran <-> monde

    private double worldX(double mouseX) { return centerX + (mouseX - width / 2.0) / zoom; }

    private double worldZ(double mouseY) { return centerZ + (mouseY - height / 2.0) / zoom; }

    private double screenX(double wx) { return width / 2.0 + (wx - centerX) * zoom; }

    private double screenY(double wz) { return height / 2.0 + (wz - centerZ) * zoom; }

    // ------------------------------------------------------------------ interactions

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        dragged = false;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            dragged = true;
            centerX -= dragX / zoom;
            centerZ -= dragY / zoom;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (super.mouseReleased(mouseX, mouseY, button)) return true;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;

        if (button == 0 && !dragged) {
            // Clic gauche : édition du waypoint le plus proche du curseur
            Waypoint nearest = nearestWaypoint(mouseX, mouseY);
            if (nearest != null) {
                mc.setScreen(new WaypointEditScreen(this, nearest));
                return true;
            }
        } else if (button == 1) {
            // Clic droit : création d'un waypoint à la position cliquée (spec §6.2)
            int wx = (int) Math.floor(worldX(mouseX));
            int wz = (int) Math.floor(worldZ(mouseY));
            int wy = mc.player.blockPosition().getY();
            Waypoint wp = Waypoint.create("X:" + wx + " Z:" + wz,
                    mc.level.dimension().location(), new BlockPos(wx, wy, wz),
                    0xFFFFFF & java.util.concurrent.ThreadLocalRandom.current().nextInt(), "user");
            mc.setScreen(new WaypointEditScreen(this, wp, true));
            return true;
        }
        return false;
    }

    private Waypoint nearestWaypoint(double mouseX, double mouseY) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return null;
        Waypoint best = null;
        double bestDist = WAYPOINT_CLICK_PX * WAYPOINT_CLICK_PX;
        for (Waypoint wp : WaypointStore.forDimension(mc.level.dimension().location())) {
            double dx = screenX(wp.x() + 0.5) - mouseX;
            double dy = screenY(wp.z() + 0.5) - mouseY;
            double d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = wp;
            }
        }
        return best;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        float old = zoom;
        zoom = Math.max(0.125f, Math.min(8.0f, zoom * (scrollY > 0 ? 1.25f : 0.8f)));
        // Zoom centré sur le curseur
        double wx = centerX + (mouseX - width / 2.0) / old;
        double wz = centerZ + (mouseY - height / 2.0) / old;
        centerX = wx - (mouseX - width / 2.0) / zoom;
        centerZ = wz - (mouseY - height / 2.0) / zoom;
        return true;
    }

    // ------------------------------------------------------------------ rendu

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackgroundLayers(gg);
        super.render(gg, mouseX, mouseY, partialTick);

        // Coordonnées sous le curseur
        int wx = (int) Math.floor(worldX(mouseX));
        int wz = (int) Math.floor(worldZ(mouseY));
        gg.drawString(font, wx + ", " + wz, 6, 6, 0xFFFFFF);
        gg.drawString(font, "zoom x" + String.format("%.2f", zoom), 6, 18, 0xAAAAAA);
        gg.drawString(font, Component.translatable("sharedjourney.fullmap.hint"), 6, height - 14, 0x808080);

        // Nom du waypoint survolé
        Waypoint hovered = nearestWaypoint(mouseX, mouseY);
        if (hovered != null) {
            gg.renderTooltip(font, Component.literal(hovered.name()
                    + " (" + hovered.x() + ", " + hovered.y() + ", " + hovered.z() + ")"), mouseX, mouseY);
        }
    }

    private void renderBackgroundLayers(GuiGraphics gg) {
        gg.fill(0, 0, width, height, 0xFF101014);
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        var dim = mc.level.dimension();
        int band = layer == MapLayer.CAVE ? currentBand() : 0;

        double blocksHalfW = (width / 2.0) / zoom;
        double blocksHalfH = (height / 2.0) / zoom;
        int minRx = Math.floorDiv((int) Math.floor(centerX - blocksHalfW), RegionKey.REGION_BLOCKS);
        int maxRx = Math.floorDiv((int) Math.ceil(centerX + blocksHalfW), RegionKey.REGION_BLOCKS);
        int minRz = Math.floorDiv((int) Math.floor(centerZ - blocksHalfH), RegionKey.REGION_BLOCKS);
        int maxRz = Math.floorDiv((int) Math.ceil(centerZ + blocksHalfH), RegionKey.REGION_BLOCKS);

        List<RegionKey> missing = new ArrayList<>();
        List<Long> knownVersions = new ArrayList<>();

        var pose = gg.pose();
        pose.pushPose();
        pose.translate(width / 2.0f, height / 2.0f, 0);
        pose.scale(zoom, zoom, 1f);
        pose.translate((float) -centerX, (float) -centerZ, 0);

        for (int rx = minRx; rx <= maxRx; rx++) {
            for (int rz = minRz; rz <= maxRz; rz++) {
                RegionKey key = new RegionKey(dim, layer, layer == MapLayer.CAVE ? band : 0, rx, rz);
                ClientMapCache.Region region = ClientMapCache.getOrLoad(key);
                if (region != null) {
                    gg.blit(region.texture(),
                            rx * RegionKey.REGION_BLOCKS, rz * RegionKey.REGION_BLOCKS,
                            0f, 0f, RegionKey.REGION_BLOCKS, RegionKey.REGION_BLOCKS,
                            RegionKey.REGION_BLOCKS, RegionKey.REGION_BLOCKS);
                }
                // Demande si absente ou potentiellement périmée (throttlée)
                long now = System.currentTimeMillis();
                Long last = ClientMapCache.LAST_REQUESTED.get(key);
                if ((last == null || now - last > REQUEST_COOLDOWN_MS) && missing.size() < 64) {
                    missing.add(key);
                    knownVersions.add(ClientMapCache.versionOf(key));
                    ClientMapCache.LAST_REQUESTED.put(key, now);
                }
            }
        }

        // Marqueur joueur
        int px = (int) Math.floor(mc.player.getX());
        int pz = (int) Math.floor(mc.player.getZ());
        gg.fill(px - 2, pz - 2, px + 2, pz + 2, 0xFF000000);
        gg.fill(px - 1, pz - 1, px + 1, pz + 1, 0xFFFF4040);

        pose.popPose();

        // Waypoints par-dessus, en coordonnées écran (taille constante quel que soit le zoom)
        for (Waypoint wp : WaypointStore.forDimension(dim.location())) {
            if (!wp.visible()) continue;
            int sx = (int) Math.round(screenX(wp.x() + 0.5));
            int sy = (int) Math.round(screenY(wp.z() + 0.5));
            if (sx < -8 || sx > width + 8 || sy < -8 || sy > height + 8) continue;
            gg.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFF000000);
            gg.fill(sx - 2, sy - 2, sx + 3, sy + 3, 0xFF000000 | wp.colorRgb());
            if (zoom >= 0.5f) {
                gg.drawCenteredString(font, wp.name(), sx, sy - 13, 0xFFFFFF);
            }
        }

        if (!missing.isEmpty()) {
            PacketDistributor.sendToServer(new Payloads.RegionRequestPayload(missing, knownVersions));
        }
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Pas de flou vanilla : le fond est peint par renderBackgroundLayers().
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
