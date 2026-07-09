package fr.cheesegrinder.sharedjourney.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.NotNull;

/**
 * Square item-icon button (JourneyMap action bar style) with a tooltip and
 * a "selected" state (highlighted outline) for toggles and the active layer.
 */
public class IconButton extends Button {

    private final ItemStack icon;
    private boolean selected;

    public IconButton(int x, int y, int size, ItemStack icon, Component tooltip, OnPress onPress) {
        super(x, y, size, size, Component.empty(), onPress, DEFAULT_NARRATION);
        this.icon = icon;
        setTooltip(Tooltip.create(tooltip));
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    protected void renderWidget(@NotNull GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(gg, mouseX, mouseY, partialTick);
        gg.renderItem(icon, getX() + (width - 16) / 2, getY() + (height - 16) / 2);
        if (selected) {
            gg.renderOutline(getX(), getY(), width, height, 0xFF7FD3FF);
        }
    }
}
