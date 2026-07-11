package fr.cheesegrinder.sharedjourney.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * SERVER config (facade): assembles the per-feature sections —
 * {@link LayersServerConfig} (layers), {@link EngineServerConfig} (render
 * engine), {@link SyncServerConfig} (synchronization) and
 * {@link WaypointServerConfig} (waypoints) — into a single spec (one TOML
 * file, "layers"/"engine"/"sync"/"waypoints" sections unchanged).
 * NeoForge already implements the hierarchy required by spec §8:
 * {@code defaultconfigs/} (global) is copied then overridden by
 * {@code world/serverconfig/} (per-world). Editable in-game by an OP through
 * the config screen, or via /sj admin layer.
 */
public final class ServerConfig {

    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        LayersServerConfig.define(b);
        EngineServerConfig.define(b);
        SyncServerConfig.define(b);
        WaypointServerConfig.define(b);
        SPEC = b.build();
    }

    private ServerConfig() {}

    /** Invalidates the sections' parsing caches (called on config reload). */
    public static void invalidateCache() {
        LayersServerConfig.invalidateCache();
        EngineServerConfig.invalidateCache();
    }
}
