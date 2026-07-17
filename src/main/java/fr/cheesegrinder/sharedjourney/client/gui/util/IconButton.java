package fr.cheesegrinder.sharedjourney.client.gui.util;

import fr.cheesegrinder.sharedjourney.client.render.WaypointIcons;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.NotNull;

/**
 * Square icon button (JourneyMap action bar style) with a tooltip and
 * a "selected" state (highlighted outline) for toggles and the active layer.
 * The icon is either an item stack or a tinted grayscale sprite
 * (same tinting rules as {@link WaypointIcons}).
 */
public class IconButton extends Button {

    /** Selected-state accent (gold, same hue as the manager's selection). */
    private static final int SELECTED_BORDER = 0xFFFFD770;

    private static final int SELECTED_FILL = 0x38FFD770;

    private final ItemStack icon;
    private final ResourceLocation sprite;
    private final int spriteTint;
    private boolean selected;

    public IconButton(int x, int y, int size, ItemStack icon, Component tooltip, OnPress onPress) {
        super(x, y, size, size, Component.empty(), onPress, DEFAULT_NARRATION);
        this.icon = icon;
        this.sprite = null;
        this.spriteTint = 0xFFFFFF;
        setTooltip(Tooltip.create(tooltip));
    }

    public IconButton(int x, int y, int size, ResourceLocation sprite, Component tooltip, OnPress onPress) {
        this(x, y, size, sprite, 0xFFFFFF, tooltip, onPress);
    }

    public IconButton(
            int x, int y, int size, ResourceLocation sprite, int tintRgb, Component tooltip, OnPress onPress) {
        super(x, y, size, size, Component.empty(), onPress, DEFAULT_NARRATION);
        this.icon = null;
        this.sprite = sprite;
        this.spriteTint = tintRgb;
        setTooltip(Tooltip.create(tooltip));
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    protected void renderWidget(@NotNull GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(gg, mouseX, mouseY, partialTick);
        if (selected) {
            // Tinted underlay below the icon so the active state reads at
            // a glance (the old thin light-blue outline was near-invisible
            // against the vanilla button texture).
            gg.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, SELECTED_FILL);
        }

        if (sprite != null) {
            WaypointIcons.draw(gg, sprite, getX() + width / 2.0f, getY() + height / 2.0f, spriteTint, 1.0f);
        } else {
            gg.renderItem(icon, getX() + (width - 16) / 2, getY() + (height - 16) / 2);
        }

        if (selected) {
            // Border drawn after the icon so it stays unbroken.
            gg.renderOutline(getX(), getY(), width, height, SELECTED_BORDER);
        }
    }
}
