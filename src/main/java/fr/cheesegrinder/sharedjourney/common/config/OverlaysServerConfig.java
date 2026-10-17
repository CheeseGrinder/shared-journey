package fr.cheesegrinder.sharedjourney.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * "overlays" section of the server config: server-side switches of the
 * server-computed chunk overlays (force-loaded chunks, slime chunks). A
 * disabled overlay is never computed nor sent, whatever the clients ask;
 * the flags are announced to clients ({@code OverlaySettingsPayload}) so
 * they hide the matching controls. Clients also have their own on/off
 * toggles (both default to off).
 */
public final class OverlaysServerConfig {

    /** Allow the force-loaded chunks overlay. */
    public static ModConfigSpec.BooleanValue FORCED_CHUNKS_ENABLED;

    /** Allow the slime chunks overlay (overworld only). */
    public static ModConfigSpec.BooleanValue SLIME_CHUNKS_ENABLED;

    private OverlaysServerConfig() {}

    static void define(ModConfigSpec.Builder b) {
        b.push("overlays");
        FORCED_CHUNKS_ENABLED = b.comment(
                        "Allow the force-loaded chunks overlay. Chunk positions are only sent for",
                        "regions already explored on the shared map.")
                .define("forcedChunksEnabled", true);
        SLIME_CHUNKS_ENABLED = b.comment(
                        "Allow the slime chunks overlay (overworld only). Slime chunks are computed",
                        "server-side from the world seed and only sent for regions already explored",
                        "on the shared map: the seed itself is never sent. Disable it if you consider",
                        "even this partial information a leak.")
                .define("slimeChunksEnabled", true);
        b.pop();
    }
}
