package fr.cheesegrinder.sharedjourney.client.gui.screen;

import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.gui.util.UiColors;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;
import fr.cheesegrinder.sharedjourney.common.util.Lang;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Waypoint creation/edition form (JourneyMap-style): name, position,
 * group (text with autocompletion over the existing groups; a new name
 * creates the group), color (hex field, palette, random, HSB picker),
 * visibility and type. Also used for creation (creating=true).
 */
public class WaypointEditScreen extends Screen {

    private static final int[] PALETTE = {
        0xFF4040, 0xFF9040, 0xFFE040, 0x60FF60, 0x40E0D0,
        0x4090FF, 0x9060FF, 0xFF60C0, 0xFFFFFF, 0x909090
    };

    private static final int FORM_WIDTH = 240;
    private static final int ROW_STEP = 32;

    // Group autocompletion dropdown.
    private static final int MAX_GROUP_SUGGESTIONS = 5;
    private static final int SUGGESTION_ROW = 12;

    // HSB picker (JourneyMap-like): saturation/value square + hue strip.
    private static final int PICKER_SIZE = 96;
    private static final int HUE_STRIP_X = PICKER_SIZE + 8;
    private static final int HUE_STRIP_W = 14;
    private static final int PICKER_ROW = PICKER_SIZE + 8;

    private final Screen parent;
    private final boolean creating;
    private final Waypoint original;

    // Form state not carried by an EditBox.
    private int color;
    private boolean visible;
    private Waypoint.Type type;
    /** Selected group (cycles over the existing assignable groups). */
    private String group;

    // Picker state. HSB is kept authoritative while dragging the picker
    // (recomputing it from RGB would reset the hue on black/gray colors).
    private float hue;
    private float sat;
    private float val;
    /** 0 = none, 1 = square, 2 = hue strip. */
    private int pickerDrag;
    /** True while the hex box is synced programmatically (no HSB reset). */
    private boolean syncingHex;

    private int pickerX;
    private int pickerY;

    private EditBox nameBox;
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private EditBox groupBox;
    private EditBox colorBox;

    /** Autocomplete suggestions shown under the focused group field. */
    private final List<String> groupSuggestions = new ArrayList<>();
    /** True while the group box is synced programmatically. */
    private boolean syncingGroup;

    /** Panel top, recomputed in init() (kept for render()). */
    private int panelTop;

    public WaypointEditScreen(Screen parent, Waypoint waypoint) {
        this(parent, waypoint, false);
    }

    /**
     * Opens the creation form for a waypoint at the player's position
     * (waypoint manager row, in-game shortcut). Null parent: closing the
     * form returns to the game.
     */
    public static void openCreateAtPlayer(Screen parent, String group) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        BlockPos pos = mc.player.blockPosition();
        Waypoint wp = Waypoint.create(
                        "X:" + pos.getX() + " Z:" + pos.getZ(),
                        mc.level.dimension().location(),
                        pos,
                        0xFFFFFF & ThreadLocalRandom.current().nextInt(),
                        Waypoint.SOURCE_USER)
                .withGroup(group);
        mc.setScreen(new WaypointEditScreen(parent, wp, true));
    }

    public WaypointEditScreen(Screen parent, Waypoint waypoint, boolean creating) {
        super(creating ? Component.translatable(Lang.WAYPOINT_CREATE) : Component.translatable(Lang.WAYPOINT_EDIT));
        this.parent = parent;
        this.original = waypoint;
        this.creating = creating;
        this.color = waypoint.colorRgb();
        this.visible = waypoint.visible();
        this.type = waypoint.type();
        this.group = waypoint.group();
        updateHsbFromColor();
    }

    @Override
    protected void init() {
        int left = width / 2 - FORM_WIDTH / 2;
        panelTop = Math.max(24, height / 2 - 162);
        int y = panelTop + 14;

        // Row 1: name.
        nameBox = textBox(left, y, FORM_WIDTH, valueOr(nameBox, original.name()));
        nameBox.setMaxLength(48);
        setInitialFocus(nameBox);
        y += ROW_STEP;

        // Row 2: position (X / Y / Z).
        xBox = intBox(left, y, valueOr(xBox, Integer.toString(original.x())));
        yBox = intBox(left + 81, y, valueOr(yBox, Integer.toString(original.y())));
        zBox = intBox(left + 162, y, valueOr(zBox, Integer.toString(original.z())));
        y += ROW_STEP;

        // Row 3: group — free text with autocompletion over the existing
        // groups; a new name creates the group on save. Locked to the
        // reserved group while the type is PUBLIC.
        groupBox = textBox(left, y, FORM_WIDTH, "");
        groupBox.setMaxLength(32);
        groupBox.setTooltip(Tooltip.create(Component.translatable(Lang.WAYPOINT_GROUP_TOOLTIP)));
        groupBox.setResponder(this::onGroupTyped);
        refreshGroupField();
        y += ROW_STEP;

        // Row 4: color — hex field, random button, live preview in render().
        colorBox = textBox(left, y, 74, String.format("#%06X", color));
        colorBox.setMaxLength(7);
        colorBox.setResponder(this::onColorTyped);
        addRenderableWidget(Button.builder(Component.translatable(Lang.WAYPOINT_RANDOM), b -> {
                    setColor(0xFFFFFF & ThreadLocalRandom.current().nextInt());
                })
                .bounds(left + 78, y, 70, 18)
                .build());
        y += 22;

        // Row 5: palette swatches (colored in render(), on top of the buttons).
        for (int i = 0; i < PALETTE.length; i++) {
            final int swatch = PALETTE[i];
            addRenderableWidget(Button.builder(Component.empty(), b -> setColor(swatch))
                    .bounds(left + i * 24, y, 22, 18)
                    .tooltip(Tooltip.create(Component.literal(String.format("#%06X", swatch))))
                    .build());
        }
        y += 26;

        // Row 6: HSB picker, drawn and handled manually (render/mouse*).
        pickerX = left;
        pickerY = y;
        y += PICKER_ROW;

        // Row 7: visibility + type.
        addRenderableWidget(Button.builder(visibilityLabel(), b -> {
                    visible = !visible;
                    b.setMessage(visibilityLabel());
                })
                .bounds(left, y, 118, 20)
                .build());
        addRenderableWidget(Button.builder(typeLabel(), b -> {
                    type = nextType(type);
                    b.setMessage(typeLabel());
                    refreshGroupField();
                })
                .bounds(left + 122, y, 118, 20)
                .tooltip(Tooltip.create(Component.translatable(Lang.WAYPOINT_TYPE_TOOLTIP)))
                .build());
        y += 26;

        // Bottom row: Done / (Delete) / Cancel.
        if (creating) {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> save())
                    .bounds(left, y, 118, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> close())
                    .bounds(left + 122, y, 118, 20)
                    .build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> save())
                    .bounds(left, y, 76, 20)
                    .build());
            addRenderableWidget(Button.builder(
                            Component.translatable(Lang.WAYPOINT_DELETE).withStyle(s -> s.withColor(0xFF6060)), b -> {
                                WaypointStore.remove(original.id());
                                close();
                            })
                    .bounds(left + 82, y, 76, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> close())
                    .bounds(left + 164, y, 76, 20)
                    .build());
        }
    }

    // ------------------------------------------------------------------ widgets

    private EditBox textBox(int x, int y, int w, String value) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.empty());
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    /** Integer field (78 px): digits and a leading minus only. */
    private EditBox intBox(int x, int y, String value) {
        EditBox box = textBox(x, y, 78, value);
        box.setMaxLength(9);
        box.setFilter(s -> s.matches("-?\\d*"));
        return box;
    }

    /** init() reruns on resize: keep what the user already typed. */
    private static String valueOr(EditBox box, String fallback) {
        return box == null ? fallback : box.getValue();
    }

    private void onColorTyped(String text) {
        if (syncingHex) {
            return;
        }

        String hex = text.startsWith("#") ? text.substring(1) : text;
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            return;
        }

        color = Integer.parseInt(hex, 16);
        updateHsbFromColor();
    }

    /** Palette click / random: updates the state AND the hex field. */
    private void setColor(int rgb) {
        color = rgb & 0xFFFFFF;
        updateHsbFromColor();
        syncHexBox();
    }

    /** Picker interaction: HSB is the source, RGB and hex field follow. */
    private void applyPickerColor() {
        color = 0xFFFFFF & Mth.hsvToRgb(hue, sat, val);
        syncHexBox();
    }

    private void syncHexBox() {
        syncingHex = true;
        colorBox.setValue(String.format("#%06X", color));
        syncingHex = false;
    }

    /** RGB → HSB (external color changes: hex typed, palette, random). */
    private void updateHsbFromColor() {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        val = max;
        sat = max == 0 ? 0 : (max - min) / max;
        if (max == min) {
            hue = 0; // gray: arbitrary hue
            return;
        }

        float delta = max - min;
        float h;
        if (max == r) {
            h = (g - b) / delta % 6f;
        } else if (max == g) {
            h = (b - r) / delta + 2f;
        } else {
            h = (r - g) / delta + 4f;
        }
        hue = (h / 6f + 1f) % 1f;
    }

    // ------------------------------------------------------------------ group picker

    private void onGroupTyped(String text) {
        if (syncingGroup) {
            return;
        }

        group = text;
        refreshGroupSuggestions();
    }

    /** Existing assignable groups matching the typed text (capped). */
    private void refreshGroupSuggestions() {
        groupSuggestions.clear();
        String typed = group.trim().toLowerCase(Locale.ROOT);
        for (String option : WaypointStore.assignableGroups()) {
            if (groupSuggestions.size() >= MAX_GROUP_SUGGESTIONS) {
                break;
            }

            if (option.toLowerCase(Locale.ROOT).contains(typed) && !option.equals(group)) {
                groupSuggestions.add(option);
            }
        }
    }

    /** PUBLIC waypoints live in the reserved group: field locked. */
    private void refreshGroupField() {
        boolean isPublic = type == Waypoint.Type.PUBLIC;
        syncingGroup = true;
        groupBox.setValue(isPublic ? Waypoint.GROUP_PUBLIC : group);
        syncingGroup = false;
        groupBox.setEditable(!isPublic);
        groupSuggestions.clear();
        if (!isPublic) {
            refreshGroupSuggestions();
        }
    }

    private boolean suggestionsVisible() {
        return groupBox.isFocused() && type != Waypoint.Type.PUBLIC && !groupSuggestions.isEmpty();
    }

    /** Suggestion row under the mouse, -1 outside the dropdown. */
    private int suggestionIndexAt(double mouseX, double mouseY) {
        if (!suggestionsVisible()) {
            return -1;
        }

        int x = groupBox.getX();
        if (mouseX < x || mouseX >= x + FORM_WIDTH) {
            return -1;
        }

        int top = groupBox.getY() + groupBox.getHeight() + 3;
        int index = Math.floorDiv((int) (mouseY - top), SUGGESTION_ROW);
        if (index < 0 || index >= groupSuggestions.size()) {
            return -1;
        }
        return index;
    }

    /** Group to persist: reserved for PUBLIC, created when typed anew. */
    private String resolveGroup() {
        if (type == Waypoint.Type.PUBLIC) {
            return Waypoint.GROUP_PUBLIC;
        }

        String name = group.trim();
        if (name.isEmpty()) {
            return Waypoint.GROUP_DEFAULT;
        }

        if (!WaypointStore.groups().contains(name)) {
            WaypointStore.createGroup(name);
        }
        return name;
    }

    private Component visibilityLabel() {
        return Component.translatable(visible ? Lang.WAYPOINT_VISIBLE : Lang.WAYPOINT_HIDDEN);
    }

    private Component typeLabel() {
        return Component.translatable(Lang.WAYPOINT_TYPE, Component.translatable(Lang.waypointType(type)));
    }

    private static Waypoint.Type nextType(Waypoint.Type type) {
        Waypoint.Type[] values = Waypoint.Type.values();
        return values[(type.ordinal() + 1) % values.length];
    }

    // ------------------------------------------------------------------ save/close

    private void save() {
        String name = nameBox.getValue().isBlank()
                ? original.name()
                : nameBox.getValue().trim();
        Waypoint edited = new Waypoint(
                original.id(),
                name,
                original.dimension(),
                parsedInt(xBox, original.x()),
                parsedInt(yBox, original.y()),
                parsedInt(zBox, original.z()),
                color,
                original.source(),
                resolveGroup(),
                visible,
                type);
        if (creating) {
            WaypointStore.add(edited);
        } else {
            WaypointStore.update(edited);
        }

        close();
    }

    private static int parsedInt(EditBox box, int fallback) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void close() {
        Minecraft.getInstance().setScreen(parent);
    }

    // ------------------------------------------------------------------ render

    /** Dimmed world + structured panel; runs before the widgets. */
    @Override
    public void renderBackground(@NotNull GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(gg, mouseX, mouseY, partialTick);
        int left = width / 2 - FORM_WIDTH / 2;
        int bottom = panelTop + 14 + 3 * ROW_STEP + 22 + 26 + PICKER_ROW + 26 + 30;
        gg.fill(left - 12, panelTop - 26, left + FORM_WIDTH + 12, bottom, UiColors.PANEL_BACKGROUND);
        gg.renderOutline(left - 12, panelTop - 26, FORM_WIDTH + 24, bottom - panelTop + 26, UiColors.PANEL_BORDER);
    }

    @Override
    public void render(@NotNull GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.render(gg, mouseX, mouseY, partialTick);
        int left = width / 2 - FORM_WIDTH / 2;
        gg.drawCenteredString(font, title, width / 2, panelTop - 18, 0xFFFFFF);
        int y = panelTop + 14;
        drawLabel(gg, Lang.WAYPOINT_NAME, left, y);
        y += ROW_STEP;
        drawLabel(gg, Lang.WAYPOINT_POSITION, left, y);
        y += ROW_STEP;
        drawLabel(gg, Lang.WAYPOINT_GROUP, left, y);
        y += ROW_STEP;
        drawLabel(gg, Lang.WAYPOINT_COLOR, left, y);
        // Current color preview, right of the Random button.
        gg.fill(left + 154, y, left + FORM_WIDTH, y + 18, 0xFF000000 | color);
        gg.renderOutline(left + 154, y, FORM_WIDTH - 154, 18, 0xFF000000);
        y += 22;
        // Palette swatches painted over their (empty-label) buttons.
        for (int i = 0; i < PALETTE.length; i++) {
            gg.fill(left + i * 24 + 2, y + 2, left + i * 24 + 20, y + 16, 0xFF000000 | PALETTE[i]);
        }

        drawPicker(gg);
        drawGroupSuggestions(gg, mouseX, mouseY);
    }

    /** Autocomplete dropdown, floating over the rows below the field. */
    private void drawGroupSuggestions(GuiGraphics gg, int mouseX, int mouseY) {
        if (!suggestionsVisible()) {
            return;
        }

        int x = groupBox.getX();
        int y = groupBox.getY() + groupBox.getHeight() + 1;
        int h = groupSuggestions.size() * SUGGESTION_ROW + 4;
        gg.pose().pushPose();
        gg.pose().translate(0, 0, 200);
        gg.fill(x, y, x + FORM_WIDTH, y + h, UiColors.MENU_BACKGROUND);
        gg.renderOutline(x, y, FORM_WIDTH, h, UiColors.MENU_BORDER);
        int hovered = suggestionIndexAt(mouseX, mouseY);
        for (int i = 0; i < groupSuggestions.size(); i++) {
            int rowY = y + 2 + i * SUGGESTION_ROW;
            if (i == hovered) {
                gg.fill(x + 1, rowY, x + FORM_WIDTH - 1, rowY + SUGGESTION_ROW, UiColors.ROW_HIGHLIGHT);
            }

            gg.drawString(font, groupSuggestions.get(i), x + 5, rowY + 2, i == hovered ? 0xFFFFFF : 0xC8C8C8);
        }
        gg.pose().popPose();
    }

    /**
     * HSB picker: saturation (→) / value (↓) square for the current hue,
     * plus a vertical hue strip. Both are exact gradients: HSB→RGB is
     * linear in V at fixed H/S (columns) and piecewise linear in H at
     * S=V=1 (strip segments).
     */
    private void drawPicker(GuiGraphics gg) {
        int x = pickerX;
        int y = pickerY;
        for (int i = 0; i < PICKER_SIZE / 2; i++) {
            float s = i / (PICKER_SIZE / 2f - 1f);
            int top = 0xFF000000 | Mth.hsvToRgb(hue, s, 1.0f);
            gg.fillGradient(x + i * 2, y, x + i * 2 + 2, y + PICKER_SIZE, top, 0xFF000000);
        }
        gg.renderOutline(x - 1, y - 1, PICKER_SIZE + 2, PICKER_SIZE + 2, 0xFF000000);

        int hx = x + HUE_STRIP_X;
        int segment = PICKER_SIZE / 6;
        for (int k = 0; k < 6; k++) {
            int from = 0xFF000000 | Mth.hsvToRgb(k / 6f, 1.0f, 1.0f);
            int to = 0xFF000000 | Mth.hsvToRgb((k + 1) % 6 / 6f, 1.0f, 1.0f);
            gg.fillGradient(hx, y + k * segment, hx + HUE_STRIP_W, y + (k + 1) * segment, from, to);
        }
        gg.renderOutline(hx - 1, y - 1, HUE_STRIP_W + 2, PICKER_SIZE + 2, 0xFF000000);

        // Selection markers: square cursor + hue line.
        int mx = x + Math.round(sat * (PICKER_SIZE - 1));
        int my = y + Math.round((1f - val) * (PICKER_SIZE - 1));
        gg.renderOutline(mx - 2, my - 2, 5, 5, 0xFFFFFFFF);
        int hy = y + Math.round(hue * (PICKER_SIZE - 1));
        gg.fill(hx - 1, hy - 1, hx + HUE_STRIP_W + 1, hy + 1, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------ picker input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // The dropdown floats over the rows below: it wins over them.
        int suggestion = button == 0 ? suggestionIndexAt(mouseX, mouseY) : -1;
        if (suggestion >= 0) {
            group = groupSuggestions.get(suggestion);
            syncingGroup = true;
            groupBox.setValue(group);
            syncingGroup = false;
            groupSuggestions.clear();
            groupBox.setFocused(false);
            return true;
        }

        if (button == 0 && pickAt(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && pickerDrag != 0) {
            applyPick(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        pickerDrag = 0;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Starts a picker interaction if the press lands on it. */
    private boolean pickAt(double mouseX, double mouseY) {
        boolean inY = mouseY >= pickerY && mouseY < pickerY + PICKER_SIZE;
        if (inY && mouseX >= pickerX && mouseX < pickerX + PICKER_SIZE) {
            pickerDrag = 1;
        } else if (inY && mouseX >= pickerX + HUE_STRIP_X && mouseX < pickerX + HUE_STRIP_X + HUE_STRIP_W) {
            pickerDrag = 2;
        } else {
            return false;
        }

        applyPick(mouseX, mouseY);
        return true;
    }

    private void applyPick(double mouseX, double mouseY) {
        float fy = Math.clamp((float) (mouseY - pickerY) / (PICKER_SIZE - 1), 0.0f, 1.0f);
        if (pickerDrag == 1) {
            sat = Math.clamp((float) (mouseX - pickerX) / (PICKER_SIZE - 1), 0.0f, 1.0f);
            val = 1f - fy;
        } else {
            hue = fy;
        }

        applyPickerColor();
    }

    /** Field label, drawn just above its row. */
    private void drawLabel(GuiGraphics gg, String key, int x, int y) {
        gg.drawString(font, Component.translatable(key), x, y - 10, 0xA0A0A0);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
