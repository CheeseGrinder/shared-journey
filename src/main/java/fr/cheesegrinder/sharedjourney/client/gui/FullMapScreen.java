package fr.cheesegrinder.sharedjourney.client.gui;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.api.client.event.FullMapScreenEvent;
import fr.cheesegrinder.sharedjourney.api.client.event.MapLayerChangedEvent;
import fr.cheesegrinder.sharedjourney.api.client.event.MapRenderEvent;
import fr.cheesegrinder.sharedjourney.client.compat.CreateTrainMapBridge;
import fr.cheesegrinder.sharedjourney.client.compat.JourneyMapFullscreenBridge;
import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.MapClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.RadarClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.WaypointClientConfig;
import fr.cheesegrinder.sharedjourney.client.event.ClientSetupEvents;
import fr.cheesegrinder.sharedjourney.client.render.EntityDots;
import fr.cheesegrinder.sharedjourney.client.render.MinimapRenderer;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.network.Payloads;
import fr.cheesegrinder.sharedjourney.common.region.RegionKey;
import fr.cheesegrinder.sharedjourney.common.util.Lang;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Fullscreen map (spec §6.2):
 * - drag to pan, scroll wheel to zoom
 * - left click: select a block (outline); double-click: create a waypoint
 * - double-click on a waypoint: edit it (name, color, deletion)
 * - right click: context menu (teleport, waypoints, chat)
 * - JourneyMap-style shortcuts: arrows (pan), B, C, F, J, T, =, -
 * - icon bar at the top to change layer; +/- at the bottom for the CAVE band
 * Missing/stale visible regions are requested from the server (throttled).
 */
public class FullMapScreen extends Screen implements JourneyMapFullscreenBridge.BridgedMapView {

    private static final long REQUEST_COOLDOWN_MS = 5_000;
    private static final int WAYPOINT_CLICK_PX = 6;
    private static final long DOUBLE_CLICK_MS = 350;
    /** Keyboard pan step (arrows), in blocks — same as JourneyMap. */
    private static final int PAN_STEP_BLOCKS = 16;

    /**
     * Zoom scale: powers of 2 displayed like JourneyMap
     * (label = zoom * 2048, from 64 to 16384). The lower bound is capped to
     * bound the number of regions scanned per frame.
     */
    private static final float ZOOM_MIN = 64f / 2048f;

    private static final float ZOOM_MAX = 16384f / 2048f;

    /** Key legend (Show Keys), kept for the duration of the session. */
    private static boolean showKeys = false;

    private double centerX;
    private double centerZ;
    private float zoom = 1.0f; // screen pixels per block
    private MapLayer layer;
    private int bandIndex;
    private boolean dragged;
    /**
     * Create train being followed (clicked on the map): the view re-centers
     * on it every frame until the player pans, recenters on themselves or
     * closes the map.
     */
    private UUID followedTrain;

    private BandSlider bandSlider;
    /** Right-click context menu (custom panel), null when closed. */
    private ContextMenu contextMenu;
    /**
     * A press consumed by the context menu closes it before the matching
     * release: this flag swallows that release so it doesn't fall through
     * to the map (block selection under the clicked row).
     */
    private boolean suppressNextRelease;

    // Top action bar: one button per layer + toggles with their state.
    private final Map<MapLayer, IconButton> layerIcons = new EnumMap<>(MapLayer.class);
    private final Map<IconButton, Supplier<Boolean>> toggleIcons = new LinkedHashMap<>();

    // Position search (left bar).
    private EditBox locateX;
    private EditBox locateZ;
    private Button locateGo;
    private boolean locateOpen;

    /** Visibility tracking for FullMapScreenEvent (init() also fires on resize). */
    private boolean apiOpened;

    // Selected block (single click) and double-click tracking.
    private boolean hasSelection;
    private int selectedBlockX;
    private int selectedBlockZ;
    private long lastClickAt;
    private UUID lastClickWaypoint;

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
            layer = allowed.getFirst();
        }

        bandIndex = Math.max(0, ClientMapCache.caveBands.indexOf(MinimapRenderer.currentCaveBand()));
    }

    /** Opens the map centered on a given position (/sj goto, chat click). */
    public FullMapScreen(double centerX, double centerZ) {
        this();
        this.centerX = centerX;
        this.centerZ = centerZ;
    }

    @Override
    protected void init() {
        bandSlider = addRenderableWidget(new BandSlider(width / 2 - 75, height - 40, 150, 20));
        closeContextMenu(); // resize: drop the transient menu
        buildTopToolbar();
        buildLeftToolbar();
        updateBandButtons();
        refreshToolbar();
        if (!apiOpened) {
            apiOpened = true;
            NeoForge.EVENT_BUS.post(new FullMapScreenEvent.Opened(this));
        }
    }

    @Override
    public void removed() {
        if (apiOpened) {
            apiOpened = false;
            NeoForge.EVENT_BUS.post(new FullMapScreenEvent.Closed());
        }

        super.removed();
    }

    // ------------------------------------------------------------------ action bars

    /** Top bar: layers, display toggles, and Close on the right. */
    private void buildTopToolbar() {
        layerIcons.clear();
        toggleIcons.clear();
        int size = 20;
        int step = size + 2;
        int total = 5 * step + 6 + 8 * step - 2;
        int x = (width - total) / 2;
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();

        x = addLayerIcon(x, step, MapLayer.DAY, Items.DAYLIGHT_DETECTOR, allowed);
        x = addLayerIcon(x, step, MapLayer.NIGHT, Items.CLOCK, allowed);
        x = addLayerIcon(x, step, MapLayer.BIOME, Items.OAK_SAPLING, allowed);
        x = addLayerIcon(x, step, MapLayer.TOPO, Items.MAP, allowed);
        x = addLayerIcon(x, step, MapLayer.CAVE, Items.TORCH, allowed);
        x += 6;

        x = addToggleIcon(x, step, Items.LANTERN, Lang.ACTION_SHOW_CAVE, MapClientConfig.SHOW_CAVE);
        x = addToggleIcon(x, step, Items.ZOMBIE_HEAD, Lang.ACTION_SHOW_MOBS, RadarClientConfig.RADAR_HOSTILE);
        x = addToggleIcon(x, step, Items.PORKCHOP, Lang.ACTION_SHOW_ANIMALS, RadarClientConfig.RADAR_PASSIVE);
        x = addToggleIcon(x, step, Items.BONE, Lang.ACTION_SHOW_PETS, RadarClientConfig.RADAR_PETS);
        x = addToggleIcon(x, step, Items.EMERALD, Lang.ACTION_SHOW_VILLAGERS, RadarClientConfig.RADAR_VILLAGERS);
        x = addToggleIcon(x, step, Items.IRON_BARS, Lang.ACTION_SHOW_GRID, MapClientConfig.SHOW_GRID);
        x = addToggleIcon(x, step, Items.PLAYER_HEAD, Lang.ACTION_HIDE_FROM_MAP, RadarClientConfig.HIDE_FROM_MAP);

        IconButton keys = addIcon(x, 6, Items.WRITABLE_BOOK, Lang.ACTION_SHOW_KEYS, b -> {
            showKeys = !showKeys;
            refreshToolbar();
        });
        toggleIcons.put(keys, () -> showKeys);

        addIcon(width - 26, 6, Items.BARRIER, Lang.ACTION_CLOSE, b -> onClose());
    }

    /** Left bar: position search, follow player, zoom. */
    private void buildLeftToolbar() {
        // Below the bridged plugins' overlay controls (Create's train map
        // draws its toggle in the top-left corner) to avoid overlapping.
        int y = 70;
        addIcon(6, y, Items.COMPASS, Lang.ACTION_LOCATE, b -> {
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

        // Buttons stacked at the same step (22 px) for an even column.
        addIcon(6, y + 22, Items.ENDER_EYE, Lang.ACTION_FOLLOW, b -> centerOnPlayer());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> zoomStep(1, width / 2.0, height / 2.0))
                .bounds(6, y + 44, 20, 20)
                .tooltip(Tooltip.create(Component.translatable(Lang.ACTION_ZOOM_IN)))
                .build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> zoomStep(-1, width / 2.0, height / 2.0))
                .bounds(6, y + 66, 20, 20)
                .tooltip(Tooltip.create(Component.translatable(Lang.ACTION_ZOOM_OUT)))
                .build());
        addIcon(6, y + 88, Items.NAME_TAG, Lang.ACTION_WAYPOINTS, b -> Minecraft.getInstance()
                .setScreen(new WaypointListScreen(this)));
    }

    private IconButton addIcon(int x, int y, Item icon, String tooltipKey, Button.OnPress press) {
        IconButton b = new IconButton(x, y, 20, new ItemStack(icon), Component.translatable(tooltipKey), press);
        addRenderableWidget(b);
        return b;
    }

    private int addLayerIcon(int x, int step, MapLayer target, Item icon, List<MapLayer> allowed) {
        IconButton b = addIcon(x, 6, icon, Lang.actionLayer(target.name()), btn -> selectLayer(target));
        b.active = allowed.isEmpty() || allowed.contains(target);
        layerIcons.put(target, b);
        return x + step;
    }

    private int addToggleIcon(int x, int step, Item icon, String tooltipKey, ModConfigSpec.BooleanValue value) {
        IconButton b = addIcon(x, 6, icon, tooltipKey, btn -> {
            value.set(!value.get());
            // set() only changes the in-memory value: force writing the
            // file so the setting survives past this session.
            ClientConfig.SPEC.save();
            if (value == RadarClientConfig.HIDE_FROM_MAP) {
                // Server-enforced preference: send immediately.
                PacketDistributor.sendToServer(new Payloads.MapVisibilityPayload(value.get()));
            }

            refreshToolbar();
        });
        toggleIcons.put(b, value);
        return x + step;
    }

    /**
     * Changes the fullscreen map's layer ONLY: the minimap keeps its own
     * selection (including day/night/cave auto mode).
     */
    private void selectLayer(MapLayer target) {
        layer = target;
        updateBandButtons();
        refreshToolbar();
        NeoForge.EVENT_BUS.post(new MapLayerChangedEvent(target, false));
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
            // Invalid input: stay put.
        }
    }

    /** Zoom by x2 steps (power-of-2 scale), anchored on a screen point. */
    private void zoomStep(int direction, double anchorX, double anchorY) {
        float old = zoom;
        float target = direction > 0 ? zoom * 2f : zoom / 2f;
        zoom = Math.clamp(target, ZOOM_MIN, ZOOM_MAX);
        double wx = centerX + (anchorX - width / 2.0) / old;
        double wz = centerZ + (anchorY - height / 2.0) / old;
        centerX = wx - (anchorX - width / 2.0) / zoom;
        centerZ = wz - (anchorY - height / 2.0) / zoom;
    }

    private void centerOnPlayer() {
        followedTrain = null;
        var player = Minecraft.getInstance().player;
        if (player != null) {
            centerX = player.getX();
            centerZ = player.getZ();
        }
    }

    /** Y range of the current CAVE band, shown on the slider. */
    private String bandLabel() {
        int band = currentBand();
        return "y" + (band * 16) + ".." + (band * 16 + 15);
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
        bandSlider.visible = layer == MapLayer.CAVE && !ClientMapCache.caveBands.isEmpty();
    }

    // ------------------------------------------------------------------ screen <-> world conversions

    /** Screen -> world conversion (public: MapView + JourneyMap bridge). */
    @Override
    public double worldX(double mouseX) {
        return centerX + (mouseX - width / 2.0) / zoom;
    }

    /** Screen -> world conversion (public: MapView + JourneyMap bridge). */
    @Override
    public double worldZ(double mouseY) {
        return centerZ + (mouseY - height / 2.0) / zoom;
    }

    @Override
    public double screenX(double wx) {
        return width / 2.0 + (wx - centerX) * zoom;
    }

    @Override
    public double screenY(double wz) {
        return height / 2.0 + (wz - centerZ) * zoom;
    }

    // ------------------------------------------------------------------ view for the API (MapView) and the JourneyMap
    // bridge (IFullscreen)

    @Override
    public boolean isMinimap() {
        return false;
    }

    @Override
    public ResourceLocation dimension() {
        var mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.dimension().location() : Level.OVERWORLD.location();
    }

    @Override
    public int caveBand() {
        return layer == MapLayer.CAVE ? currentBand() : 0;
    }

    @Override
    public Screen screen() {
        return this;
    }

    @Override
    public int viewWidth() {
        return width;
    }

    @Override
    public int viewHeight() {
        return height;
    }

    @Override
    public double centerX() {
        return centerX;
    }

    @Override
    public double centerZ() {
        return centerZ;
    }

    /** Current zoom in screen pixels per block. */
    @Override
    public float zoomScale() {
        return zoom;
    }

    @Override
    public MapLayer currentLayer() {
        return layer;
    }

    @Override
    public void centerOn(double x, double z) {
        centerX = x;
        centerZ = z;
    }

    @Override
    public void zoomIn() {
        zoomStep(1, width / 2.0, height / 2.0);
    }

    @Override
    public void zoomOut() {
        zoomStep(-1, width / 2.0, height / 2.0);
    }

    @Override
    public void close() {
        onClose();
    }

    // ------------------------------------------------------------------ interactions

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        dragged = false;
        // Context menu first: it draws on top of everything.
        if (contextMenu != null && button == 0 && contextMenu.mouseClicked(mouseX, mouseY)) {
            suppressNextRelease = true;
            return true;
        }

        // Create's train map toggle widget (drawn top-left by its overlay):
        // its native handler only knows about JM's screen.
        if (button == 0
                && pluginOverlaysActive()
                && CreateTrainMapBridge.handleToggleClick((int) mouseX, (int) mouseY)) {
            return true;
        }

        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        closeContextMenu(); // click outside the menu: close it
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Widgets first (CAVE band slider...): otherwise dragging their
        // handle would pan the map underneath.
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }

        if (button == 0) {
            dragged = true;
            followedTrain = null;
            centerX -= dragX / zoom;
            centerZ -= dragY / zoom;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (suppressNextRelease) {
            suppressNextRelease = false;
            return true;
        }

        if (super.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }

        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }

        if ((button == 0 || button == 1) && !dragged) {
            boolean overlays = pluginOverlaysActive();
            // JourneyMap PRE click (cancellable): a bridged plugin (RNS
            // deposits...) can consume the click before our own handling.
            if (overlays && JourneyMapFullscreenBridge.fireClick(this, true, mouseX, mouseY, button)) {
                return true;
            }

            boolean handled;
            if (button == 0) {
                handled = handleLeftClick(mc, mouseX, mouseY);
            } else {
                // Right click: context menu (TP, waypoints, position in chat)
                openContextMenu(mouseX, mouseY);
                handled = true;
            }

            if (overlays) {
                JourneyMapFullscreenBridge.fireClick(this, false, mouseX, mouseY, button);
            }
            return handled;
        }
        return false;
    }

    /**
     * JourneyMap-style left click: single click = select a block
     * (outline), double-click on a block = create a waypoint (modal),
     * double-click on a waypoint = edit it. No action on a single click
     * on a waypoint.
     */
    private boolean handleLeftClick(Minecraft mc, double mouseX, double mouseY) {
        long now = System.currentTimeMillis();
        boolean doubleClick = now - lastClickAt < DOUBLE_CLICK_MS;
        Waypoint nearest = nearestWaypoint(mouseX, mouseY);
        if (nearest != null) {
            if (doubleClick && nearest.id().equals(lastClickWaypoint)) {
                lastClickAt = 0;
                lastClickWaypoint = null;
                mc.setScreen(new WaypointEditScreen(this, nearest));
                return true;
            }

            lastClickAt = now;
            lastClickWaypoint = nearest.id();
            hasSelection = false;
            return true;
        }

        int wx = (int) Math.floor(worldX(mouseX));
        int wz = (int) Math.floor(worldZ(mouseY));
        // Click on a Create train: follow it (cancelled by panning,
        // recentering on the player or closing the map).
        UUID train = CreateTrainMapBridge.trainAt(worldX(mouseX), worldZ(mouseY));
        if (train != null) {
            followedTrain = train;
            hasSelection = false;
            lastClickAt = now;
            lastClickWaypoint = null;
            return true;
        }
        boolean sameBlock = hasSelection && selectedBlockX == wx && selectedBlockZ == wz;
        if (doubleClick && sameBlock && lastClickWaypoint == null) {
            lastClickAt = 0;
            createWaypointAt(wx, wz, Waypoint.Type.DIMENSION);
            return true;
        }

        hasSelection = true;
        selectedBlockX = wx;
        selectedBlockZ = wz;
        lastClickAt = now;
        lastClickWaypoint = null;
        return true;
    }

    // ------------------------------------------------------------------ context menu

    /**
     * JourneyMap-style menu: Teleport (op), Waypoints > (submenu:
     * create, temporary, global, show/hide all), Position in chat.
     */
    private void openContextMenu(double mouseX, double mouseY) {
        closeContextMenu();
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        int wx = (int) Math.floor(worldX(mouseX));
        int wz = (int) Math.floor(worldZ(mouseY));
        var dim = mc.level.dimension().location();
        List<ContextMenu.Item> items = new ArrayList<>();
        // TP goes through /tp: reserved to op players (level 2, known client-side).
        if (mc.player.hasPermissions(2)) {
            items.add(ContextMenu.Item.action(Component.translatable(Lang.CONTEXT_TELEPORT), () -> teleportTo(wx, wz)));
        }

        items.add(ContextMenu.Item.submenu(
                Component.translatable(Lang.CONTEXT_WAYPOINTS),
                List.of(
                        ContextMenu.Item.action(
                                Component.translatable(Lang.CONTEXT_WAYPOINT),
                                () -> createWaypointAt(wx, wz, Waypoint.Type.DIMENSION)),
                        ContextMenu.Item.action(
                                Component.translatable(Lang.CONTEXT_WAYPOINT_TEMP), () -> createTempWaypointAt(wx, wz)),
                        ContextMenu.Item.action(
                                Component.translatable(Lang.CONTEXT_WAYPOINT_PUBLIC),
                                () -> createWaypointAt(wx, wz, Waypoint.Type.PUBLIC)),
                        ContextMenu.Item.action(
                                Component.translatable(Lang.CONTEXT_SHOW_ALL),
                                () -> WaypointStore.setAllVisible(dim, true)),
                        ContextMenu.Item.action(
                                Component.translatable(Lang.CONTEXT_HIDE_ALL),
                                () -> WaypointStore.setAllVisible(dim, false)),
                        ContextMenu.Item.action(
                                Component.translatable(Lang.CONTEXT_MANAGE_WAYPOINTS),
                                () -> mc.setScreen(new WaypointListScreen(this))))));
        items.add(ContextMenu.Item.action(Component.translatable(Lang.CONTEXT_CHAT), () -> logCoords(wx, wz)));

        Component title = Component.literal(wx + ", " + wz);
        contextMenu = new ContextMenu(font, title, items, mouseX, mouseY, width, height, this::closeContextMenu);
    }

    private void closeContextMenu() {
        contextMenu = null;
    }

    /** Surface Y if the chunk is loaded client-side, else -1. */
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

        // Arrival Y computed server-side (/sj tp): the client doesn't
        // always have the target chunk locally, and a "~" would keep the
        // flying altitude (arriving inside rock or mid-air).
        mc.player.connection.sendUnsignedCommand("sj tp " + wx + " " + wz);
        onClose();
    }

    /** Opens the creation modal for a waypoint of the requested type. */
    private void createWaypointAt(int wx, int wz, Waypoint.Type type) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        Waypoint wp = Waypoint.create(
                "X:" + wx + " Z:" + wz,
                mc.level.dimension().location(),
                new BlockPos(wx, surfaceOrPlayerY(wx, wz), wz),
                0xFFFFFF & ThreadLocalRandom.current().nextInt(),
                Waypoint.SOURCE_USER,
                type);
        mc.setScreen(new WaypointEditScreen(this, wp, true));
    }

    /** Directly creates a temporary waypoint (no modal, JourneyMap style). */
    private void createTempWaypointAt(int wx, int wz) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        Waypoint wp = Waypoint.create(
                "Temp X:" + wx + " Z:" + wz,
                mc.level.dimension().location(),
                new BlockPos(wx, surfaceOrPlayerY(wx, wz), wz),
                0xFFFFFF & ThreadLocalRandom.current().nextInt(),
                Waypoint.SOURCE_USER,
                Waypoint.Type.TEMP);
        WaypointStore.add(wp);
    }

    /** Surface Y if known locally, else the player's Y. */
    private int surfaceOrPlayerY(int wx, int wz) {
        var mc = Minecraft.getInstance();
        int y = surfaceYAt(wx, wz);
        if (y >= 0) {
            return y;
        }

        return mc.player != null ? mc.player.blockPosition().getY() : 64;
    }

    /** Writes the position to (local) chat; clicking it reopens the map here. */
    private void logCoords(int wx, int wz) {
        var mc = Minecraft.getInstance();
        Component msg = Component.translatable(Lang.COORDS_CHAT, wx, wz).withStyle(style -> style.withColor(
                        ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sj goto " + wx + " " + wz))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(Lang.COORDS_OPEN))));
        mc.gui.getChat().addMessage(msg);
        onClose(); // close the map to reveal the chat
    }

    /**
     * Info for the hovered column: read from the local chunk if loaded
     * (perfectly fresh), otherwise from the hover sidecars pushed by the
     * server with the region sync — fully local, zero request.
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

        return ClientMapCache.hoverInfo(wx, wz);
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
        if (contextMenu != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeContextMenu();
            return true;
        }

        boolean enter = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if (locateOpen && enter && (locateX.isFocused() || locateZ.isFocused())) {
            doLocate();
            return true;
        }

        // No shortcuts while typing in a text field.
        if (getFocused() instanceof EditBox) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (handleShortcut(keyCode, scanCode)) {
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * JourneyMap-style shortcuts: arrows = pan 16 blocks, B = waypoint at
     * cursor, C = cursor position in chat, F = follow player, J = close
     * the map, T = open chat, =/- = zoom. The mod's configured keys
     * (layer, map) are also honored.
     */
    private boolean handleShortcut(int keyCode, int scanCode) {
        var mc = Minecraft.getInstance();
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> {
                centerZ -= PAN_STEP_BLOCKS;
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                centerZ += PAN_STEP_BLOCKS;
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                centerX -= PAN_STEP_BLOCKS;
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                centerX += PAN_STEP_BLOCKS;
                return true;
            }
            case GLFW.GLFW_KEY_B -> {
                createWaypointAt(
                        (int) Math.floor(worldX(cursorX())),
                        (int) Math.floor(worldZ(cursorY())),
                        Waypoint.Type.DIMENSION);
                return true;
            }
            case GLFW.GLFW_KEY_C -> {
                logCoords((int) Math.floor(worldX(cursorX())), (int) Math.floor(worldZ(cursorY())));
                return true;
            }
            case GLFW.GLFW_KEY_F -> {
                centerOnPlayer();
                return true;
            }
            case GLFW.GLFW_KEY_J -> {
                onClose();
                return true;
            }
            case GLFW.GLFW_KEY_T -> {
                // Chat over the map, opened on the NEXT tick: otherwise the
                // same frame's 't' character would land in the chat.
                mc.tell(() -> mc.setScreen(new MapChatScreen(this)));
                return true;
            }
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> {
                zoomStep(1, width / 2.0, height / 2.0);
                return true;
            }
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> {
                zoomStep(-1, width / 2.0, height / 2.0);
                return true;
            }
            default -> {
                // The mod's configurable keys, tested below.
            }
        }

        if (ClientSetupEvents.CYCLE_LAYER.matches(keyCode, scanCode)) {
            cycleLayer();
            return true;
        }

        if (ClientSetupEvents.OPEN_FULL_MAP.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }

        return false;
    }

    /** Layer change from the keyboard, among the allowed layers. */
    private void cycleLayer() {
        List<MapLayer> allowed = ClientMapCache.layersForCurrentDim();
        if (allowed.isEmpty()) {
            return;
        }

        int idx = allowed.indexOf(layer);
        selectLayer(allowed.get((idx + 1) % allowed.size()));
    }

    /** Cursor x position in GUI coordinates (outside a mouse event). */
    private double cursorX() {
        var mc = Minecraft.getInstance();
        return mc.mouseHandler.xpos()
                * mc.getWindow().getGuiScaledWidth()
                / (double) mc.getWindow().getScreenWidth();
    }

    /** Cursor y position in GUI coordinates (outside a mouse event). */
    private double cursorY() {
        var mc = Minecraft.getInstance();
        return mc.mouseHandler.ypos()
                * mc.getWindow().getGuiScaledHeight()
                / (double) mc.getWindow().getScreenHeight();
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Bridged plugin overlays (Create trains, RNS deposits) active: only
     * from zoom 1024 onward and never on the CAVE layer.
     */
    private boolean pluginOverlaysActive() {
        return zoom >= 1024f / 2048f && layer != MapLayer.CAVE;
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        var mc = Minecraft.getInstance();
        // Followed train (clicked): keep the view centered on it.
        if (followedTrain != null) {
            Vec3 trainPos = CreateTrainMapBridge.trainPosition(followedTrain);
            if (trainPos == null) {
                followedTrain = null;
            } else {
                centerX = trainPos.x;
                centerZ = trainPos.z;
            }
        }
        renderBackgroundLayers(gg);
        // Bridged JourneyMap plugin overlays (Create trains, RNS
        // deposits...): above the map, below the widgets. Create's train
        // map is rendered directly (its JM render path has a broken hover
        // pick — see CreateTrainMapBridge), with name tooltips.
        if (pluginOverlaysActive()) {
            JourneyMapFullscreenBridge.fireRender(this, gg, mouseX, mouseY, partialTick);
            CreateTrainMapBridge.renderMap(
                    gg, width, height, centerX, centerZ, zoom, mouseX, mouseY, true, followedTrain);
        }

        // Public API overlays (api.client.event.MapRenderEvent): same spot
        // as the bridged overlays, but never gated by zoom or layer.
        NeoForge.EVENT_BUS.post(new MapRenderEvent(gg, this, partialTick));

        // Player arrow ABOVE the map and the plugin overlays.
        if (mc.player != null) {
            EntityDots.drawPlayerArrow(
                    gg,
                    (float) screenX(mc.player.getX()),
                    (float) screenY(mc.player.getZ()),
                    mc.player.getYRot() + 180f,
                    1.1f);
        }

        super.render(gg, mouseX, mouseY, partialTick);

        renderTopInfoBar(gg, mc);
        renderHoverBar(gg, mc, mouseX, mouseY);
        if (showKeys) {
            renderLegend(gg);
        }

        // Name of the hovered waypoint
        Waypoint hovered = nearestWaypoint(mouseX, mouseY);
        if (hovered != null) {
            gg.renderTooltip(
                    font,
                    Component.literal(
                            hovered.name() + " (" + hovered.x() + ", " + hovered.y() + ", " + hovered.z() + ")"),
                    mouseX,
                    mouseY);
        }

        // Context menu last: always on top.
        if (contextMenu != null) {
            contextMenu.render(gg, mouseX, mouseY);
        }
    }

    /** Player info bar below the action bar: name ■ position ■ biome ■ zoom. */
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

    /** Bar at the bottom of the screen: hovered block ■ position ■ biome. */
    private void renderHoverBar(GuiGraphics gg, Minecraft mc, int mouseX, int mouseY) {
        int wx = (int) Math.floor(worldX(mouseX));
        int wz = (int) Math.floor(worldZ(mouseY));
        ClientMapCache.HoverInfo info = hoverInfoAt(mc, wx, wz);
        if (info == null) {
            drawInfoBar(gg, "x: " + wx + ", z: " + wz, height - 24);
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
        drawInfoBar(gg, sb.toString(), height - 24);
    }

    /** Localized biome name from its "namespace:path" identifier. */
    private String biomeName(String biomeId) {
        var loc = ResourceLocation.parse(biomeId);
        return Component.translatable("biome." + loc.getNamespace() + "." + loc.getPath())
                .getString();
    }

    /** Centered text line over a translucent background (JourneyMap style). */
    private void drawInfoBar(GuiGraphics gg, String text, int y) {
        int w = font.width(text);
        int x = (width - w) / 2;
        gg.fill(x - 4, y - 2, x + w + 4, y + 10, 0xA0101010);
        gg.drawString(font, text, x, y, 0xE0E0E0);
    }

    /** Controls legend (Show Keys), bottom right. */
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
        lines.add(Component.translatable(Lang.LEGEND_DRAG).getString());
        lines.add(Component.translatable(Lang.LEGEND_SCROLL).getString());
        lines.add(Component.translatable(Lang.LEGEND_DOUBLE_CLICK).getString());
        lines.add(Component.translatable(Lang.LEGEND_RIGHT_CLICK).getString());
        lines.add(Component.translatable(Lang.LEGEND_ARROWS).getString());
        lines.add(Component.translatable(Lang.LEGEND_KEY_B).getString());
        lines.add(Component.translatable(Lang.LEGEND_KEY_C).getString());
        lines.add(Component.translatable(Lang.LEGEND_KEY_F).getString());
        lines.add(Component.translatable(Lang.LEGEND_KEY_T).getString());
        lines.add(Component.translatable(Lang.LEGEND_KEY_J).getString());

        int maxW = 0;
        for (String line : lines) {
            maxW = Math.max(maxW, font.width(line));
        }

        // Reduced scale, anchored bottom right of the screen.
        float scale = 0.75f;
        int lineH = 10;
        int boxW = (int) (maxW * scale) + 8;
        int boxH = (int) (lines.size() * lineH * scale) + 6;
        int x0 = width - boxW - 6;
        // Larger bottom margin: stay clear of the chat area.
        int y0 = height - boxH - 16;
        gg.fill(x0, y0, width - 6, y0 + boxH, 0xA0101010);
        gg.pose().pushPose();
        gg.pose().translate(x0 + 4, y0 + 3, 0);
        gg.pose().scale(scale, scale, 1f);
        int y = 0;
        for (String line : lines) {
            gg.drawString(font, line, 0, y, 0xE0E0E0);
            y += lineH;
        }
        gg.pose().popPose();
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
                    if (ClientMapCache.regenActive) {
                        MinimapRenderer.drawRegenVeil(gg, dim.location(), rx, rz);
                    }
                }
                // Request if missing or potentially stale (throttled); the
                // hover sidecar (INFO) rides along with the displayed layer.
                long now = System.currentTimeMillis();
                RegionKey infoKey = new RegionKey(dim, MapLayer.INFO, 0, rx, rz);
                for (RegionKey wanted : List.of(key, infoKey)) {
                    Long last = ClientMapCache.LAST_REQUESTED.get(wanted);
                    if ((last == null || now - last > REQUEST_COOLDOWN_MS) && missing.size() < 64) {
                        missing.add(wanted);
                        knownVersions.add(ClientMapCache.versionOf(wanted));
                        ClientMapCache.LAST_REQUESTED.put(wanted, now);
                    }
                }
            }
        }

        pose.popPose();

        // Chunk grid in screen coordinates (1 px thin lines), only from
        // zoom 1024 onward (label = zoom * 2048).
        if (MapClientConfig.SHOW_GRID.get() && zoom >= 1024f / 2048f) {
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

        // Outline of the selected block (single click); double-click = waypoint.
        if (hasSelection) {
            int sx0 = (int) Math.round(screenX(selectedBlockX));
            int sy0 = (int) Math.round(screenY(selectedBlockZ));
            int sx1 = (int) Math.round(screenX(selectedBlockX + 1));
            int sy1 = (int) Math.round(screenY(selectedBlockZ + 1));
            gg.renderOutline(sx0, sy0, Math.max(1, sx1 - sx0), Math.max(1, sy1 - sy0), 0xFFE0E0E0);
        }

        // Waypoints on top, in screen coordinates (constant size regardless of zoom)
        for (Waypoint wp : WaypointStore.forDimension(dim.location())) {
            if (!WaypointStore.isShown(wp)) {
                continue;
            }

            int sx = (int) Math.round(screenX(wp.x() + 0.5));
            int sy = (int) Math.round(screenY(wp.z() + 0.5));
            if (sx < -8 || sx > width + 8 || sy < -8 || sy > height + 8) {
                continue;
            }

            EntityDots.drawWaypointDiamond(gg, sx, sy, wp.colorRgb(), 1.2f);
            if (zoom >= 0.5f && WaypointClientConfig.SHOW_WAYPOINT_NAMES.get()) {
                gg.drawCenteredString(font, wp.name(), sx, sy - 13, 0xFFFFFF);
            }
        }

        // ---- Other players' heads (server positions, no distance limit)
        if (RadarClientConfig.RADAR_PLAYERS.get()) {
            var selfId = mc.player.getUUID();
            for (var pos : ClientMapCache.playerPositions.values()) {
                if (pos.id().equals(selfId) || !pos.dimension().equals(dim.location())) {
                    continue;
                }

                double pwx = pos.x();
                double pwz = pos.z();
                Player live = mc.level.getPlayerByUUID(pos.id());
                if (live != null) {
                    pwx = live.getX();
                    pwz = live.getZ();
                }

                int sx = (int) Math.round(screenX(pwx));
                int sy = (int) Math.round(screenY(pwz));
                if (sx < -8 || sx > width + 8 || sy < -8 || sy > height + 8) {
                    continue;
                }

                EntityDots.drawPlayerHead(gg, sx, sy, pos.id(), 8);
            }
        }

        // Entity radar: same filters and server cap as the minimap.
        if (RadarClientConfig.RADAR_ENABLED.get() && ClientMapCache.radarMaxRadius > 0) {
            int radius = Math.min(RadarClientConfig.RADAR_RADIUS.get(), ClientMapCache.radarMaxRadius);
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
        // No vanilla blur: the background is painted by renderBackgroundLayers().
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Chat opened OVER the map: the map keeps rendering in the background
     * and reopens when the chat closes (Escape or message sent), instead
     * of being lost.
     */
    private static class MapChatScreen extends ChatScreen {

        private final FullMapScreen parent;

        MapChatScreen(FullMapScreen parent) {
            super("");
            this.parent = parent;
        }

        @Override
        public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            // The map behind (non-interactive), then the chat on top.
            parent.render(gg, -1, -1, partialTick);
            super.render(gg, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
            // Vanilla closes the chat by setting screen=null: reopen the map.
            if (minecraft != null && minecraft.screen == null) {
                minecraft.setScreen(parent);
            }
            return handled;
        }
    }

    /** CAVE band selection slider, only visible on the CAVE layer. */
    private class BandSlider extends AbstractSliderButton {

        BandSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), 0);
            syncFromIndex();
        }

        /** Aligns the handle on the current band (opening, resize). */
        private void syncFromIndex() {
            int count = ClientMapCache.caveBands.size();
            if (count > 1) {
                value = bandIndex / (double) (count - 1);
            } else {
                value = 0;
            }

            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(bandLabel()));
        }

        @Override
        protected void applyValue() {
            int count = ClientMapCache.caveBands.size();
            if (count > 0) {
                bandIndex = (int) Math.round(value * (count - 1));
            }
        }
    }
}
