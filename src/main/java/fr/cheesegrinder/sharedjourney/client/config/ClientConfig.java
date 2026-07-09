package fr.cheesegrinder.sharedjourney.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * CLIENT config (facade, spec §8): assembles the per-feature sections —
 * {@link MinimapClientConfig} (HUD minimap), {@link RadarClientConfig}
 * (entity radar), {@link WaypointClientConfig} (waypoints/beacons) and
 * {@link MapClientConfig} (overlays, layers, disk cache) — into a single
 * spec (one TOML file, keys unchanged).
 */
public final class ClientConfig {

    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        MinimapClientConfig.define(b);
        RadarClientConfig.define(b);
        WaypointClientConfig.define(b);
        MapClientConfig.define(b);
        SPEC = b.build();
    }

    private ClientConfig() {}
}
