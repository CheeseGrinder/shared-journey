package fr.cheesegrinder.sharedjourney.client.gui;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.event.ClientSetupEvents;
import fr.cheesegrinder.sharedjourney.client.render.EntityDots;
import fr.cheesegrinder.sharedjourney.client.render.MinimapRenderer;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.PacketDistributor;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

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

    /**
     * Échelle du zoom : puissances de 2 affichées comme JourneyMap
     * (libellé = zoom * 2048, de 64 à 16384). La borne basse est limitée pour
     * borner le nombre de régions parcourues par frame.
     */
    private static final float ZOOM_MIN = 64f / 2048f;

    private static final float ZOOM_MAX = 16384f / 2048f;

    /** Légende des touches (Show Keys), conservée le temps de la session. */
    private static boolean showKeys = false;

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

    // Barre d'actions du haut : bouton par couche + toggles avec leur état.
    private final Map<MapLayer, IconButton> layerIcons = new EnumMap<>(MapLayer.class);
    private final Map<IconButton, Supplier<Boolean>> toggleIcons = new LinkedHashMap<>();

    // Recherche de position (barre de gauche).
    private EditBox locateX;
    private EditBox locateZ;
    private Button locateGo;
    private boolean locateOpen;

    // Throttle des requêtes d'infos de survol (colonne + horodatage).
    private long lastInfoRequestKey = Long.MIN_VALUE;
    private long lastInfoRequestAt;

    public FullMapScreen() {
        super(Component.literal("SharedJourney"));
        var player = Minecraft.getInstance().player;
        if (player != null) {
            centerX = player.getX();
            centerZ = player.getZ();
        }
        layer = MinimapRenderer.displayedLayer();
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (!allowed.isEmpty() && !allowed.contains(layer)) {
            layer = allowed.get(0);
        }

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
                .bounds(width / 2 - 60, height - 26, 120, 20)
                .build());
        bandMinus = addRenderableWidget(Button.builder(Component.literal("-"), b -> changeBand(-1))
                .bounds(width / 2 - 90, height - 26, 20, 20)
                .build());
        bandPlus = addRenderableWidget(Button.builder(Component.literal("+"), b -> changeBand(+1))
                .bounds(width / 2 + 70, height - 26, 20, 20)
                .build());
        addRenderableWidget(
                Button.builder(Component.translatable("sharedjourney.fullmap.center"), b -> centerOnPlayer())
                        .bounds(width - 86, height - 26, 80, 20)
                        .build());
        contextButtons.clear(); // init() recrée tous les widgets (resize)
        buildTopToolbar();
        buildLeftToolbar();
        updateBandButtons();
        refreshToolbar();
    }

    // ------------------------------------------------------------------ barres d'actions

    /** Barre du haut : couches, toggles d'affichage, et Close à droite. */
    private void buildTopToolbar() {
        layerIcons.clear();
        toggleIcons.clear();
        int size = 20;
        int step = size + 2;
        int total = 5 * step + 6 + 7 * step - 2;
        int x = (width - total) / 2;
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();

        x = addLayerIcon(x, step, MapLayer.DAY, Items.DAYLIGHT_DETECTOR, allowed);
        x = addLayerIcon(x, step, MapLayer.NIGHT, Items.CLOCK, allowed);
        x = addLayerIcon(x, step, MapLayer.BIOME, Items.OAK_SAPLING, allowed);
        x = addLayerIcon(x, step, MapLayer.TOPO, Items.MAP, allowed);
        x = addLayerIcon(x, step, MapLayer.CAVE, Items.TORCH, allowed);
        x += 6;

        x = addToggleIcon(x, step, Items.LANTERN, "sharedjourney.action.show_cave", ClientConfig.SHOW_CAVE);
        x = addToggleIcon(x, step, Items.ZOMBIE_HEAD, "sharedjourney.action.show_mobs", ClientConfig.RADAR_HOSTILE);
        x = addToggleIcon(x, step, Items.PORKCHOP, "sharedjourney.action.show_animals", ClientConfig.RADAR_PASSIVE);
        x = addToggleIcon(x, step, Items.BONE, "sharedjourney.action.show_pets", ClientConfig.RADAR_PETS);
        x = addToggleIcon(x, step, Items.EMERALD, "sharedjourney.action.show_villagers", ClientConfig.RADAR_VILLAGERS);
        x = addToggleIcon(x, step, Items.IRON_BARS, "sharedjourney.action.show_grid", ClientConfig.SHOW_GRID);

        IconButton keys = addIcon(x, 6, Items.WRITABLE_BOOK, "sharedjourney.action.show_keys", b -> {
            showKeys = !showKeys;
            refreshToolbar();
        });
        toggleIcons.put(keys, () -> showKeys);

        addIcon(width - 26, 6, Items.BARRIER, "sharedjourney.action.close", b -> onClose());
    }

    /** Barre de gauche : recherche de position, suivi du joueur, zoom. */
    private void buildLeftToolbar() {
        int y = 40;
        addIcon(6, y, Items.COMPASS, "sharedjourney.action.locate", b -> {
            locateOpen = !locateOpen;
            updateLocateWidgets();
        });
        locateX = new EditBox(font, 32, y + 1, 56, 18, Component.literal("X"));
        locateX.setHint(Component.literal("x:"));
        locateZ = new EditBox(font, 92, y + 1, 56, 18, Component.literal("Z"));
        locateZ.setHint(Component.literal("z:"));
        locateGo = Button.builder(Component.literal("→"), b -> doLocate())
                .bounds(152, y, 20, 20)
                .build();
        addRenderableWidget(locateX);
        addRenderableWidget(locateZ);
        addRenderableWidget(locateGo);
        updateLocateWidgets();

        addIcon(6, y + 24, Items.ENDER_EYE, "sharedjourney.action.follow", b -> centerOnPlayer());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> zoomStep(1, width / 2.0, height / 2.0))
                .bounds(6, y + 48, 20, 20)
                .tooltip(Tooltip.create(Component.translatable("sharedjourney.action.zoom_in")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> zoomStep(-1, width / 2.0, height / 2.0))
                .bounds(6, y + 72, 20, 20)
                .tooltip(Tooltip.create(Component.translatable("sharedjourney.action.zoom_out")))
                .build());
    }

    private IconButton addIcon(int x, int y, Item icon, String tooltipKey, Button.OnPress press) {
        IconButton b = new IconButton(x, y, 20, new ItemStack(icon), Component.translatable(tooltipKey), press);
        addRenderableWidget(b);
        return b;
    }

    private int addLayerIcon(int x, int step, MapLayer target, Item icon, List<MapLayer> allowed) {
        IconButton b = addIcon(
                x,
                6,
                icon,
                "sharedjourney.action." + target.name().toLowerCase(Locale.ROOT),
                btn -> selectLayer(target));
        b.active = allowed.isEmpty() || allowed.contains(target);
        layerIcons.put(target, b);
        return x + step;
    }

    private int addToggleIcon(int x, int step, Item icon, String tooltipKey, ModConfigSpec.BooleanValue value) {
        IconButton b = addIcon(x, 6, icon, tooltipKey, btn -> {
            value.set(!value.get());
            refreshToolbar();
        });
        toggleIcons.put(b, value::get);
        return x + step;
    }

    private void selectLayer(MapLayer target) {
        layer = target;
        MinimapRenderer.setLayer(target);
        layerButton.setMessage(layerLabel());
        updateBandButtons();
        refreshToolbar();
    }

    private void refreshToolbar() {
        layerIcons.forEach((l, b) -> b.setSelected(layer == l));
        toggleIcons.forEach((b, state) -> b.setSelected(state.get()));
    }

    private void updateLocateWidgets() {
        locateX.setVisible(locateOpen);
        locateZ.setVisible(locateOpen);
        locateGo.visible = locateOpen;
    }

    private void doLocate() {
        try {
            centerX = Integer.parseInt(locateX.getValue().trim()) + 0.5;
            centerZ = Integer.parseInt(locateZ.getValue().trim()) + 0.5;
        } catch (NumberFormatException ignored) {
            // Entrée invalide : on ne bouge pas.
        }
    }

    /** Zoom par pas de x2 (échelle en puissances de 2), ancré sur un point écran. */
    private void zoomStep(int direction, double anchorX, double anchorY) {
        float old = zoom;
        float target = direction > 0 ? zoom * 2f : zoom / 2f;
        zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, target));
        double wx = centerX + (anchorX - width / 2.0) / old;
        double wz = centerZ + (anchorY - height / 2.0) / old;
        centerX = wx - (anchorX - width / 2.0) / zoom;
        centerZ = wz - (anchorY - height / 2.0) / zoom;
    }

    private void centerOnPlayer() {
        var player = Minecraft.getInstance().player;
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
        if (allowed.isEmpty()) {
            return;
        }

        int idx = allowed.indexOf(layer);
        layer = allowed.get((idx + 1) % allowed.size());
        MinimapRenderer.setLayer(layer);
        layerButton.setMessage(layerLabel());
        updateBandButtons();
    }

    private void changeBand(int delta) {
        List<Integer> bands = ClientMapCache.caveBands;
        if (bands.isEmpty()) {
            return;
        }

        bandIndex = Math.floorMod(bandIndex + delta, bands.size());
        layerButton.setMessage(layerLabel());
    }

    private int currentBand() {
        List<Integer> bands = ClientMapCache.caveBands;
        if (bands.isEmpty()) {
            return 0;
        }

        bandIndex = Math.min(bandIndex, bands.size() - 1);
        return bands.get(Math.max(0, bandIndex));
    }

    private void updateBandButtons() {
        boolean cave = layer == MapLayer.CAVE;
        bandMinus.visible = cave;
        bandPlus.visible = cave;
    }

    // ------------------------------------------------------------------ conversions écran <-> monde

    private double worldX(double mouseX) {
        return centerX + (mouseX - width / 2.0) / zoom;
    }

    private double worldZ(double mouseY) {
        return centerZ + (mouseY - height / 2.0) / zoom;
    }

    private double screenX(double wx) {
        return width / 2.0 + (wx - centerX) * zoom;
    }

    private double screenY(double wz) {
        return height / 2.0 + (wz - centerZ) * zoom;
    }

    // ------------------------------------------------------------------ interactions

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        dragged = false;
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

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
        if (super.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }

        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }

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
        if (mc.player == null || mc.level == null) {
            return;
        }

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
                })
                .bounds(x, y, w, h)
                .build();
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
        LevelChunk chunk = mc.level == null ? null : mc.level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        if (chunk == null) {
            return -1;
        }

        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15) + 1;
    }

    private void teleportTo(int wx, int wz) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        int y = surfaceYAt(wx, wz);
        String yArg = y >= 0 ? String.valueOf(y) : "~";
        mc.player.connection.sendUnsignedCommand("tp @s " + (wx + 0.5) + " " + yArg + " " + (wz + 0.5));
        onClose();
    }

    private void createWaypointAt(int wx, int wz) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        int y = surfaceYAt(wx, wz);
        int wy = y >= 0 ? y : mc.player.blockPosition().getY();
        Waypoint wp = Waypoint.create(
                "X:" + wx + " Z:" + wz,
                mc.level.dimension().location(),
                new BlockPos(wx, wy, wz),
                0xFFFFFF & ThreadLocalRandom.current().nextInt(),
                "user");
        mc.setScreen(new WaypointEditScreen(this, wp, true));
    }

    /** Écrit la position dans le chat (local) ; cliquer dessus rouvre la carte ici. */
    private void logCoords(int wx, int wz) {
        var mc = Minecraft.getInstance();
        Component msg = Component.translatable("sharedjourney.coords.chat", wx, wz)
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sj goto " + wx + " " + wz))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT, Component.translatable("sharedjourney.coords.open"))));
        mc.gui.getChat().addMessage(msg);
        onClose(); // referme la carte pour laisser voir le chat
    }

    /**
     * Infos de la colonne survolée : lues du chunk local s'il est chargé,
     * sinon du cache des réponses serveur (avec requête throttlée si absent).
     */
    private ClientMapCache.HoverInfo hoverInfoAt(Minecraft mc, int wx, int wz) {
        if (mc.level == null) {
            return null;
        }

        LevelChunk chunk = mc.level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        if (chunk != null) {
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
            BlockPos pos = new BlockPos(wx, top, wz);
            String biomeId = mc.level
                    .getBiome(pos)
                    .unwrapKey()
                    .map(k -> k.location().toString())
                    .orElse("");
            BlockState state = chunk.getBlockState(pos);
            String blockId = state.isAir()
                    ? ""
                    : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return new ClientMapCache.HoverInfo(top, biomeId, blockId);
        }

        ClientMapCache.HoverInfo cached = ClientMapCache.hoverInfo(wx, wz);
        if (cached != null) {
            return cached;
        }

        // Requête au serveur, throttlée : une par colonne survolée, re-tentée
        // au plus toutes les 500 ms si la réponse n'est pas encore arrivée.
        long key = ClientMapCache.columnKey(wx, wz);
        long now = System.currentTimeMillis();
        if (key != lastInfoRequestKey || now - lastInfoRequestAt > 500) {
            lastInfoRequestKey = key;
            lastInfoRequestAt = now;
            PacketDistributor.sendToServer(new Payloads.MapInfoRequestPayload(wx, wz));
        }

        return null;
    }

    private Waypoint nearestWaypoint(double mouseX, double mouseY) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }

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
        zoomStep(scrollY > 0 ? 1 : -1, mouseX, mouseY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean enter = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if (locateOpen && enter && (locateX.isFocused() || locateZ.isFocused())) {
            doLocate();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ rendu

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackgroundLayers(gg);
        super.render(gg, mouseX, mouseY, partialTick);

        var mc = Minecraft.getInstance();
        renderTopInfoBar(gg, mc);
        renderHoverBar(gg, mc, mouseX, mouseY);
        if (showKeys) {
            renderLegend(gg);
        }

        // Nom du waypoint survolé
        Waypoint hovered = nearestWaypoint(mouseX, mouseY);
        if (hovered != null) {
            gg.renderTooltip(
                    font,
                    Component.literal(
                            hovered.name() + " (" + hovered.x() + ", " + hovered.y() + ", " + hovered.z() + ")"),
                    mouseX,
                    mouseY);
        }
    }

    /** Barre d'infos joueur sous la barre d'actions : pseudo ■ position ■ biome ■ zoom. */
    private void renderTopInfoBar(GuiGraphics gg, Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        BlockPos pos = mc.player.blockPosition();
        String biome = mc.level
                .getBiome(pos)
                .unwrapKey()
                .map(k -> biomeName(k.location().toString()))
                .orElse("?");
        String text = mc.player.getGameProfile().getName()
                + " ■ x: " + pos.getX() + ", z: " + pos.getZ() + ", y: " + pos.getY()
                + " ■ " + biome
                + " ■ Zoom: " + Math.round(zoom * 2048);
        drawInfoBar(gg, text, 30);
    }

    /** Barre au-dessus des actions du bas : bloc survolé ■ position ■ biome. */
    private void renderHoverBar(GuiGraphics gg, Minecraft mc, int mouseX, int mouseY) {
        int wx = (int) Math.floor(worldX(mouseX));
        int wz = (int) Math.floor(worldZ(mouseY));
        ClientMapCache.HoverInfo info = hoverInfoAt(mc, wx, wz);
        if (info == null) {
            drawInfoBar(gg, "x: " + wx + ", z: " + wz, height - 40);
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (!info.blockId().isEmpty()) {
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(info.blockId()));
            sb.append(block.getName().getString()).append(" ■ ");
        }
        sb.append("x: ").append(wx).append(", z: ").append(wz).append(", y: ").append(info.y());
        if (!info.biomeId().isEmpty()) {
            sb.append(" ■ ").append(biomeName(info.biomeId()));
        }
        drawInfoBar(gg, sb.toString(), height - 40);
    }

    /** Nom localisé d'un biome depuis son identifiant "namespace:path". */
    private String biomeName(String biomeId) {
        var loc = ResourceLocation.parse(biomeId);
        return Component.translatable("biome." + loc.getNamespace() + "." + loc.getPath())
                .getString();
    }

    /** Ligne de texte centrée sur fond translucide (style JourneyMap). */
    private void drawInfoBar(GuiGraphics gg, String text, int y) {
        int w = font.width(text);
        int x = (width - w) / 2;
        gg.fill(x - 4, y - 2, x + w + 4, y + 10, 0xA0101010);
        gg.drawString(font, text, x, y, 0xE0E0E0);
    }

    /** Légende des contrôles (Show Keys), en bas à droite. */
    private void renderLegend(GuiGraphics gg) {
        List<String> lines = new ArrayList<>();
        for (KeyMapping key : List.of(
                ClientSetupEvents.OPEN_FULL_MAP,
                ClientSetupEvents.TOGGLE_MINIMAP,
                ClientSetupEvents.CYCLE_LAYER,
                ClientSetupEvents.ZOOM_IN,
                ClientSetupEvents.ZOOM_OUT)) {
            lines.add(key.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT) + "  "
                    + Component.translatable(key.getName()).getString());
        }
        lines.add(Component.translatable("sharedjourney.legend.drag").getString());
        lines.add(Component.translatable("sharedjourney.legend.scroll").getString());
        lines.add(Component.translatable("sharedjourney.legend.left_click").getString());
        lines.add(Component.translatable("sharedjourney.legend.right_click").getString());

        int maxW = 0;
        for (String line : lines) {
            maxW = Math.max(maxW, font.width(line));
        }
        int x = width - maxW - 10;
        int y = height - 44 - lines.size() * 10;
        gg.fill(x - 4, y - 4, width - 6, y + lines.size() * 10 + 2, 0xA0101010);
        for (String line : lines) {
            gg.drawString(font, line, x, y, 0xE0E0E0);
            y += 10;
        }
    }

    private void renderBackgroundLayers(GuiGraphics gg) {
        gg.fill(0, 0, width, height, MinimapRenderer.BACKGROUND);
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

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

        pose.popPose();

        // Grille de chunks en coordonnées écran (lignes fines de 1 px),
        // seulement quand un chunk fait au moins quelques pixels.
        if (ClientConfig.SHOW_GRID.get() && 16 * zoom >= 4f) {
            int gridColor = 0x38000000;
            int firstCx = Math.floorDiv((int) Math.floor(worldX(0)), 16);
            int lastCx = Math.floorDiv((int) Math.ceil(worldX(width)), 16) + 1;
            for (int gcx = firstCx; gcx <= lastCx; gcx++) {
                int sx = (int) Math.round(screenX(gcx * 16));
                gg.fill(sx, 0, sx + 1, height, gridColor);
            }
            int firstCz = Math.floorDiv((int) Math.floor(worldZ(0)), 16);
            int lastCz = Math.floorDiv((int) Math.ceil(worldZ(height)), 16) + 1;
            for (int gcz = firstCz; gcz <= lastCz; gcz++) {
                int sy = (int) Math.round(screenY(gcz * 16));
                gg.fill(0, sy, width, sy + 1, gridColor);
            }
        }

        // Flèche du joueur (taille constante quel que soit le zoom).
        EntityDots.drawPlayerArrow(
                gg,
                (float) screenX(mc.player.getX()),
                (float) screenY(mc.player.getZ()),
                mc.player.getYRot() + 180f,
                1.1f);

        // Waypoints par-dessus, en coordonnées écran (taille constante quel que soit le zoom)
        for (Waypoint wp : WaypointStore.forDimension(dim.location())) {
            if (!wp.visible()) {
                continue;
            }

            int sx = (int) Math.round(screenX(wp.x() + 0.5));
            int sy = (int) Math.round(screenY(wp.z() + 0.5));
            if (sx < -8 || sx > width + 8 || sy < -8 || sy > height + 8) {
                continue;
            }

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
                Integer color = EntityDots.colorFor(e);
                if (color == null) {
                    continue;
                }

                double ex = e.getX() - mc.player.getX(), ez = e.getZ() - mc.player.getZ();
                if (ex * ex + ez * ez > (double) radius * radius) {
                    continue;
                }

                int sx = (int) Math.round(screenX(e.getX()));
                int sy = (int) Math.round(screenY(e.getZ()));
                if (sx < -4 || sx > width + 4 || sy < -4 || sy > height + 4) {
                    continue;
                }

                EntityDots.draw(gg, sx, sy, color);
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
    public boolean isPauseScreen() {
        return false;
    }
}
