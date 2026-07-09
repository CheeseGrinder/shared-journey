package fr.cheesegrinder.sharedjourney.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * "sync" section of the server config: tile synchronization towards clients
 * (radius, bandwidth, cadence) and safeguards for on-demand requests and the
 * entity radar.
 */
public final class SyncServerConfig {

    public static ModConfigSpec.IntValue PUSH_RADIUS_REGIONS;
    /** Per-player bandwidth cap (spec §5: max_kb_per_second). */
    public static ModConfigSpec.IntValue MAX_KB_PER_SECOND_PER_PLAYER;

    public static ModConfigSpec.IntValue SYNC_RATE_TICKS;
    public static ModConfigSpec.BooleanValue ALLOW_ON_DEMAND_REQUESTS;
    /** Max radar radius tolerated server-side (anti-cheat, caps the client). */
    public static ModConfigSpec.IntValue RADAR_MAX_RADIUS;

    private SyncServerConfig() {}

    static void define(ModConfigSpec.Builder b) {
        b.push("sync");
        PUSH_RADIUS_REGIONS = b.comment("Radius in regions (512 blocks) synchronized around players.")
                .defineInRange("pushRadiusRegions", 2, 0, 8);
        MAX_KB_PER_SECOND_PER_PLAYER = b.comment("Max bandwidth per player (KB/s) for tile pushes.")
                .defineInRange("maxKbPerSecondPerPlayer", 512, 32, 8192);
        SYNC_RATE_TICKS = b.comment("Interval between two sync delta computations per player (ticks).")
                .defineInRange("syncRateTicks", 40, 5, 1200);
        ALLOW_ON_DEMAND_REQUESTS = b.comment(
                        "Allow clients to request regions outside the push radius (fullscreen map).")
                .define("allowOnDemandRequests", true);
        RADAR_MAX_RADIUS = b.comment("Max radius (blocks) allowed for the clients' entity radar.")
                .defineInRange("radarMaxRadius", 64, 0, 128);
        b.pop();
    }
}
