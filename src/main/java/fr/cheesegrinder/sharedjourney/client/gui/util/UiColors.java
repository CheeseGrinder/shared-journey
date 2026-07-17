package fr.cheesegrinder.sharedjourney.client.gui.util;

/**
 * Shared ARGB color palette of the mod's custom-rendered screens
 * (dark panel style: {@link WaypointEditScreen}, {@link ModalScreen},
 * {@link ContextMenu}, the group autocomplete dropdown). Centralized so
 * the panels stay visually consistent and the values aren't repeated.
 */
public final class UiColors {

    /** Panel/dropdown background (translucent near-black). */
    public static final int PANEL_BACKGROUND = 0xE0101014;

    /** Panel border (dark gray). */
    public static final int PANEL_BORDER = 0xFF404048;

    /** Flat menu/dropdown background (context menu, autocomplete). */
    public static final int MENU_BACKGROUND = 0xF016161C;

    /** Flat menu/dropdown border. */
    public static final int MENU_BORDER = 0xFF44444C;

    /** Hovered/selected row highlight. */
    public static final int ROW_HIGHLIGHT = 0xFF32323C;

    /** Default row text. */
    public static final int TEXT = 0xFFCACACA;

    /** Hovered/selected row text. */
    public static final int TEXT_HOVER = 0xFFFFFFFF;

    /** Muted title/header text. */
    public static final int TEXT_TITLE = 0xFF8A8A92;

    /** Keycap chip background (fullscreen map legend). */
    public static final int CHIP_BACKGROUND = 0xFF2E2E36;

    /** Keycap chip border (fullscreen map legend). */
    public static final int CHIP_BORDER = 0xFF5A5A64;

    private UiColors() {}
}
