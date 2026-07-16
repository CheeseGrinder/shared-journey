package fr.cheesegrinder.sharedjourney.client.service;

import fr.cheesegrinder.sharedjourney.api.client.MapMarker;
import fr.cheesegrinder.sharedjourney.api.client.MapMarkerProvider;
import fr.cheesegrinder.sharedjourney.api.client.MapView;

import net.minecraft.resources.ResourceLocation;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry backing {@link fr.cheesegrinder.sharedjourney.api.client.MapMarkerApi}
 * (wired by {@code SharedJourneyClient}). Providers are registered from
 * mod init (any thread) and polled from the render thread once per
 * visible map per frame: mutations swap an immutable snapshot read
 * lock-free by {@link #collect}.
 *
 * <p>A provider that throws (or returns null) is logged once and skipped
 * for the frame — a broken third-party provider must never take the HUD
 * render loop down with it.
 */
public final class MapMarkerStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Registration order preserved (draw order between providers). */
    private static final Map<ResourceLocation, MapMarkerProvider> PROVIDERS = new LinkedHashMap<>();

    /** Immutable snapshot of the providers, read by the render thread. */
    private static volatile List<MapMarkerProvider> snapshot = List.of();

    /** Providers already reported broken (one warn per offender). */
    private static final Set<ResourceLocation> REPORTED = ConcurrentHashMap.newKeySet();

    private MapMarkerStore() {}

    /** Registers or replaces (same id) a provider. */
    public static synchronized void register(ResourceLocation id, MapMarkerProvider provider) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        PROVIDERS.put(id, provider);
        REPORTED.remove(id);
        snapshot = List.copyOf(PROVIDERS.values());
    }

    /** Removes a provider; unknown ids are ignored. */
    public static synchronized void unregister(ResourceLocation id) {
        if (PROVIDERS.remove(id) != null) {
            snapshot = List.copyOf(PROVIDERS.values());
        }
    }

    /**
     * All markers to draw on this view, this frame, in provider
     * registration order. Never null; broken providers are skipped.
     */
    public static List<MapMarker> collect(MapView view) {
        List<MapMarkerProvider> providers = snapshot;
        if (providers.isEmpty()) {
            return List.of();
        }

        List<MapMarker> markers = new ArrayList<>();
        for (MapMarkerProvider provider : providers) {
            try {
                List<MapMarker> provided = provider.markers(view);
                if (provided != null) {
                    markers.addAll(provided);
                }
            } catch (Throwable t) {
                warnOnce(provider, t);
            }
        }
        return markers;
    }

    /** Logs a broken provider once (identified by its registration id). */
    private static synchronized void warnOnce(MapMarkerProvider provider, Throwable t) {
        for (Map.Entry<ResourceLocation, MapMarkerProvider> entry : PROVIDERS.entrySet()) {
            if (entry.getValue() == provider && REPORTED.add(entry.getKey())) {
                LOGGER.warn("Map marker provider {} threw and was skipped: {}", entry.getKey(), t.toString());
            }
        }
    }
}
