package fr.cheesegrinder.sharedjourney.client.gui;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.MapClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.MinimapClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.RadarClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.WaypointClientConfig;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.common.config.PrivacyServerConfig;
import fr.cheesegrinder.sharedjourney.common.network.OpsConfigPayloads;
import fr.cheesegrinder.sharedjourney.common.network.PlayerVisibilityPayloads;
import fr.cheesegrinder.sharedjourney.common.util.Lang;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.PacketDistributor;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * In-game settings screen, opened from the fullscreen map. Client tabs
 * (minimap, radar, map, waypoints, addons) edit the CLIENT config live —
 * values apply immediately, the file is saved once on close. The Server
 * tab (ops, permission level 2+, hidden otherwise) edits a working copy
 * of the server config received through {@code OpsConfigPayload} and only
 * sends it back on Apply; the server sanitizes, persists and re-broadcasts
 * the active layers, then echoes the authoritative snapshot. Row tooltips
 * reuse the NeoForge config screen descriptions
 * ({@code sharedjourney.configuration.*.tooltip}).
 */
public class MapSettingsScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 46;
    private static final int LIST_BOTTOM_MARGIN = 32;
    private static final int ROW_WIDTH = 380;
    private static final int CONTROL_WIDTH = 140;

    /**
     * Settings tabs; ADDONS only exists when a bridged mod is present,
     * SERVER only for ops (permission level 2+).
     */
    private enum Tab {
        MINIMAP,
        RADAR,
        MAP,
        WAYPOINTS,
        ADDONS,
        SERVER
    }

    private final Screen parent;
    private Tab tab = Tab.MINIMAP;
    private OptionList list;
    private final Map<Tab, Button> tabButtons = new EnumMap<>(Tab.class);
    private Button doneButton;
    private Button applyButton;

    /** Ops working copy (null until the server snapshot arrives). */
    private OpsState ops;
    /** Snapshot the ops rows were built from (arrival/echo detection). */
    private OpsConfigPayloads.OpsConfigPayload shownOps;

    private boolean opsRequested;

    public MapSettingsScreen(Screen parent) {
        super(Component.translatable(Lang.SETTINGS_TITLE));
        this.parent = parent;
    }

    private static boolean isOp() {
        var player = Minecraft.getInstance().player;
        return player != null && player.hasPermissions(2);
    }

    @Override
    protected void init() {
        List<Tab> tabs = new ArrayList<>(List.of(Tab.MINIMAP, Tab.RADAR, Tab.MAP, Tab.WAYPOINTS));
        if (ModList.get().isLoaded("create") || ModList.get().isLoaded("create_rns")) {
            tabs.add(Tab.ADDONS);
        }

        if (isOp()) {
            tabs.add(Tab.SERVER);
        }

        int tabWidth = 70;
        int x = (width - (tabs.size() * (tabWidth + 2) - 2)) / 2;
        tabButtons.clear();
        for (Tab t : tabs) {
            Button b = Button.builder(Component.translatable(Lang.settingsTab(t.name())), btn -> selectTab(t))
                    .bounds(x, 22, tabWidth, 20)
                    .build();
            tabButtons.put(t, b);
            addRenderableWidget(b);
            x += tabWidth + 2;
        }

        list = addRenderableWidget(new OptionList(minecraft, width, height - LIST_TOP - LIST_BOTTOM_MARGIN, LIST_TOP));
        doneButton = addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20)
                .build());
        applyButton = addRenderableWidget(Button.builder(Component.translatable(Lang.SETTINGS_APPLY), b -> applyOps())
                .bounds(width / 2 + 2, height - 26, 100, 20)
                .build());
        selectTab(tab);
    }

    private void selectTab(Tab target) {
        tab = target;
        tabButtons.forEach((t, b) -> b.active = t != target);
        if (target == Tab.SERVER && !opsRequested) {
            opsRequested = true;
            PacketDistributor.sendToServer(new OpsConfigPayloads.OpsConfigRequestPayload());
        }

        boolean server = target == Tab.SERVER;
        doneButton.setX(server ? width / 2 - 102 : width / 2 - 50);
        applyButton.visible = server;
        applyButton.active = ops != null;
        rebuildRows();
    }

    private void rebuildRows() {
        list.rebuild(
                switch (tab) {
                    case MINIMAP -> minimapRows();
                    case RADAR -> radarRows();
                    case MAP -> mapRows();
                    case WAYPOINTS -> waypointRows();
                    case ADDONS -> addonRows();
                    case SERVER -> serverRows();
                });
    }

    @Override
    public void render(@NotNull GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Server snapshot arrived (or apply echo): refresh the working copy.
        OpsConfigPayloads.OpsConfigPayload snapshot = ClientMapCache.opsConfig;
        if (tab == Tab.SERVER && snapshot != null && snapshot != shownOps) {
            shownOps = snapshot;
            ops = OpsState.from(snapshot);
            applyButton.active = true;
            rebuildRows();
        }

        super.render(gg, mouseX, mouseY, partialTick);
        gg.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
    }

    /** Client edits are applied live; the file is written once, here. */
    @Override
    public void onClose() {
        ClientConfig.SPEC.save();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void applyOps() {
        if (ops == null) {
            return;
        }

        PacketDistributor.sendToServer(ops.toPayload());
    }

    // ------------------------------------------------------------------ client tabs

    private List<OptionRow> minimapRows() {
        List<OptionRow> rows = new ArrayList<>();
        rows.add(configToggle(Lang.SETTINGS_MINIMAP_ENABLED, "enabled", MinimapClientConfig.MINIMAP_ENABLED));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_MINIMAP_SIZE),
                Lang.configTooltip("size"),
                64,
                320,
                MinimapClientConfig.MINIMAP_SIZE.get(),
                v -> Component.translatable(Lang.UNIT_PIXELS, v),
                MinimapClientConfig.MINIMAP_SIZE::set));
        rows.add(cycle(
                Component.translatable(Lang.SETTINGS_MINIMAP_CORNER),
                Lang.configTooltip("corner"),
                List.of(MinimapClientConfig.Corner.values()),
                MinimapClientConfig.MINIMAP_CORNER.get(),
                c -> Component.translatable(Lang.settingsValue(c.name())),
                MinimapClientConfig.MINIMAP_CORNER::set));
        rows.add(cycle(
                Component.translatable(Lang.SETTINGS_MINIMAP_SHAPE),
                Lang.configTooltip("shape"),
                List.of(MinimapClientConfig.Shape.values()),
                MinimapClientConfig.MINIMAP_SHAPE.get(),
                s -> Component.translatable(Lang.settingsValue(s.name())),
                MinimapClientConfig.MINIMAP_SHAPE::set));
        rows.add(configToggle(Lang.ACTION_ROTATE_MAP, "rotateWithPlayer", MinimapClientConfig.MINIMAP_ROTATE));
        rows.add(configToggle(Lang.SETTINGS_MINIMAP_COORDS, "showCoordinates", MinimapClientConfig.SHOW_COORDS));
        // Zoom in x0.25 notches (0.25 to 4.0 pixels per block).
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_MINIMAP_ZOOM),
                Lang.configTooltip("zoomDefault"),
                1,
                16,
                (int) Math.round(MinimapClientConfig.MINIMAP_ZOOM_DEFAULT.get() / 0.25),
                v -> Component.literal(String.format(Locale.ROOT, "%.2f×", v * 0.25)),
                v -> MinimapClientConfig.MINIMAP_ZOOM_DEFAULT.set(v * 0.25)));
        return rows;
    }

    private List<OptionRow> radarRows() {
        List<OptionRow> rows = new ArrayList<>();
        rows.add(configToggle(Lang.SETTINGS_RADAR_ENABLED, "enabled", RadarClientConfig.RADAR_ENABLED));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_RADAR_RADIUS, ClientMapCache.radarMaxRadius),
                Lang.configTooltip("radius"),
                8,
                128,
                RadarClientConfig.RADAR_RADIUS.get(),
                v -> Component.translatable(Lang.UNIT_BLOCKS, v),
                RadarClientConfig.RADAR_RADIUS::set));
        rows.add(configToggle(Lang.SETTINGS_RADAR_HEADS, "mobHeads", RadarClientConfig.RADAR_MOB_HEADS));
        rows.add(configToggle(Lang.ACTION_SHOW_PLAYERS, "showPlayers", RadarClientConfig.RADAR_PLAYERS));
        rows.add(configToggle(Lang.ACTION_SHOW_MOBS, "showHostile", RadarClientConfig.RADAR_HOSTILE));
        rows.add(configToggle(Lang.ACTION_SHOW_ANIMALS, "showPassive", RadarClientConfig.RADAR_PASSIVE));
        rows.add(configToggle(Lang.ACTION_SHOW_PETS, "showPets", RadarClientConfig.RADAR_PETS));
        rows.add(configToggle(Lang.ACTION_SHOW_VILLAGERS, "showVillagers", RadarClientConfig.RADAR_VILLAGERS));
        // Server-enforced preference: pushed immediately, like the toolbar.
        rows.add(toggle(
                Component.translatable(Lang.ACTION_HIDE_FROM_MAP),
                Lang.configTooltip("hideFromMap"),
                RadarClientConfig.HIDE_FROM_MAP::get,
                v -> {
                    RadarClientConfig.HIDE_FROM_MAP.set(v);
                    PacketDistributor.sendToServer(new PlayerVisibilityPayloads.MapVisibilityPayload(v));
                }));
        return rows;
    }

    private List<OptionRow> mapRows() {
        List<String> layerNames = MapLayer.values().stream()
                .filter(l -> l != MapLayer.INFO)
                .map(MapLayer::name)
                .toList();
        String currentDefault = MapClientConfig.DEFAULT_LAYER.get().trim().toUpperCase(Locale.ROOT);
        if (!layerNames.contains(currentDefault)) {
            currentDefault = layerNames.getFirst();
        }

        List<OptionRow> rows = new ArrayList<>();
        rows.add(cycle(
                Component.translatable(Lang.SETTINGS_MAP_DEFAULT_LAYER),
                Lang.configTooltip("defaultLayer"),
                layerNames,
                currentDefault,
                // Custom layers may ship no lang key: fall back to the id.
                n -> Component.translatableWithFallback(Lang.layerName(n), n.toLowerCase(Locale.ROOT)),
                MapClientConfig.DEFAULT_LAYER::set));
        rows.add(configToggle(Lang.SETTINGS_MAP_AUTO_LAYER, "autoLayer", MapClientConfig.AUTO_LAYER));
        rows.add(configToggle(Lang.ACTION_SHOW_CAVE, "showCave", MapClientConfig.SHOW_CAVE));
        rows.add(configToggle(Lang.SETTINGS_MAP_REMEMBER_LAYER, "rememberLayer", MapClientConfig.REMEMBER_LAYER));
        rows.add(configToggle(Lang.ACTION_SHOW_GRID, "showGrid", MapClientConfig.SHOW_GRID));
        rows.add(configToggle(Lang.SETTINGS_MAP_DISK_CACHE, "diskCache", MapClientConfig.DISK_CACHE_ENABLED));
        return rows;
    }

    private List<OptionRow> waypointRows() {
        List<OptionRow> rows = new ArrayList<>();
        rows.add(configToggle(Lang.ACTION_SHOW_WAYPOINTS, "showWaypoints", MapClientConfig.SHOW_WAYPOINTS));
        rows.add(configToggle(
                Lang.SETTINGS_WAYPOINT_NAMES, "showWaypointNames", WaypointClientConfig.SHOW_WAYPOINT_NAMES));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_WAYPOINT_TEMP_RADIUS),
                Lang.configTooltip("tempWaypointRadius"),
                1,
                128,
                WaypointClientConfig.TEMP_WAYPOINT_RADIUS.get(),
                v -> Component.translatable(Lang.UNIT_BLOCKS, v),
                WaypointClientConfig.TEMP_WAYPOINT_RADIUS::set));
        rows.add(
                configToggle(Lang.SETTINGS_WAYPOINT_BEACONS, "waypointBeacons", WaypointClientConfig.WAYPOINT_BEACONS));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_WAYPOINT_BEACON_MIN),
                Lang.configTooltip("beaconMinDistance"),
                0,
                512,
                WaypointClientConfig.BEACON_MIN_DISTANCE.get(),
                v -> Component.translatable(Lang.UNIT_BLOCKS, v),
                WaypointClientConfig.BEACON_MIN_DISTANCE::set));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_WAYPOINT_BEACON_MAX),
                Lang.configTooltip("beaconMaxDistance"),
                16,
                4096,
                WaypointClientConfig.BEACON_MAX_DISTANCE.get(),
                v -> Component.translatable(Lang.UNIT_BLOCKS, v),
                WaypointClientConfig.BEACON_MAX_DISTANCE::set));
        rows.add(configToggle(Lang.SETTINGS_WAYPOINT_DEATH, "deathWaypoints", WaypointClientConfig.DEATH_WAYPOINTS));
        return rows;
    }

    /** Bridged mods, one section each (only present when the mod is). */
    private List<OptionRow> addonRows() {
        List<OptionRow> rows = new ArrayList<>();
        if (ModList.get().isLoaded("create")) {
            rows.add(new HeaderRow(Component.literal("Create")));
            rows.add(configToggle(Lang.ACTION_SHOW_TRAINS, "showTrainOverlay", MapClientConfig.SHOW_TRAIN_OVERLAY));
        }

        if (ModList.get().isLoaded("create_rns")) {
            rows.add(new HeaderRow(Component.literal("Create: Rock & Stone")));
            rows.add(configToggle(
                    Lang.ACTION_SHOW_DEPOSITS, "showDepositOverlay", MapClientConfig.SHOW_DEPOSIT_OVERLAY));
        }

        return rows;
    }

    // ------------------------------------------------------------------ ops tab

    private List<OptionRow> serverRows() {
        List<OptionRow> rows = new ArrayList<>();
        if (ops == null) {
            rows.add(new InfoRow(Component.translatable(Lang.SETTINGS_WAITING)));
            return rows;
        }

        rows.add(new HeaderRow(Component.translatable(Lang.SETTINGS_SERVER_LAYERS)));
        ops.layersByDim.forEach((dim, set) -> rows.add(new LayerSetRow(Component.literal(dimensionLabel(dim)), set)));
        rows.add(new LayerSetRow(Component.translatable(Lang.SETTINGS_SERVER_DEFAULT_LAYERS), ops.defaultLayers));

        rows.add(new HeaderRow(Component.translatable(Lang.SETTINGS_SERVER_BANDS)));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_SERVER_BAND_MIN),
                Lang.configTooltip("caveBands"),
                -8,
                20,
                ops.bandMin,
                MapSettingsScreen::bandLabel,
                v -> ops.bandMin = v));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_SERVER_BAND_MAX),
                Lang.configTooltip("caveBands"),
                -8,
                20,
                ops.bandMax,
                MapSettingsScreen::bandLabel,
                v -> ops.bandMax = v));

        rows.add(new HeaderRow(Component.translatable(Lang.SETTINGS_SERVER_SYNC)));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_SERVER_PUSH_RADIUS),
                Lang.configTooltip("pushRadiusRegions"),
                0,
                8,
                ops.pushRadius,
                v -> Component.literal(String.valueOf(v)),
                v -> ops.pushRadius = v));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_SERVER_MAX_KB),
                Lang.configTooltip("maxKbPerSecondPerPlayer"),
                32,
                8192,
                ops.maxKb,
                v -> Component.translatable(Lang.UNIT_KBPS, v),
                v -> ops.maxKb = v));
        // Stored in ticks (payload/config), displayed in seconds.
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_SERVER_SYNC_RATE),
                Lang.configTooltip("syncRateTicks"),
                5,
                1200,
                ops.syncRate,
                v -> Component.translatable(Lang.UNIT_SECONDS, String.format(Locale.ROOT, "%.2f", v / 20.0)),
                v -> ops.syncRate = v));
        rows.add(toggle(
                Component.translatable(Lang.SETTINGS_SERVER_ON_DEMAND),
                Lang.configTooltip("allowOnDemandRequests"),
                () -> ops.onDemand,
                v -> ops.onDemand = v));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_SERVER_RADAR_CAP),
                Lang.configTooltip("radarMaxRadius"),
                0,
                128,
                ops.radarCap,
                v -> Component.translatable(Lang.UNIT_BLOCKS, v),
                v -> ops.radarCap = v));

        rows.add(new HeaderRow(Component.translatable(Lang.SETTINGS_SERVER_WAYPOINTS)));
        rows.add(toggle(
                Component.translatable(Lang.SETTINGS_SERVER_DEATH),
                Lang.configTooltip("deathWaypointsEnabled"),
                () -> ops.deathWaypoints,
                v -> ops.deathWaypoints = v));
        rows.add(cycle(
                Component.translatable(Lang.SETTINGS_SERVER_STORAGE),
                Lang.configTooltip("waypointStorage"),
                List.of(Boolean.TRUE, Boolean.FALSE),
                ops.storageServer,
                v -> Component.translatable(Lang.settingsValue(v ? "server" : "client")),
                v -> ops.storageServer = v));

        rows.add(new HeaderRow(Component.translatable(Lang.SETTINGS_SERVER_PRIVACY)));
        rows.add(cycle(
                Component.translatable(Lang.SETTINGS_SERVER_POLICY),
                Lang.configTooltip("hiddenAreaPolicy"),
                List.of(PrivacyServerConfig.HiddenAreaPolicy.values()),
                ops.policy,
                p -> Component.translatable(Lang.settingsValue(p.name())),
                p -> ops.policy = p));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_SERVER_QUAR_RADIUS),
                Lang.configTooltip("quarantineRadiusChunks"),
                1,
                32,
                ops.quarantineRadius,
                v -> Component.translatable(Lang.UNIT_CHUNKS, v),
                v -> ops.quarantineRadius = v));
        rows.add(intSlider(
                Component.translatable(Lang.SETTINGS_SERVER_QUAR_DRAIN),
                Lang.configTooltip("quarantineDrainMinutes"),
                1,
                120,
                ops.quarantineDrain,
                v -> Component.translatable(Lang.UNIT_MINUTES, v),
                v -> ops.quarantineDrain = v));
        return rows;
    }

    /** Y range covered by a CAVE band (band = floor(y/16)). */
    private static Component bandLabel(int band) {
        return Component.literal("y " + band * 16 + ".." + (band * 16 + 15));
    }

    /** Short dimension label: path only for vanilla dimensions. */
    private static String dimensionLabel(ResourceLocation dim) {
        if ("minecraft".equals(dim.getNamespace())) {
            return dim.getPath();
        }

        return dim.toString();
    }

    // ------------------------------------------------------------------ row factories

    /** Client config toggle: applied live, saved once on close. */
    private OptionRow configToggle(String labelKey, String configKey, ModConfigSpec.BooleanValue value) {
        return toggle(Component.translatable(labelKey), Lang.configTooltip(configKey), value::get, value::set);
    }

    private OptionRow toggle(Component label, String tooltipKey, Supplier<Boolean> get, Consumer<Boolean> set) {
        Button b = Button.builder(onOff(get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    btn.setMessage(onOff(now));
                })
                .size(48, 20)
                .build();
        return row(label, tooltipKey, b);
    }

    private static Component onOff(boolean on) {
        String key = on ? Lang.SETTINGS_ON : Lang.SETTINGS_OFF;
        return Component.translatable(key).withStyle(s -> s.withColor(on ? 0x7FD37F : 0xD37F7F));
    }

    private <T> OptionRow cycle(
            Component label,
            String tooltipKey,
            List<T> values,
            T current,
            Function<T, Component> name,
            Consumer<T> set) {
        int start = Math.max(0, values.indexOf(current));
        int[] index = {start};
        Button b = Button.builder(name.apply(values.get(start)), btn -> {
                    index[0] = (index[0] + 1) % values.size();
                    T value = values.get(index[0]);
                    set.accept(value);
                    btn.setMessage(name.apply(value));
                })
                .size(CONTROL_WIDTH, 20)
                .build();
        return row(label, tooltipKey, b);
    }

    private OptionRow intSlider(
            Component label,
            String tooltipKey,
            int min,
            int max,
            int current,
            IntFunction<Component> display,
            IntConsumer set) {
        return row(label, tooltipKey, new IntSlider(min, max, current, display, set));
    }

    /** Attaches the description tooltip to the control, then wraps the row. */
    private OptionRow row(Component label, String tooltipKey, AbstractWidget control) {
        if (tooltipKey != null) {
            control.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        }

        return new WidgetRow(label, control);
    }

    // ------------------------------------------------------------------ list and rows

    /** Scrollable option list; rows are rebuilt on tab change. */
    private class OptionList extends ContainerObjectSelectionList<OptionRow> {

        OptionList(Minecraft mc, int width, int height, int y) {
            super(mc, width, height, y, ROW_HEIGHT);
        }

        void rebuild(List<OptionRow> rows) {
            clearEntries();
            setScrollAmount(0);
            rows.forEach(this::addEntry);
        }

        @Override
        public int getRowWidth() {
            return Math.min(ROW_WIDTH, width - 40);
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + getRowWidth() / 2 + 10;
        }
    }

    /** Base of every settings row. */
    private abstract static class OptionRow extends ContainerObjectSelectionList.Entry<OptionRow> {}

    /** Label on the left, one control (toggle/slider/cycle) on the right. */
    private class WidgetRow extends OptionRow {

        private final Component label;
        private final AbstractWidget control;

        WidgetRow(Component label, AbstractWidget control) {
            this.label = label;
            this.control = control;
        }

        @Override
        public void render(
                @NotNull GuiGraphics gg,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovering,
                float partialTick) {
            gg.drawString(font, label, left, top + 6, UiColors.TEXT);
            control.setPosition(left + width - control.getWidth(), top);
            control.render(gg, mouseX, mouseY, partialTick);
        }

        @Override
        public @NotNull List<? extends GuiEventListener> children() {
            return List.of(control);
        }

        @Override
        public @NotNull List<? extends NarratableEntry> narratables() {
            return List.of(control);
        }
    }

    /** Section header (ops tab groupings, addon mod names). */
    private class HeaderRow extends OptionRow {

        private final Component label;

        HeaderRow(Component label) {
            this.label = label;
        }

        @Override
        public void render(
                @NotNull GuiGraphics gg,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovering,
                float partialTick) {
            gg.drawCenteredString(font, label, left + width / 2, top + 8, UiColors.TEXT_TITLE);
        }

        @Override
        public @NotNull List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public @NotNull List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    /** Plain centered message ("waiting for the server config"). */
    private class InfoRow extends OptionRow {

        private final Component label;

        InfoRow(Component label) {
            this.label = label;
        }

        @Override
        public void render(
                @NotNull GuiGraphics gg,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovering,
                float partialTick) {
            gg.drawCenteredString(font, label, left + width / 2, top + 8, 0xAAAAAA);
        }

        @Override
        public @NotNull List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public @NotNull List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    /**
     * One editable layer set (a dimension of sharedLayers, or the
     * defaults): label + a checkbox per display layer, JourneyMap
     * "Dimensions" modal style. Mutates the working copy in place.
     */
    private class LayerSetRow extends OptionRow {

        private static final int BOX_STEP = 22;

        private final Component label;
        private final List<Checkbox> boxes = new ArrayList<>();

        LayerSetRow(Component label, Set<MapLayer> set) {
            this.label = label;
            for (MapLayer layer : MapLayer.values()) {
                // Custom layers are not server-config-managed: the ops
                // layer editor only shows the built-in display layers.
                if (layer == MapLayer.INFO || !layer.isBuiltin()) {
                    continue;
                }

                boxes.add(Checkbox.builder(Component.empty(), font)
                        .selected(set.contains(layer))
                        .onValueChange((cb, value) -> {
                            if (value) {
                                set.add(layer);
                            } else {
                                set.remove(layer);
                            }
                        })
                        .tooltip(Tooltip.create(Component.translatable(Lang.layerName(layer.name()))))
                        .build());
            }
        }

        @Override
        public void render(
                @NotNull GuiGraphics gg,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovering,
                float partialTick) {
            gg.drawString(font, label, left, top + 6, UiColors.TEXT);
            int x = left + width - boxes.size() * BOX_STEP;
            for (Checkbox box : boxes) {
                box.setPosition(x, top + 1);
                box.render(gg, mouseX, mouseY, partialTick);
                x += BOX_STEP;
            }
        }

        @Override
        public @NotNull List<? extends GuiEventListener> children() {
            return boxes;
        }

        @Override
        public @NotNull List<? extends NarratableEntry> narratables() {
            return boxes;
        }
    }

    /** Int slider over [min, max] with a custom value display. */
    private static class IntSlider extends AbstractSliderButton {

        private final int min;
        private final int max;
        private final IntFunction<Component> display;
        private final IntConsumer set;

        IntSlider(int min, int max, int current, IntFunction<Component> display, IntConsumer set) {
            super(0, 0, CONTROL_WIDTH, 20, Component.empty(), (current - min) / (double) (max - min));
            this.min = min;
            this.max = max;
            this.display = display;
            this.set = set;
            updateMessage();
        }

        private int intValue() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(display.apply(intValue()));
        }

        @Override
        protected void applyValue() {
            set.accept(intValue());
        }
    }

    // ------------------------------------------------------------------ ops working copy

    /**
     * Mutable working copy of the editable server config, decoded from the
     * snapshot payload; only sent back (re-encoded) on Apply.
     */
    private static final class OpsState {

        final Map<ResourceLocation, Set<MapLayer>> layersByDim =
                new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        final Set<MapLayer> defaultLayers = new LinkedHashSet<>();
        int bandMin;
        int bandMax;
        int pushRadius;
        int maxKb;
        int syncRate;
        int radarCap;
        int quarantineRadius;
        int quarantineDrain;
        boolean onDemand;
        boolean deathWaypoints;
        boolean storageServer;
        PrivacyServerConfig.HiddenAreaPolicy policy;

        static OpsState from(OpsConfigPayloads.OpsConfigPayload p) {
            OpsState s = new OpsState();
            for (String name : p.defaultLayers()) {
                addLayer(s.defaultLayers, name);
            }

            for (String entry : p.sharedLayers()) {
                String[] parts = entry.split("=", 2);
                ResourceLocation dim = ResourceLocation.tryParse(parts[0].trim());
                if (dim == null || parts.length < 2) {
                    continue;
                }

                Set<MapLayer> set = new LinkedHashSet<>();
                for (String layer : parts[1].split(",")) {
                    addLayer(set, layer);
                }

                s.layersByDim.put(dim, set);
            }

            // Dimensions the server serves but sharedLayers does not list:
            // editable too, seeded with their effective (default) layers.
            ClientMapCache.layersByDim.forEach((dim, layers) -> s.layersByDim.computeIfAbsent(dim, d -> {
                Set<MapLayer> set = new LinkedHashSet<>();
                // Custom layers excluded: not server-config-managed.
                layers.stream().filter(l -> l != MapLayer.INFO && l.isBuiltin()).forEach(set::add);
                return set;
            }));

            List<Integer> bands = p.caveBands();
            s.bandMin = bands.isEmpty() ? -4 : Collections.min(bands);
            s.bandMax = bands.isEmpty() ? 7 : Collections.max(bands);
            s.pushRadius = p.pushRadiusRegions();
            s.maxKb = p.maxKbPerSecondPerPlayer();
            s.syncRate = p.syncRateTicks();
            s.onDemand = p.allowOnDemandRequests();
            s.radarCap = p.radarMaxRadius();
            s.deathWaypoints = p.deathWaypointsEnabled();
            s.storageServer = p.waypointStorageServer();
            s.policy = parsePolicy(p.hiddenAreaPolicy());
            s.quarantineRadius = p.quarantineRadiusChunks();
            s.quarantineDrain = p.quarantineDrainMinutes();
            return s;
        }

        private static void addLayer(Set<MapLayer> set, String name) {
            try {
                MapLayer layer = MapLayer.valueOf(name.trim().toUpperCase(Locale.ROOT));
                if (layer != MapLayer.INFO && layer.isBuiltin()) {
                    set.add(layer);
                }
            } catch (IllegalArgumentException ignored) {
                // Unknown layer name in the snapshot: dropped.
            }
        }

        private static PrivacyServerConfig.HiddenAreaPolicy parsePolicy(String name) {
            try {
                return PrivacyServerConfig.HiddenAreaPolicy.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return PrivacyServerConfig.HiddenAreaPolicy.QUARANTINE;
            }
        }

        OpsConfigPayloads.OpsConfigPayload toPayload() {
            List<String> shared = new ArrayList<>();
            layersByDim.forEach((dim, set) ->
                    shared.add(dim + "=" + set.stream().map(MapLayer::name).collect(Collectors.joining(","))));
            int lo = Math.min(bandMin, bandMax);
            int hi = Math.max(bandMin, bandMax);
            List<Integer> bands = IntStream.rangeClosed(lo, hi).boxed().toList();
            return new OpsConfigPayloads.OpsConfigPayload(
                    defaultLayers.stream().map(MapLayer::name).toList(),
                    shared,
                    bands,
                    pushRadius,
                    maxKb,
                    syncRate,
                    onDemand,
                    radarCap,
                    deathWaypoints,
                    storageServer,
                    policy.name(),
                    quarantineRadius,
                    quarantineDrain);
        }
    }
}
