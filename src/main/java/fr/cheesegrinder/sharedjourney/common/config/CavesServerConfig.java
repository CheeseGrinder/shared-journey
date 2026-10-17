package fr.cheesegrinder.sharedjourney.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * "caves" section of the server config: how far around a player the CAVE
 * layer is revealed (the anti-exploit unlocking done by
 * {@code CaveTracker}). The vertical bands themselves are configured in the
 * "layers" section ({@link LayersServerConfig#CAVE_BANDS}).
 */
public final class CavesServerConfig {

    /** Chunks revealed on the player's band, in every direction (radius 2 = 5x5 chunks). */
    public static ModConfigSpec.IntValue REVEAL_RADIUS_CHUNKS;

    private CavesServerConfig() {}

    static void define(ModConfigSpec.Builder b) {
        b.push("caves");
        REVEAL_RADIUS_CHUNKS = b.comment(
                        "Radius (in chunks) revealed on the CAVE layer around a player underground, on the band he is in.",
                        "1 = 3x3 chunks, 5 = 11x11 (default), 16 = 33x33. A larger radius shows more of the caves",
                        "around the player (including behind walls) and renders more chunks the first time an area is visited.")
                .defineInRange("revealRadiusChunks", 5, 1, 16);
        b.pop();
    }
}
