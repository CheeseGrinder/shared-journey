package fr.cheesegrinder.sharedjourney.client.gui;

import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.util.Lang;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Waypoint management screen (JourneyMap-style): group list on the left
 * ("All" + one row per group; click to select — highlighted in gold —
 * checkbox = group visibility, add/rename/delete/done below; reserved
 * and bridged groups cannot be renamed/deleted), waypoints of the
 * selected group on the right with per-waypoint actions. The dimension
 * is plain waypoint data (shown as info), not a sorting axis.
 */
public class WaypointListScreen extends Screen {

    /**
     * Pseudo-group showing every waypoint. The leading space cannot
     * collide with a real group name: created names are trimmed.
     */
    private static final String GROUP_ALL = " all";

    private static final int MARGIN = 8;
    private static final int LEFT_WIDTH = 130;
    private static final int PANEL_GAP = 8;
    private static final int GROUP_LIST_TOP = 24;
    /** New group / Rename+Delete / Done rows under the group list. */
    private static final int LEFT_FOOTER = 76;

    private static final int RIGHT_LIST_TOP = 24;
    private static final int GROUP_ITEM_HEIGHT = 20;
    private static final int WAYPOINT_ITEM_HEIGHT = 24;
    /** Selected group name color (gold, JM-like active tab). */
    private static final int SELECTED_COLOR = 0xFFD770;

    private final Screen parent;
    private String selectedGroup = GROUP_ALL;

    private GroupList groupList;
    private WaypointList waypointList;
    private Button renameButton;
    private Button deleteButton;

    public WaypointListScreen(Screen parent) {
        super(Component.translatable(Lang.WAYPOINTS_TITLE));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!GROUP_ALL.equals(selectedGroup) && !WaypointStore.groups().contains(selectedGroup)) {
            selectedGroup = GROUP_ALL;
        }

        // Left panel: groups + management buttons + Done.
        groupList = addRenderableWidget(new GroupList(minecraft, LEFT_WIDTH, height - GROUP_LIST_TOP - LEFT_FOOTER));
        addRenderableWidget(Button.builder(Component.translatable(Lang.WAYPOINTS_GROUP_NEW), b -> openGroupPrompt(null))
                .bounds(MARGIN, height - 70, LEFT_WIDTH, 20)
                .build());
        renameButton = addRenderableWidget(
                Button.builder(Component.translatable(Lang.WAYPOINTS_GROUP_RENAME), b -> openGroupPrompt(selectedGroup))
                        .bounds(MARGIN, height - 48, LEFT_WIDTH / 2 - 2, 20)
                        .build());
        deleteButton = addRenderableWidget(
                Button.builder(Component.translatable(Lang.WAYPOINTS_GROUP_DELETE), b -> confirmDeleteGroup())
                        .bounds(MARGIN + LEFT_WIDTH / 2 + 2, height - 48, LEFT_WIDTH / 2 - 2, 20)
                        .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(MARGIN, height - 26, LEFT_WIDTH, 20)
                .build());

        // Right panel: the selected group's waypoints; the last row of the
        // list is the creation entry.
        int rightX = MARGIN + LEFT_WIDTH + PANEL_GAP;
        int rightWidth = width - rightX - MARGIN;
        waypointList =
                addRenderableWidget(new WaypointList(minecraft, rightX, rightWidth, height - RIGHT_LIST_TOP - MARGIN));

        onGroupSelected();
    }

    @Override
    public void render(@NotNull GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.render(gg, mouseX, mouseY, partialTick);
        gg.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        // Only the creation row left: the group is empty.
        if (waypointList.children().size() <= 1) {
            int cx = MARGIN + LEFT_WIDTH + PANEL_GAP + (width - MARGIN * 2 - LEFT_WIDTH - PANEL_GAP) / 2;
            gg.drawCenteredString(font, Component.translatable(Lang.WAYPOINTS_EMPTY_GROUP), cx, height / 2, 0xAAAAAA);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ group actions

    /** Selection changed: refresh the right panel and the group buttons. */
    private void onGroupSelected() {
        boolean editable = !GROUP_ALL.equals(selectedGroup) && WaypointStore.isEditableGroup(selectedGroup);
        renameButton.active = editable;
        deleteButton.active = editable;
        waypointList.rebuild();
    }

    /** Name prompt shared by group creation (renameFrom=null) and rename. */
    private void openGroupPrompt(String renameFrom) {
        String titleKey = renameFrom == null ? Lang.WAYPOINTS_GROUP_NEW : Lang.WAYPOINTS_GROUP_RENAME_TITLE;
        Consumer<String> onConfirm = name -> {
            boolean applied =
                    renameFrom == null ? WaypointStore.createGroup(name) : WaypointStore.renameGroup(renameFrom, name);
            if (applied) {
                selectedGroup = name.trim();
            }
        };
        Minecraft.getInstance()
                .setScreen(new GroupNameScreen(
                        this, Component.translatable(titleKey), renameFrom == null ? "" : renameFrom, onConfirm));
    }

    private void confirmDeleteGroup() {
        String group = selectedGroup;
        long count = WaypointStore.all().stream()
                .filter(wp -> wp.group().equals(group))
                .count();
        Component message = Component.translatable(Lang.WAYPOINTS_GROUP_DELETE_CONFIRM, groupLabel(group), count);
        Minecraft.getInstance().setScreen(new GroupDeleteScreen(this, message, () -> {
            WaypointStore.deleteGroup(group);
            selectedGroup = GROUP_ALL;
        }));
    }

    /** Display name of a group: reserved groups are translated. */
    static Component groupLabel(String group) {
        if (GROUP_ALL.equals(group)) {
            return Component.translatable(Lang.WAYPOINTS_GROUP_ALL);
        }

        if (Waypoint.GROUP_DEFAULT.equals(group)) {
            return Component.translatable(Lang.GROUP_DEFAULT);
        }

        if (Waypoint.GROUP_DEATHS.equals(group)) {
            return Component.translatable(Lang.GROUP_DEATHS);
        }

        if (Waypoint.GROUP_PUBLIC.equals(group)) {
            return Component.translatable(Lang.GROUP_PUBLIC);
        }

        if (Waypoint.GROUP_BANNERS.equals(group)) {
            return Component.translatable(Lang.GROUP_BANNERS);
        }
        return Component.literal(group);
    }

    // ------------------------------------------------------------------ waypoint actions

    /** Creation at the player's position, in the selected group. */
    private void createWaypoint() {
        String group =
                WaypointStore.assignableGroups().contains(selectedGroup) ? selectedGroup : Waypoint.GROUP_DEFAULT;
        WaypointEditScreen.openCreateAtPlayer(this, group);
    }

    private void teleportTo(Waypoint wp) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        // Same path as the fullscreen map: /sj tp computes the arrival Y
        // server-side (the target chunk is not always loaded client-side).
        mc.player.connection.sendUnsignedCommand("sj tp " + wp.x() + " " + wp.z());
        mc.setScreen(null);
    }

    /** Waypoints of the selected group ("All": everything), sorted by name. */
    private List<Waypoint> selectedWaypoints() {
        List<Waypoint> result = new ArrayList<>();
        for (Waypoint wp : WaypointStore.all()) {
            if (GROUP_ALL.equals(selectedGroup) || wp.group().equals(selectedGroup)) {
                result.add(wp);
            }
        }

        result.sort(Comparator.comparing(Waypoint::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Short dimension label: path only for vanilla dimensions. */
    private static String dimensionLabel(ResourceLocation dim) {
        if ("minecraft".equals(dim.getNamespace())) {
            return dim.getPath();
        }
        return dim.toString();
    }

    // ------------------------------------------------------------------ group list (left)

    /** Left panel: "All" + one selectable row per group. */
    private class GroupList extends ContainerObjectSelectionList<GroupList.GroupRow> {

        GroupList(Minecraft mc, int width, int height) {
            super(mc, width, height, GROUP_LIST_TOP, GROUP_ITEM_HEIGHT);
            setX(MARGIN);
            addEntry(new GroupRow(GROUP_ALL));
            for (String group : WaypointStore.groups()) {
                addEntry(new GroupRow(group));
            }

            for (GroupRow row : children()) {
                if (row.group.equals(selectedGroup)) {
                    setSelected(row);
                }
            }
        }

        @Override
        public int getRowWidth() {
            return width - 12;
        }

        @Override
        protected int getScrollbarPosition() {
            return getX() + width - 6;
        }

        /** One group: visibility checkbox + name (count); click = select. */
        class GroupRow extends ContainerObjectSelectionList.Entry<GroupRow> {

            private final String group;
            /** Null for the "All" pseudo-group (no global toggle). */
            private final Checkbox visibility;

            GroupRow(String group) {
                this.group = group;
                boolean all = GROUP_ALL.equals(group);
                this.visibility = all
                        ? null
                        : Checkbox.builder(Component.empty(), font)
                                .selected(WaypointStore.isGroupVisible(group))
                                .onValueChange((cb, value) -> WaypointStore.setGroupVisible(group, value))
                                .tooltip(Tooltip.create(Component.translatable(Lang.WAYPOINTS_GROUP_TOGGLE)))
                                .build();
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (super.mouseClicked(mouseX, mouseY, button)) {
                    return true; // checkbox handled it
                }

                setSelected(this);
                selectedGroup = group;
                onGroupSelected();
                return true;
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
                int textX = left + 4;
                if (visibility != null) {
                    visibility.setPosition(left, top + 1);
                    visibility.render(gg, mouseX, mouseY, partialTick);
                    textX = left + 22;
                }

                Component label =
                        Component.empty().append(groupLabel(group)).append(Component.literal(" (" + count() + ")"));
                int color = rowColor();
                gg.drawString(font, label, textX, top + 6, color);
            }

            /** Recomputed live: deletions must refresh the counter. */
            private int count() {
                if (GROUP_ALL.equals(group)) {
                    return WaypointStore.all().size();
                }

                return (int) WaypointStore.all().stream()
                        .filter(wp -> wp.group().equals(group))
                        .count();
            }

            /** Gold for the selected group, gray when hidden, white else. */
            private int rowColor() {
                if (group.equals(selectedGroup)) {
                    return SELECTED_COLOR;
                }

                boolean visible = visibility == null || WaypointStore.isGroupVisible(group);
                return visible ? 0xFFFFFF : 0x808080;
            }

            @Override
            public @NotNull List<? extends GuiEventListener> children() {
                return visibility == null ? List.of() : List.of(visibility);
            }

            @Override
            public @NotNull List<? extends NarratableEntry> narratables() {
                return visibility == null ? List.of() : List.of(visibility);
            }
        }
    }

    // ------------------------------------------------------------------ waypoint list (right)

    /** Right panel: the selected group's waypoints + a creation row. */
    private class WaypointList extends ContainerObjectSelectionList<WaypointList.Row> {

        WaypointList(Minecraft mc, int x, int width, int height) {
            super(mc, width, height, RIGHT_LIST_TOP, WAYPOINT_ITEM_HEIGHT);
            setX(x);
        }

        void rebuild() {
            clearEntries();
            setScrollAmount(0);
            selectedWaypoints().forEach(wp -> addEntry(new WaypointRow(wp)));
            addEntry(new CreateRow());
        }

        @Override
        public int getRowWidth() {
            return width - 16;
        }

        @Override
        protected int getScrollbarPosition() {
            return getX() + width - 6;
        }

        /** Base of both row kinds (waypoint / creation). */
        abstract class Row extends ContainerObjectSelectionList.Entry<Row> {}

        /** Last row of the list: creates a waypoint in the current group. */
        class CreateRow extends Row {

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                createWaypoint();
                return true;
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
                Component label = Component.literal("+ ").append(Component.translatable(Lang.WAYPOINT_CREATE));
                int color = hovering ? 0xFFFFFF : 0x80E080;
                gg.drawCenteredString(font, label, left + width / 2, top + 6, color);
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

        /** One waypoint: color swatch, name + info, action buttons. */
        class WaypointRow extends Row {

            private final Waypoint waypoint;
            private final List<Button> buttons = new ArrayList<>();
            private final Button visibility;

            WaypointRow(Waypoint waypoint) {
                this.waypoint = waypoint;
                Minecraft mc = Minecraft.getInstance();
                this.visibility = Button.builder(visibilityLabel(), b -> {
                            Waypoint current = WaypointStore.get(waypoint.id());
                            if (current != null) {
                                WaypointStore.update(current.withVisible(!current.visible()));
                            }
                            b.setMessage(visibilityLabel());
                        })
                        .size(30, 20)
                        .tooltip(Tooltip.create(Component.translatable(Lang.WAYPOINTS_TOGGLE)))
                        .build();
                buttons.add(visibility);
                // Banner waypoints are read-only (position/name/color are
                // derived from the physical banner): no Edit, no Delete —
                // removing them means breaking the banner in the world.
                boolean banner = Waypoint.SOURCE_BANNER.equals(waypoint.source());
                if (!banner) {
                    buttons.add(Button.builder(
                                    Component.translatable(Lang.WAYPOINTS_EDIT),
                                    b -> mc.setScreen(new WaypointEditScreen(WaypointListScreen.this, waypoint)))
                            .size(40, 20)
                            .build());
                }
                if (mc.player != null && mc.player.hasPermissions(2)) {
                    Button tp = Button.builder(
                                    Component.translatable(Lang.WAYPOINTS_TELEPORT), b -> teleportTo(waypoint))
                            .size(26, 20)
                            .tooltip(Tooltip.create(Component.translatable(Lang.CONTEXT_TELEPORT)))
                            .build();
                    tp.active = sameDimension(mc, waypoint);
                    buttons.add(tp);
                }
                if (!banner) {
                    Button delete = Button.builder(Component.literal("x").withStyle(s -> s.withColor(0xFF6060)), b -> {
                                WaypointStore.remove(waypoint.id());
                                rebuild();
                            })
                            .size(20, 20)
                            .tooltip(Tooltip.create(Component.translatable(Lang.WAYPOINT_DELETE)))
                            .build();
                    buttons.add(delete);
                }
            }

            private Component visibilityLabel() {
                Waypoint current = WaypointStore.get(waypoint.id());
                boolean visible = current == null ? waypoint.visible() : current.visible();
                String key = visible ? Lang.WAYPOINTS_ON : Lang.WAYPOINTS_OFF;
                return Component.translatable(key);
            }

            private boolean sameDimension(Minecraft mc, Waypoint wp) {
                if (mc.level == null) {
                    return false;
                }

                return wp.dimension().equals(mc.level.dimension().location());
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
                int x = left + width;
                for (int i = buttons.size() - 1; i >= 0; i--) {
                    Button b = buttons.get(i);
                    x -= b.getWidth();
                    b.setPosition(x, top);
                    b.render(gg, mouseX, mouseY, partialTick);
                    x -= 2;
                }

                gg.fill(left + 2, top + 4, left + 14, top + 16, 0xFF000000 | waypoint.colorRgb());
                boolean shown = WaypointStore.isShown(waypoint);
                int nameColor = shown ? 0xFFFFFF : 0x808080;
                gg.drawString(font, waypoint.name(), left + 20, top + 1, nameColor);
                StringBuilder info = new StringBuilder();
                info.append(waypoint.x())
                        .append(", ")
                        .append(waypoint.y())
                        .append(", ")
                        .append(waypoint.z())
                        .append("  ")
                        .append(dimensionLabel(waypoint.dimension()));
                if (GROUP_ALL.equals(selectedGroup)) {
                    info.append("  ■ ").append(groupLabel(waypoint.group()).getString());
                }
                if (Waypoint.SOURCE_BANNER.equals(waypoint.source())) {
                    info.append("  · ")
                            .append(Component.translatable(Lang.WAYPOINT_TYPE_BANNER)
                                    .getString());
                }
                gg.drawString(font, info.toString(), left + 20, top + 11, 0x888888);
            }

            @Override
            public @NotNull List<? extends GuiEventListener> children() {
                return buttons;
            }

            @Override
            public @NotNull List<? extends NarratableEntry> narratables() {
                return buttons;
            }
        }
    }

    // ------------------------------------------------------------------ modals

    /** Styled modal asking for a group name (creation and rename). */
    private static class GroupNameScreen extends ModalScreen {

        private final String initial;
        private final Consumer<String> onConfirm;
        private EditBox nameBox;

        GroupNameScreen(Screen parent, Component title, String initial, Consumer<String> onConfirm) {
            super(title, parent);
            this.initial = initial;
            this.onConfirm = onConfirm;
        }

        @Override
        protected int contentHeight() {
            return 48;
        }

        @Override
        protected void init() {
            super.init();
            nameBox = new EditBox(font, panelLeft, contentTop, PANEL_WIDTH, 20, Component.empty());
            nameBox.setValue(initial);
            nameBox.setMaxLength(32);
            addRenderableWidget(nameBox);
            setInitialFocus(nameBox);

            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> confirm())
                    .bounds(panelLeft, contentTop + 28, PANEL_WIDTH / 2 - 2, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                    .bounds(panelLeft + PANEL_WIDTH / 2 + 2, contentTop + 28, PANEL_WIDTH / 2 - 2, 20)
                    .build());
        }

        private void confirm() {
            onConfirm.accept(nameBox.getValue());
            onClose();
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirm();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    /** Styled group deletion confirmation (replaces vanilla ConfirmScreen). */
    private static class GroupDeleteScreen extends ModalScreen {

        private final Component message;
        private final Runnable onDelete;

        GroupDeleteScreen(Screen parent, Component message, Runnable onDelete) {
            super(Component.translatable(Lang.WAYPOINTS_GROUP_DELETE_TITLE), parent);
            this.message = message;
            this.onDelete = onDelete;
        }

        @Override
        protected int contentHeight() {
            return messageLines().size() * 10 + 12 + 20;
        }

        @Override
        protected void init() {
            super.init();
            int buttonsY = contentTop + messageLines().size() * 10 + 12;
            addRenderableWidget(Button.builder(
                            Component.translatable(Lang.WAYPOINTS_GROUP_DELETE).withStyle(s -> s.withColor(0xFF6060)),
                            b -> {
                                onDelete.run();
                                onClose();
                            })
                    .bounds(panelLeft, buttonsY, PANEL_WIDTH / 2 - 2, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                    .bounds(panelLeft + PANEL_WIDTH / 2 + 2, buttonsY, PANEL_WIDTH / 2 - 2, 20)
                    .build());
        }

        @Override
        public void render(@NotNull GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            super.render(gg, mouseX, mouseY, partialTick);
            int y = contentTop;
            for (FormattedCharSequence line : messageLines()) {
                gg.drawCenteredString(font, line, width / 2, y, 0xC8C8C8);
                y += 10;
            }
        }

        private List<FormattedCharSequence> messageLines() {
            return font.split(message, PANEL_WIDTH);
        }
    }
}
