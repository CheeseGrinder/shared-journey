package fr.cheesegrinder.sharedjourney.client.gui.util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Custom-rendered context menu (JourneyMap-style flat panel with hover
 * highlight) replacing stacked vanilla buttons, with one submenu level.
 * The owning screen renders it last (always on top) and forwards left
 * clicks; a click outside both panels is not consumed, letting the owner
 * close the menu.
 */
public final class ContextMenu {

    /** One row: an action, or a submenu when {@code children} is set. */
    public record Item(Component label, Runnable action, List<Item> children) {

        public static Item action(Component label, Runnable action) {
            return new Item(label, action, null);
        }

        public static Item submenu(Component label, List<Item> children) {
            return new Item(label, null, children);
        }

        private boolean hasChildren() {
            return children != null && !children.isEmpty();
        }
    }

    private static final int ROW_HEIGHT = 14;
    private static final int PAD_X = 7;
    private static final int PAD_Y = 4;
    private static final int MIN_WIDTH = 110;
    /** Space reserved on the right for the submenu ">" marker. */
    private static final int ARROW_WIDTH = 10;

    private final Font font;
    /** Non-clickable header row (the clicked block position); nullable. */
    private final Component title;

    private final List<Item> items;
    /** Closes the menu owner-side; run before a row's action. */
    private final Runnable close;

    private final int screenW;
    private final int screenH;
    private final int x;
    private final int y;
    private final int w;
    private final int h;

    // Open submenu (one level), anchored to its parent row.
    private Item openSubmenu;
    private int subX;
    private int subY;
    private int subW;
    private int subH;

    public ContextMenu(
            Font font,
            Component title,
            List<Item> items,
            double mouseX,
            double mouseY,
            int screenW,
            int screenH,
            Runnable close) {
        this.font = font;
        this.title = title;
        this.items = items;
        this.close = close;
        this.screenW = screenW;
        this.screenH = screenH;
        this.w = panelWidth(items, title);
        this.h = panelHeight(items.size() + (title == null ? 0 : 1));
        this.x = clamp((int) mouseX, screenW - w - 4);
        this.y = clamp((int) mouseY, screenH - h - 4);
    }

    // ------------------------------------------------------------------ input

    /**
     * Left click. Consumes clicks on either panel (running the hit row's
     * action after closing the menu); returns false on a miss so the
     * owner can dismiss the menu.
     */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (openSubmenu != null && contains(mouseX, mouseY, subX, subY, subW, subH)) {
            int index = rowAt(subY, openSubmenu.children().size(), mouseY, false);
            clickRow(openSubmenu.children(), index, subY + PAD_Y);
            return true;
        }

        if (contains(mouseX, mouseY, x, y, w, h)) {
            clickRow(items, rowAt(y, items.size(), mouseY, title != null), y + titleOffset());
            return true;
        }
        return false;
    }

    private void clickRow(List<Item> rows, int index, int rowsTop) {
        if (index < 0 || index >= rows.size()) {
            return;
        }

        Item item = rows.get(index);
        if (item.hasChildren()) {
            toggleSubmenu(item, rowsTop + index * ROW_HEIGHT);
            return;
        }

        close.run();
        item.action().run();
    }

    private void toggleSubmenu(Item item, int rowTop) {
        if (openSubmenu == item) {
            openSubmenu = null;
            return;
        }

        openSubmenu = item;
        subW = panelWidth(item.children(), null);
        subH = panelHeight(item.children().size());
        subX = x + w + 2;
        if (subX + subW > screenW - 4) {
            subX = x - subW - 2;
        }
        subY = clamp(rowTop - PAD_Y, screenH - subH - 4);
    }

    // ------------------------------------------------------------------ render

    public void render(GuiGraphics gg, int mouseX, int mouseY) {
        gg.pose().pushPose();
        // Above everything the map draws, including the legend (z=300)
        // and vanilla tooltips (z=400).
        gg.pose().translate(0, 0, 500);
        drawPanel(gg, x, y, w, h);
        int rowY = y + PAD_Y;
        if (title != null) {
            gg.drawString(font, title, x + PAD_X, rowY + 3, UiColors.TEXT_TITLE);
            rowY += ROW_HEIGHT;
        }

        drawRows(gg, items, x, rowY, w, mouseX, mouseY, true);
        if (openSubmenu != null) {
            drawPanel(gg, subX, subY, subW, subH);
            drawRows(gg, openSubmenu.children(), subX, subY + PAD_Y, subW, mouseX, mouseY, false);
        }
        gg.pose().popPose();
    }

    private void drawPanel(GuiGraphics gg, int px, int py, int pw, int ph) {
        gg.fill(px, py, px + pw, py + ph, UiColors.MENU_BACKGROUND);
        gg.renderOutline(px, py, pw, ph, UiColors.MENU_BORDER);
    }

    private void drawRows(
            GuiGraphics gg, List<Item> rows, int panelX, int top, int panelW, int mouseX, int mouseY, boolean root) {
        for (int i = 0; i < rows.size(); i++) {
            Item item = rows.get(i);
            int rowTop = top + i * ROW_HEIGHT;
            boolean hovered = contains(mouseX, mouseY, panelX, rowTop, panelW, ROW_HEIGHT);
            // The row whose submenu is open stays highlighted (JM-like).
            boolean marked = hovered || (root && item == openSubmenu);
            if (marked) {
                gg.fill(panelX + 1, rowTop, panelX + panelW - 1, rowTop + ROW_HEIGHT, UiColors.ROW_HIGHLIGHT);
            }

            gg.drawString(font, item.label(), panelX + PAD_X, rowTop + 3, marked ? UiColors.TEXT_HOVER : UiColors.TEXT);
            if (item.hasChildren()) {
                gg.drawString(
                        font,
                        ">",
                        panelX + panelW - PAD_X - 4,
                        rowTop + 3,
                        marked ? UiColors.TEXT_HOVER : UiColors.TEXT);
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    /** Row index under the mouse; -1 on the title row or the paddings. */
    private int rowAt(int panelTop, int count, double mouseY, boolean titled) {
        int index = Math.floorDiv((int) (mouseY - panelTop - PAD_Y), ROW_HEIGHT);
        if (titled) {
            index--;
        }

        return index >= count ? -1 : index;
    }

    private int titleOffset() {
        return PAD_Y + (title == null ? 0 : ROW_HEIGHT);
    }

    private int panelWidth(List<Item> rows, Component header) {
        int max = MIN_WIDTH;
        for (Item item : rows) {
            int arrow = item.hasChildren() ? ARROW_WIDTH : 0;
            max = Math.max(max, font.width(item.label()) + 2 * PAD_X + arrow);
        }

        if (header != null) {
            max = Math.max(max, font.width(header) + 2 * PAD_X);
        }
        return max;
    }

    private int panelHeight(int rowCount) {
        return rowCount * ROW_HEIGHT + 2 * PAD_Y;
    }

    private static int clamp(int value, int max) {
        return Math.max(4, Math.min(value, max));
    }

    private static boolean contains(double mx, double my, int px, int py, int pw, int ph) {
        return mx >= px && mx < px + pw && my >= py && my < py + ph;
    }
}
