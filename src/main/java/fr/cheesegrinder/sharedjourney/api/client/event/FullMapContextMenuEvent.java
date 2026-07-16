package fr.cheesegrinder.sharedjourney.api.client.event;

import fr.cheesegrinder.sharedjourney.api.client.MapView;

import net.minecraft.network.chat.Component;

import net.neoforged.bus.api.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Posted on NeoForge.EVENT_BUS (CLIENT side only) when the fullscreen map
 * opens its right-click context menu, after the internal rows (Teleport,
 * Waypoints, Position in chat) are built. Contributed entries are
 * appended below them, in listener/contribution order.
 *
 * <p>{@link #getBlockX()}/{@link #getBlockZ()} are the CLICKED block
 * (floor of the world position under the cursor — the coordinates shown
 * as the menu title). One submenu level only, enforced at construction.
 *
 * <p>Deliberately NOT exposed (YAGNI until a consumer needs them): entry
 * icons, insertion position/reordering, removal of internal rows, nested
 * submenus, dynamic enabling.
 */
public class FullMapContextMenuEvent extends Event {

    /** One menu row: an action, or a one-level submenu of action entries. */
    public record Entry(Component label, Runnable action, List<Entry> children) {

        public Entry {
            Objects.requireNonNull(label, "label");
        }

        /** Clickable row running the given action (the menu closes first). */
        public static Entry action(Component label, Runnable action) {
            Objects.requireNonNull(action, "action");
            return new Entry(label, action, null);
        }

        /**
         * Submenu row. Children must all be action entries (one submenu
         * level only): a nested submenu throws IllegalArgumentException.
         */
        public static Entry submenu(Component label, List<Entry> children) {
            Objects.requireNonNull(children, "children");
            for (Entry child : children) {
                if (child.children() != null) {
                    throw new IllegalArgumentException("Nested submenus are not supported");
                }
            }
            return new Entry(label, null, List.copyOf(children));
        }
    }

    private final MapView view;
    private final int blockX;
    private final int blockZ;
    private final List<Entry> entries = new ArrayList<>();

    public FullMapContextMenuEvent(MapView view, int blockX, int blockZ) {
        this.view = view;
        this.blockX = blockX;
        this.blockZ = blockZ;
    }

    public MapView getView() {
        return view;
    }

    /** World X of the clicked block. */
    public int getBlockX() {
        return blockX;
    }

    /** World Z of the clicked block. */
    public int getBlockZ() {
        return blockZ;
    }

    /** Appends an entry below the internal rows. */
    public void addEntry(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        entries.add(entry);
    }

    /** Contributed entries in contribution order (consumed by the screen). */
    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
