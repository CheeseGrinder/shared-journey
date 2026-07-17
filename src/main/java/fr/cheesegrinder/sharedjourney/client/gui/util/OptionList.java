package fr.cheesegrinder.sharedjourney.client.gui.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Reusable scrollable option list for settings-style screens: a column of
 * {@link OptionRow}s (label + control, section header, or plain message),
 * rebuilt in place when the content changes. Extracted from
 * {@code MapSettingsScreen} so any screen can reuse it; the row factories
 * live in {@link SettingsControls}.
 */
public class OptionList extends ContainerObjectSelectionList<OptionList.OptionRow> {

    private static final int ROW_HEIGHT = 24;
    private static final int ROW_WIDTH = 380;

    public OptionList(Minecraft mc, int width, int height, int y) {
        super(mc, width, height, y, ROW_HEIGHT);
    }

    public void rebuild(List<OptionRow> rows) {
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

    /** Base of every settings row. */
    public abstract static class OptionRow extends ContainerObjectSelectionList.Entry<OptionRow> {}

    /** Label on the left, one control (toggle/slider/cycle) on the right. */
    static class WidgetRow extends OptionRow {

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
            gg.drawString(Minecraft.getInstance().font, label, left, top + 6, UiColors.TEXT);
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
    static class HeaderRow extends OptionRow {

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
            gg.drawCenteredString(Minecraft.getInstance().font, label, left + width / 2, top + 8, UiColors.TEXT_TITLE);
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
    static class InfoRow extends OptionRow {

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
            gg.drawCenteredString(Minecraft.getInstance().font, label, left + width / 2, top + 8, 0xAAAAAA);
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
}
