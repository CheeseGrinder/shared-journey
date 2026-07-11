package fr.cheesegrinder.sharedjourney.api;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Public waypoint CRUD facade, published now that the waypoint model is
 * stable (groups, PUBLIC/DIMENSION/TEMP types, death waypoints). Waypoints
 * are owned by {@code client.service.WaypointStore}, which {@code api}
 * cannot reference (layering: api has no dependencies) — this class is
 * the same static-hook indirection as {@link
 * fr.cheesegrinder.sharedjourney.common.network.Payloads.Hooks}, wired by
 * the client entry point ({@code SharedJourneyClient}) at startup.
 *
 * <p>Additions/removals/updates post {@link
 * fr.cheesegrinder.sharedjourney.api.event.WaypointEvent} on
 * {@code NeoForge.EVENT_BUS} regardless of the entry point used (this
 * facade or the store directly): listen there to react, not by polling
 * this facade.
 *
 * <p>Deliberately NOT exposed here (internal session/lifecycle concerns,
 * YAGNI until a consumer needs them): group management (create/rename/
 * delete), session open/close, temp-waypoint cleanup, the bridge's
 * per-source removal.
 */
public final class WaypointApi {

    /** Hook indirection, wired by {@code SharedJourneyClient} at startup. */
    public static final class Hooks {
        public static Supplier<List<Waypoint>> all = List::of;
        public static Function<ResourceLocation, List<Waypoint>> forDimension = dim -> List.of();
        public static Function<UUID, Waypoint> get = id -> null;
        public static Predicate<Waypoint> add = wp -> false;
        public static Consumer<Waypoint> update = wp -> {};
        public static Consumer<UUID> remove = id -> {};
        public static Predicate<Waypoint> isShown = wp -> false;
        public static Supplier<List<String>> groups = List::of;

        private Hooks() {}
    }

    private WaypointApi() {}

    /** Every waypoint known to this client (own + bridged mods'). */
    public static List<Waypoint> all() {
        return Hooks.all.get();
    }

    /** Waypoints of a dimension (every type is bound to its own dimension). */
    public static List<Waypoint> forDimension(ResourceLocation dimension) {
        return Hooks.forDimension.apply(dimension);
    }

    /** A waypoint by id, or null if unknown. */
    public static Waypoint get(UUID id) {
        return Hooks.get.apply(id);
    }

    /**
     * Adds a waypoint. PUBLIC waypoints are routed to the server (shared
     * with every player). Returns false if a listener cancelled the
     * {@link fr.cheesegrinder.sharedjourney.api.event.WaypointEvent.Added}
     * event.
     */
    public static boolean add(Waypoint waypoint) {
        return Hooks.add.test(waypoint);
    }

    /** Updates an existing waypoint (matched by id). */
    public static void update(Waypoint waypoint) {
        Hooks.update.accept(waypoint);
    }

    public static void remove(UUID id) {
        Hooks.remove.accept(id);
    }

    /** Should this waypoint currently be rendered (own flag AND its group's)? */
    public static boolean isShown(Waypoint waypoint) {
        return Hooks.isShown.test(waypoint);
    }

    /** Groups in use, sorted, the default group listed first. */
    public static List<String> groups() {
        return Hooks.groups.get();
    }
}
