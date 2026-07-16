package fr.cheesegrinder.sharedjourney.api.client.event;

import fr.cheesegrinder.sharedjourney.api.client.MapView;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Posted on NeoForge.EVENT_BUS (CLIENT side only) every time the
 * fullscreen map (re)builds its widgets — on open AND on every window
 * resize. Listeners contribute declarative button specs; SharedJourney
 * owns the layout, the drawing and the click routing. Contributed
 * buttons live in DEDICATED zones, visually separate from the internal
 * toolbars, so players can tell a SharedJourney button from an addon
 * one. Buttons are laid out in listener/registration order:
 * {@link Slot#TOP_LEFT} buttons form a horizontal cluster in the
 * top-left corner (the addon zone, next to bridged overlay widgets),
 * {@link Slot#TOP_RIGHT} buttons are right-aligned, marching left from
 * the Close button.
 *
 * <p>Toggle buttons ({@link ToolbarButton#toggle}) get the standard
 * selected outline, re-evaluated after any toolbar press and on every
 * rebuild — external state changes while the map is open won't refresh
 * the outline until then.
 *
 * <p>Deliberately NOT exposed (YAGNI until a consumer needs them):
 * absolute positioning, custom widget classes, texture icons (ItemStack
 * only for now), keyboard shortcuts, overflow handling.
 */
public class FullMapToolbarEvent extends Event {

    /** Where the button is appended (SharedJourney picks the exact x/y). */
    public enum Slot {
        /** Addon cluster in the top-left corner, marching right. */
        TOP_LEFT,
        /** Right-aligned in the top bar, left of the Close button. */
        TOP_RIGHT
    }

    /** Declarative button spec; {@code selected} == null means a plain action button. */
    public record ToolbarButton(
            Slot slot, ItemStack icon, Component tooltip, Runnable onPress, BooleanSupplier selected) {

        public ToolbarButton {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(tooltip, "tooltip");
            Objects.requireNonNull(onPress, "onPress");
        }

        /** Plain action button (no selected state). */
        public static ToolbarButton action(Slot slot, ItemStack icon, Component tooltip, Runnable onPress) {
            return new ToolbarButton(slot, icon, tooltip, onPress, null);
        }

        /** Toggle button: {@code selected} drives the highlight outline. */
        public static ToolbarButton toggle(
                Slot slot, ItemStack icon, Component tooltip, Runnable onPress, BooleanSupplier selected) {
            Objects.requireNonNull(selected, "selected");
            return new ToolbarButton(slot, icon, tooltip, onPress, selected);
        }
    }

    private final MapView view;
    private final List<ToolbarButton> buttons = new ArrayList<>();

    public FullMapToolbarEvent(MapView view) {
        this.view = view;
    }

    public MapView getView() {
        return view;
    }

    /** Contributes a button; layout follows the order of contribution. */
    public void addButton(ToolbarButton button) {
        Objects.requireNonNull(button, "button");
        buttons.add(button);
    }

    /** Contributed buttons in contribution order (consumed by the screen). */
    public List<ToolbarButton> getButtons() {
        return Collections.unmodifiableList(buttons);
    }
}
