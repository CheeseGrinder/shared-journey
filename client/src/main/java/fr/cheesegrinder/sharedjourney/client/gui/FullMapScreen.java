package fr.cheesegrinder.sharedjourney.client.gui;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.MinimapRenderer;
import fr.cheesegrinder.sharedjourney.client.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.Payloads;
import fr.cheesegrinder.sharedjourney.common.RegionKey;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
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
    /** Boutons du menu contextuel (clic droit), retirés au prochain clic. */
    private final List<Button> contextButtons = new ArrayList<>();

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

    /** Ouvre la carte centrée sur une position donnée (/sj goto, clic chat). */
    public FullMapScreen(double centerX, double centerZ) {
        this();
        this.centerX = centerX;
        this.centerZ = centerZ;
    }

    @Override
    protected void init() {
        layerButton = addRenderableWidget(Button.builder(layerLabel(), b -> cycleLayer())
                .bounds(width / 2 - 60, height - 26, 120, 20).build());
        bandMinus = addRenderableWidget(Button.builder(Component.literal("-"), b -> changeBand(-1))
                .bounds(width / 2 - 90, height - 26, 20, 20).build());
        bandPlus = addRenderableWidget(Button.builder(Component.literal("+"), b -> changeBand(+1))
                .bounds(width / 2 + 70, height - 26, 20, 20).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("sharedjourney.fullmap.center"), b -> centerOnPlayer())
                .bounds(width - 86, height - 26, 80, 20).build());
        contextButtons.clear(); // init() recrée tous les widgets (resize)
        updateBandButtons();
    }

    private void centerOnPlayer() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) {
            centerX = player.getX();
            centerZ = player.getZ();
        }
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
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        closeContextMenu(); // clic hors du menu : on le referme
        return false;
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
        } else if (button == 1 && !dragged) {
            // Clic droit : menu contextuel (TP, waypoint, position dans le chat)
            openContextMenu(mouseX, mouseY);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ menu contextuel

    private void openContextMenu(double mouseX, double mouseY) {
        closeContextMenu();
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        int wx = (int) Math.floor(worldX(mouseX));
        int wz = (int) Math.floor(worldZ(mouseY));
        // Le TP passe par /tp : réservé aux joueurs op (niveau 2, connu du client).
        boolean canTeleport = mc.player.hasPermissions(2);
        int rows = canTeleport ? 3 : 2;
        int w = 140, h = 20;
        int bx = (int) Math.min(mouseX, width - w - 4);
        int by = (int) Math.min(mouseY, height - rows * (h + 1) - 4);
        if (canTeleport) {
            addContextButton(bx, by, w, h, "sharedjourney.context.teleport", () -> teleportTo(wx, wz));
            by += h + 1;
        }
        addContextButton(bx, by, w, h, "sharedjourney.context.waypoint", () -> createWaypointAt(wx, wz));
        addContextButton(bx, by + h + 1, w, h, "sharedjourney.context.chat", () -> logCoords(wx, wz));
    }

    private void addContextButton(int x, int y, int w, int h, String key, Runnable action) {
        Button b = Button.builder(Component.translatable(key), btn -> {
            closeContextMenu();
            action.run();
        }).bounds(x, y, w, h).build();
        contextButtons.add(b);
        addRenderableWidget(b);
    }

    private void closeContextMenu() {
        contextButtons.forEach(this::removeWidget);
        contextButtons.clear();
    }

    /** Y de surface si le chunk est chargé côté client, sinon -1. */
    private int surfaceYAt(int wx, int wz) {
        var mc = Minecraft.getInstance();
        LevelChunk chunk = mc.level == null ? null
                : mc.level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        if (chunk == null) return -1;
        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15) + 1;
    }

    private void teleportTo(int wx, int wz) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int y = surfaceYAt(wx, wz);
        String yArg = y >= 0 ? String.valueOf(y) : "~";
        mc.player.connection.sendUnsignedCommand(
                "tp @s " + (wx + 0.5) + " " + yArg + " " + (wz + 0.5));
        onClose();
    }

    private void createWaypointAt(int wx, int wz) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        int y = surfaceYAt(wx, wz);
        int wy = y >= 0 ? y : mc.player.blockPosition().getY();
        Waypoint wp = Waypoint.create("X:" + wx + " Z:" + wz,
                mc.level.dimension().location(), new BlockPos(wx, wy, wz),
                0xFFFFFF & java.util.concurrent.ThreadLocalRandom.current().nextInt(), "user");
        mc.setScreen(new WaypointEditScreen(this, wp, true));
    }

    /** Écrit la position dans le chat (local) ; cliquer dessus rouvre la carte ici. */
    private void logCoords(int wx, int wz) {
        var mc = Minecraft.getInstance();
        Component msg = Component.translatable("sharedjourney.coords.chat", wx, wz)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/sj goto " + wx + " " + wz))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("sharedjourney.coords.open"))));
        mc.gui.getChat().addMessage(msg);
        onClose(); // referme la carte pour laisser voir le chat
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

        // Infos sous le curseur : coordonnées, et si le chunk est chargé côté
        // client, biome + bloc de surface (comme JourneyMap).
        int wx = (int) Math.floor(worldX(mouseX));
        int wz = (int) Math.floor(worldZ(mouseY));
        var mc = net.minecraft.client.Minecraft.getInstance();
        int infoY = 6;
        LevelChunk hoverChunk = mc.level == null ? null
                : mc.level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        if (hoverChunk != null) {
            int top = hoverChunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
            BlockPos hoverPos = new BlockPos(wx, top, wz);
            gg.drawString(font, wx + ", " + top + ", " + wz, 6, infoY, 0xFFFFFF);
            infoY += 12;
            var biomeKey = mc.level.getBiome(hoverPos).unwrapKey();
            if (biomeKey.isPresent()) {
                var loc = biomeKey.get().location();
                gg.drawString(font, Component.translatable(
                        "biome." + loc.getNamespace() + "." + loc.getPath()), 6, infoY, 0xC0C0FF);
                infoY += 12;
            }
            BlockState topState = hoverChunk.getBlockState(hoverPos);
            if (!topState.isAir()) {
                gg.drawString(font, topState.getBlock().getName(), 6, infoY, 0xA0E0A0);
                infoY += 12;
            }
        } else {
            gg.drawString(font, wx + ", " + wz, 6, infoY, 0xFFFFFF);
            infoY += 12;
        }
        gg.drawString(font, "zoom x" + String.format("%.2f", zoom), 6, infoY, 0xAAAAAA);
        gg.drawString(font, Component.translatable("sharedjourney.fullmap.hint"), 6, height - 14, 0x808080);

        // Nom du waypoint survolé
        Waypoint hovered = nearestWaypoint(mouseX, mouseY);
        if (hovered != null) {
            gg.renderTooltip(font, Component.literal(hovered.name()
                    + " (" + hovered.x() + ", " + hovered.y() + ", " + hovered.z() + ")"), mouseX, mouseY);
        }
    }

    private void renderBackgroundLayers(GuiGraphics gg) {
        gg.fill(0, 0, width, height, MinimapRenderer.BACKGROUND);
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

        // Radar d'entités : mêmes filtres et plafond serveur que la minimap.
        if (ClientConfig.RADAR_ENABLED.get() && ClientMapCache.radarMaxRadius > 0) {
            int radius = Math.min(ClientConfig.RADAR_RADIUS.get(), ClientMapCache.radarMaxRadius);
            AABB box = mc.player.getBoundingBox().inflate(radius, 32, radius);
            for (Entity e : mc.level.getEntities(mc.player, box)) {
                int color;
                if (e instanceof Player && ClientConfig.RADAR_PLAYERS.get()) color = 0xFFFFFFFF;
                else if (e instanceof Enemy && ClientConfig.RADAR_HOSTILE.get()) color = 0xFFFF4040;
                else if (e instanceof Animal && ClientConfig.RADAR_PASSIVE.get()) color = 0xFF40FF40;
                else continue;
                double ex = e.getX() - mc.player.getX(), ez = e.getZ() - mc.player.getZ();
                if (ex * ex + ez * ez > (double) radius * radius) continue;
                int sx = (int) Math.round(screenX(e.getX()));
                int sy = (int) Math.round(screenY(e.getZ()));
                if (sx < -4 || sx > width + 4 || sy < -4 || sy > height + 4) continue;
                gg.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF000000);
                gg.fill(sx - 1, sy - 1, sx + 1, sy + 1, color);
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
