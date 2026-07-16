package fr.cheesegrinder.sharedjourney.api.client.event;

import fr.cheesegrinder.sharedjourney.api.client.MapView;

import net.minecraft.network.chat.Component;

import net.neoforged.bus.api.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Posted on NeoForge.EVENT_BUS (CLIENT side only) each frame the
 * fullscreen map draws an info bar — {@link Top} for the player bar under
 * the toolbar, {@link Hover} for the bottom bar (hovered block).
 * Contributed segments are appended after the internal ones, joined with
 * the same " ■ " separator as the rest of the bar.
 *
 * <p>Posted PER FRAME (the bars are rebuilt every frame internally
 * anyway): listeners must be cheap and allocation-light; cache anything
 * expensive yourself. Fullscreen map only — the minimap's info pills are
 * not extensible.
 *
 * <p>Deliberately NOT exposed (YAGNI until a consumer needs them):
 * reordering/removal of internal segments, extra bars, styling beyond
 * the Component's own formatting.
 */
public abstract class FullMapInfoBarEvent extends Event {

    private final MapView view;
    private final List<Component> segments = new ArrayList<>();

    protected FullMapInfoBarEvent(MapView view) {
        this.view = view;
    }

    public MapView getView() {
        return view;
    }

    /** Appends a segment after the internal ones. */
    public void addSegment(Component segment) {
        Objects.requireNonNull(segment, "segment");
        segments.add(segment);
    }

    /** Contributed segments in contribution order (consumed by the screen). */
    public List<Component> getSegments() {
        return Collections.unmodifiableList(segments);
    }

    /** Top bar: player name ■ position ■ biome ■ zoom. */
    public static class Top extends FullMapInfoBarEvent {

        public Top(MapView view) {
            super(view);
        }
    }

    /** Bottom hover bar; carries the hovered block column. */
    public static class Hover extends FullMapInfoBarEvent {

        private final int blockX;
        private final int blockZ;

        public Hover(MapView view, int blockX, int blockZ) {
            super(view);
            this.blockX = blockX;
            this.blockZ = blockZ;
        }

        /** World X of the hovered block column. */
        public int getBlockX() {
            return blockX;
        }

        /** World Z of the hovered block column. */
        public int getBlockZ() {
            return blockZ;
        }
    }
}
