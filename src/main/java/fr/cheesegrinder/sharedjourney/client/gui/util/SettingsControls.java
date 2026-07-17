package fr.cheesegrinder.sharedjourney.client.gui.util;

import fr.cheesegrinder.sharedjourney.common.util.Lang;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Factories that build {@link OptionList.OptionRow}s for settings-style
 * screens: on/off toggles, value cyclers, int sliders, and section
 * headers/messages. Extracted from {@code MapSettingsScreen} so screens
 * declare their content by calling these instead of hand-wiring widgets.
 */
public final class SettingsControls {

    private static final int CONTROL_WIDTH = 140;

    private SettingsControls() {}

    /** Section header row. */
    public static OptionList.OptionRow header(Component label) {
        return new OptionList.HeaderRow(label);
    }

    /** Plain centered message row. */
    public static OptionList.OptionRow info(Component label) {
        return new OptionList.InfoRow(label);
    }

    /** Client config toggle: applied live, saved once on close. */
    public static OptionList.OptionRow configToggle(
            String labelKey, String configKey, ModConfigSpec.BooleanValue value) {
        return toggle(Component.translatable(labelKey), Lang.configTooltip(configKey), value::get, value::set);
    }

    public static OptionList.OptionRow toggle(
            Component label, String tooltipKey, Supplier<Boolean> get, Consumer<Boolean> set) {
        Button b = Button.builder(onOff(get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    btn.setMessage(onOff(now));
                })
                .size(48, 20)
                .build();
        return row(label, tooltipKey, b);
    }

    public static <T> OptionList.OptionRow cycle(
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

    public static OptionList.OptionRow intSlider(
            Component label,
            String tooltipKey,
            int min,
            int max,
            int current,
            IntFunction<Component> display,
            IntConsumer set) {
        return row(label, tooltipKey, new IntSlider(min, max, current, display, set));
    }

    private static Component onOff(boolean on) {
        String key = on ? Lang.SETTINGS_ON : Lang.SETTINGS_OFF;
        return Component.translatable(key).withStyle(s -> s.withColor(on ? 0x7FD37F : 0xD37F7F));
    }

    /** Attaches the description tooltip to the control, then wraps the row. */
    private static OptionList.OptionRow row(Component label, String tooltipKey, AbstractWidget control) {
        if (tooltipKey != null) {
            control.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        }

        return new OptionList.WidgetRow(label, control);
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
}
