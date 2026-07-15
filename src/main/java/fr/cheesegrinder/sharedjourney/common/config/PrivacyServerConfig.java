package fr.cheesegrinder.sharedjourney.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * "privacy" section of the server config: what happens to map updates caused
 * near players who asked to be hidden from the map. Without gating, a hidden
 * player still "draws" their position through three vectors: broken/placed
 * blocks (live push), freshly generated chunks (exploration front) and CAVE
 * band unlocks.
 */
public final class PrivacyServerConfig {

    /** Diffusion policy for map updates near hidden players. */
    public enum HiddenAreaPolicy {
        /** No gating: hiding only removes the position marker. */
        OFF,
        /** Render normally; delay the broadcast to distant players. */
        QUARANTINE,
        /** Radical: chunks near hidden players are not rendered at all. */
        EXCLUDE
    }

    public static ModConfigSpec.EnumValue<HiddenAreaPolicy> HIDDEN_AREA_POLICY;
    public static ModConfigSpec.IntValue QUARANTINE_RADIUS_CHUNKS;
    public static ModConfigSpec.IntValue QUARANTINE_DRAIN_MINUTES;

    private PrivacyServerConfig() {}

    static void define(ModConfigSpec.Builder b) {
        b.push("privacy");
        HIDDEN_AREA_POLICY = b.comment(
                        "Map updates near a player hidden from the map: OFF (no gating),",
                        "QUARANTINE (rendered normally but the broadcast to distant players",
                        "is delayed), EXCLUDE (not rendered at all; the area updates later,",
                        "when a visible player triggers it).")
                .defineEnum("hiddenAreaPolicy", HiddenAreaPolicy.QUARANTINE);
        QUARANTINE_RADIUS_CHUNKS = b.comment(
                        "Radius (chunks) around a hidden player within which map updates are",
                        "quarantined/excluded. Always floored to the server view distance",
                        "+ 1: the exploration front generates chunks that far, and a",
                        "smaller radius would broadcast a ring with a tell-tale hole at",
                        "the hidden player's position. Players within this radius of a",
                        "quarantined chunk still receive the real map immediately (the",
                        "game already streams them the area anyway).")
                .defineInRange("quarantineRadiusChunks", 8, 1, 32);
        QUARANTINE_DRAIN_MINUTES = b.comment(
                        "Delay (minutes) before a quarantined chunk is broadcast to distant",
                        "players. Randomized per chunk (+/-25%) so the reveal does not",
                        "replay the hidden player's trajectory. Re-armed while a hidden",
                        "player is still nearby.")
                .defineInRange("quarantineDrainMinutes", 10, 1, 120);
        b.pop();
    }
}
