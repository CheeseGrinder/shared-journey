package fr.cheesegrinder.sharedjourney.client.gui;

import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Édition d'un waypoint (spec §6.2) : nom, couleur (palette), visibilité,
 * suppression. Sert aussi à la création (creating=true).
 */
public class WaypointEditScreen extends Screen {

    private static final int[] PALETTE = {
        0xFF4040, 0xFF9040, 0xFFE040, 0x60FF60, 0x40E0D0,
        0x4090FF, 0x9060FF, 0xFF60C0, 0xFFFFFF, 0x909090
    };

    private final Screen parent;
    private Waypoint waypoint;
    private final boolean creating;
    private EditBox nameBox;

    public WaypointEditScreen(Screen parent, Waypoint waypoint) {
        this(parent, waypoint, false);
    }

    public WaypointEditScreen(Screen parent, Waypoint waypoint, boolean creating) {
        super(Component.translatable(creating ? "sharedjourney.waypoint.create" : "sharedjourney.waypoint.edit"));
        this.parent = parent;
        this.waypoint = waypoint;
        this.creating = creating;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = height / 2 - 40;

        nameBox = new EditBox(font, cx - 100, y, 200, 20, Component.literal("Nom"));
        nameBox.setValue(waypoint.name());
        nameBox.setMaxLength(48);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        // Palette de couleurs
        int px = cx - (PALETTE.length * 22 - 2) / 2;
        for (int i = 0; i < PALETTE.length; i++) {
            final int color = PALETTE[i];
            addRenderableWidget(Button.builder(Component.empty(), b -> waypoint = waypoint.withColor(color))
                    .bounds(px + i * 22, y + 28, 20, 20)
                    .tooltip(Tooltip.create(Component.literal(String.format("#%06X", color))))
                    .build());
        }

        // Visibilité + type (Dimension / Global / Temporaire)
        addRenderableWidget(Button.builder(visibilityLabel(), b -> {
                    waypoint = waypoint.withVisible(!waypoint.visible());
                    b.setMessage(visibilityLabel());
                })
                .bounds(cx - 100, y + 56, 97, 20)
                .build());
        addRenderableWidget(Button.builder(typeLabel(), b -> {
                    waypoint = waypoint.withType(nextType(waypoint.type()));
                    b.setMessage(typeLabel());
                })
                .bounds(cx + 3, y + 56, 97, 20)
                .tooltip(Tooltip.create(Component.translatable("sharedjourney.waypoint.type.tooltip")))
                .build());

        // Valider / Supprimer / Annuler
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> save())
                .bounds(cx - 100, y + 84, 95, 20)
                .build());
        if (creating) {
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> close())
                    .bounds(cx + 5, y + 84, 95, 20)
                    .build());
        } else {
            addRenderableWidget(Button.builder(
                            Component.translatable("sharedjourney.waypoint.delete")
                                    .withStyle(s -> s.withColor(0xFF6060)),
                            b -> {
                                WaypointStore.remove(waypoint.id());
                                close();
                            })
                    .bounds(cx + 5, y + 84, 95, 20)
                    .build());
        }
    }

    private Component visibilityLabel() {
        return Component.translatable(
                waypoint.visible() ? "sharedjourney.waypoint.visible" : "sharedjourney.waypoint.hidden");
    }

    private Component typeLabel() {
        String key = "sharedjourney.waypoint.type." + waypoint.type().name().toLowerCase(Locale.ROOT);
        return Component.translatable("sharedjourney.waypoint.type", Component.translatable(key));
    }

    private static Waypoint.Type nextType(Waypoint.Type type) {
        Waypoint.Type[] values = Waypoint.Type.values();
        return values[(type.ordinal() + 1) % values.length];
    }

    private void save() {
        String name = nameBox.getValue().isBlank()
                ? waypoint.name()
                : nameBox.getValue().trim();
        waypoint = waypoint.withName(name);
        if (creating) {
            WaypointStore.add(waypoint);
        } else {
            WaypointStore.update(waypoint);
        }

        close();
    }

    private void close() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(@NotNull GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.render(gg, mouseX, mouseY, partialTick);
        int cx = width / 2;
        int y = height / 2 - 40;
        gg.drawCenteredString(font, title, cx, y - 30, 0xFFFFFF);
        gg.drawCenteredString(font, waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z(), cx, y - 16, 0xAAAAAA);
        // Aperçu de la couleur courante au-dessus de la palette
        int px = cx - (PALETTE.length * 22 - 2) / 2;
        for (int i = 0; i < PALETTE.length; i++) {
            gg.fill(px + i * 22 + 2, y + 30, px + i * 22 + 18, y + 46, 0xFF000000 | PALETTE[i]);
        }
        gg.fill(cx + 104, y, cx + 124, y + 20, 0xFF000000 | waypoint.colorRgb());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
