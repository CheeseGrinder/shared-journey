package fr.cheesegrinder.sharedjourney.api.client;

import net.minecraft.resources.ResourceLocation;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Registration facade for declarative {@link MapMarker}s (screen-render
 * API v2). Marker state is owned by {@code client.service.MapMarkerStore},
 * which {@code api} cannot reference (layering: api has no dependencies) —
 * this class is the same static-hook indirection as {@link
 * fr.cheesegrinder.sharedjourney.api.WaypointApi}, wired by the client
 * entry point ({@code SharedJourneyClient}) at startup.
 *
 * <p>Register anytime (client mod init or later); providers are polled
 * once per visible map per frame, so dynamic markers need no
 * re-registration. Markers are drawn above the map tiles and grid, below
 * SharedJourney's own waypoints, player heads and player arrow; their
 * order relative to {@link
 * fr.cheesegrinder.sharedjourney.api.client.event.MapRenderEvent} v1
 * overlays is unspecified. A provider that throws is logged once and
 * skipped.
 *
 * <p>Deliberately NOT exposed (YAGNI until a consumer needs them):
 * provider enumeration, per-provider enable/disable, marker hit-testing.
 */
public final class MapMarkerApi {

    /** Hook indirection, wired by {@code SharedJourneyClient} at startup. */
    public static final class Hooks {
        public static BiConsumer<ResourceLocation, MapMarkerProvider> register = (id, provider) -> {};
        public static Consumer<ResourceLocation> unregister = id -> {};

        private Hooks() {}
    }

    private MapMarkerApi() {}

    /**
     * Registers a marker provider, replacing any previous provider with
     * the same id. Use your own namespace: {@code yourmodid:whatever}.
     */
    public static void register(ResourceLocation id, MapMarkerProvider provider) {
        Hooks.register.accept(id, provider);
    }

    /** Removes a provider; unknown ids are ignored. */
    public static void unregister(ResourceLocation id) {
        Hooks.unregister.accept(id);
    }
}
