package fr.cheesegrinder.sharedjourney.common.util;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.Waypoint;

import java.util.Locale;

/**
 * Central registry of the mod's translation keys, used as
 * {@code Component.translatable(Lang.X)}. One constant per key referenced
 * in code keeps typos out and makes dead keys searchable; the
 * NeoForge-generated config screen keys ({@code sharedjourney.configuration.*})
 * follow their own naming convention and are not listed here.
 */
public final class Lang {

    private static final String PREFIX = SharedJourneyConstants.MOD_ID + ".";

    // ------------------------------------------------------------------ key bindings

    public static final String KEY_CATEGORY = "key.categories." + SharedJourneyConstants.MOD_ID;
    public static final String KEY_FULL_MAP = "key." + PREFIX + "fullmap";
    public static final String KEY_TOGGLE_MINIMAP = "key." + PREFIX + "toggle_minimap";
    public static final String KEY_CYCLE_LAYER = "key." + PREFIX + "cycle_layer";
    public static final String KEY_ZOOM_IN = "key." + PREFIX + "zoom_in";
    public static final String KEY_ZOOM_OUT = "key." + PREFIX + "zoom_out";
    public static final String KEY_OPEN_WAYPOINTS = "key." + PREFIX + "waypoints";
    public static final String KEY_CREATE_WAYPOINT = "key." + PREFIX + "create_waypoint";

    // ------------------------------------------------------------------ fullscreen map: action bar

    public static final String ACTION_SHOW_CAVE = action("show_cave");
    public static final String ACTION_SHOW_MOBS = action("show_mobs");
    public static final String ACTION_SHOW_ANIMALS = action("show_animals");
    public static final String ACTION_SHOW_PETS = action("show_pets");
    public static final String ACTION_SHOW_VILLAGERS = action("show_villagers");
    public static final String ACTION_SHOW_GRID = action("show_grid");
    public static final String ACTION_HIDE_FROM_MAP = action("hide_from_map");
    public static final String ACTION_SHOW_KEYS = action("show_keys");
    public static final String ACTION_CLOSE = action("close");
    public static final String ACTION_LOCATE = action("locate");
    public static final String ACTION_FOLLOW = action("follow");
    public static final String ACTION_ZOOM_IN = action("zoom_in");
    public static final String ACTION_ZOOM_OUT = action("zoom_out");
    public static final String ACTION_WAYPOINTS = action("waypoints");

    /** Layer button tooltip ("sharedjourney.action.day", ".cave"...). */
    public static String actionLayer(String layerName) {
        return action(layerName.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ fullscreen map: context menu

    public static final String CONTEXT_TELEPORT = key("context.teleport");
    public static final String CONTEXT_WAYPOINTS = key("context.waypoints");
    public static final String CONTEXT_WAYPOINT = key("context.waypoint");
    public static final String CONTEXT_WAYPOINT_TEMP = key("context.waypoint_temp");
    public static final String CONTEXT_WAYPOINT_PUBLIC = key("context.waypoint_public");
    public static final String CONTEXT_SHOW_ALL = key("context.show_all");
    public static final String CONTEXT_HIDE_ALL = key("context.hide_all");
    public static final String CONTEXT_MANAGE_WAYPOINTS = key("context.manage_waypoints");
    public static final String CONTEXT_CHAT = key("context.chat");

    // ------------------------------------------------------------------ fullscreen map: misc

    public static final String COORDS_CHAT = key("coords.chat");
    public static final String COORDS_OPEN = key("coords.open");

    public static final String LEGEND_DRAG = key("legend.drag");
    public static final String LEGEND_SCROLL = key("legend.scroll");
    public static final String LEGEND_DOUBLE_CLICK = key("legend.double_click");
    public static final String LEGEND_RIGHT_CLICK = key("legend.right_click");
    public static final String LEGEND_ARROWS = key("legend.arrows");
    public static final String LEGEND_KEY_B = key("legend.key_b");
    public static final String LEGEND_KEY_C = key("legend.key_c");
    public static final String LEGEND_KEY_F = key("legend.key_f");
    public static final String LEGEND_KEY_T = key("legend.key_t");
    public static final String LEGEND_KEY_J = key("legend.key_j");

    // ------------------------------------------------------------------ minimap

    public static final String TIME_DAY = key("time.day");
    public static final String TIME_SUNSET = key("time.sunset");
    public static final String TIME_NIGHT = key("time.night");
    public static final String TIME_SUNRISE = key("time.sunrise");

    // ------------------------------------------------------------------ waypoint edit form

    public static final String WAYPOINT_CREATE = key("waypoint.create");
    public static final String WAYPOINT_EDIT = key("waypoint.edit");
    public static final String WAYPOINT_DELETE = key("waypoint.delete");
    public static final String WAYPOINT_NAME = key("waypoint.name");
    public static final String WAYPOINT_POSITION = key("waypoint.position");
    public static final String WAYPOINT_GROUP = key("waypoint.group");
    public static final String WAYPOINT_GROUP_TOOLTIP = key("waypoint.group.tooltip");
    public static final String WAYPOINT_COLOR = key("waypoint.color");
    public static final String WAYPOINT_RANDOM = key("waypoint.random");
    public static final String WAYPOINT_VISIBLE = key("waypoint.visible");
    public static final String WAYPOINT_HIDDEN = key("waypoint.hidden");
    public static final String WAYPOINT_TYPE = key("waypoint.type");
    public static final String WAYPOINT_TYPE_TOOLTIP = key("waypoint.type.tooltip");

    /** Label of one waypoint type ("sharedjourney.waypoint.type.public"...). */
    public static String waypointType(Waypoint.Type type) {
        return key("waypoint.type." + type.name().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ waypoint manager

    public static final String WAYPOINTS_TITLE = key("waypoints.title");
    public static final String WAYPOINTS_EMPTY_GROUP = key("waypoints.empty_group");
    public static final String WAYPOINTS_GROUP_ALL = key("waypoints.group_all");
    public static final String WAYPOINTS_GROUP_TOGGLE = key("waypoints.group_toggle");
    public static final String WAYPOINTS_GROUP_NEW = key("waypoints.group_new");
    public static final String WAYPOINTS_GROUP_RENAME = key("waypoints.group_rename");
    public static final String WAYPOINTS_GROUP_RENAME_TITLE = key("waypoints.group_rename_title");
    public static final String WAYPOINTS_GROUP_DELETE = key("waypoints.group_delete");
    public static final String WAYPOINTS_GROUP_DELETE_TITLE = key("waypoints.group_delete_title");
    public static final String WAYPOINTS_GROUP_DELETE_CONFIRM = key("waypoints.group_delete_confirm");
    public static final String WAYPOINTS_TOGGLE = key("waypoints.toggle");
    public static final String WAYPOINTS_EDIT = key("waypoints.edit");
    public static final String WAYPOINTS_TELEPORT = key("waypoints.teleport");
    public static final String WAYPOINTS_ON = key("waypoints.on");
    public static final String WAYPOINTS_OFF = key("waypoints.off");

    public static final String GROUP_DEFAULT = key("group.default");
    public static final String GROUP_DEATHS = key("group.deaths");
    public static final String GROUP_PUBLIC = key("group.public");

    // ------------------------------------------------------------------ commands / services

    public static final String COMMAND_PURGED = key("command.purged");
    public static final String REGEN_SCAN = key("regen.scan");
    public static final String REGEN_BOSSBAR = key("regen.bossbar");

    private Lang() {}

    private static String key(String path) {
        return PREFIX + path;
    }

    private static String action(String path) {
        return key("action." + path);
    }
}
