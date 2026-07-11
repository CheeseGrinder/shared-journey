package fr.cheesegrinder.sharedjourney.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.NotNull;

/**
 * Base of the small centered modals (group name prompt, delete
 * confirmation): same dark panel style as the waypoint edit form, title
 * above the content, close returns to the parent screen.
 */
abstract class ModalScreen extends Screen {

    protected static final int PANEL_WIDTH = 220;

    private final Screen parent;

    protected int panelLeft;
    protected int contentTop;

    ModalScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** Height of the widget/content area (font is available here). */
    protected abstract int contentHeight();

    @Override
    protected void init() {
        panelLeft = width / 2 - PANEL_WIDTH / 2;
        contentTop = height / 2 - contentHeight() / 2;
    }

    /** Dimmed world + styled panel; runs before the widgets. */
    @Override
    public void renderBackground(@NotNull GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(gg, mouseX, mouseY, partialTick);
        int top = contentTop - 28;
        int bottom = contentTop + contentHeight() + 10;
        gg.fill(panelLeft - 12, top, panelLeft + PANEL_WIDTH + 12, bottom, UiColors.PANEL_BACKGROUND);
        gg.renderOutline(panelLeft - 12, top, PANEL_WIDTH + 24, bottom - top, UiColors.PANEL_BORDER);
    }

    @Override
    public void render(@NotNull GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.render(gg, mouseX, mouseY, partialTick);
        gg.drawCenteredString(font, title, width / 2, contentTop - 20, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
