package fr.cheesegrinder.sharedjourney.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** COMMON config: options shared between client and server. */
public final class CommonConfig {

    public static final ModConfigSpec SPEC;

    public static ModConfigSpec.BooleanValue DEBUG_LOGGING;
    /** Max size of a network fragment (< 1 MiB S2C; 28 KB is conservative). */
    public static ModConfigSpec.IntValue FRAGMENT_SIZE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        DEBUG_LOGGING = b.comment("Verbose render/sync logging.").define("debugLogging", false);
        FRAGMENT_SIZE = b.comment("Maximum size of a network fragment, in bytes.")
                .defineInRange("fragmentSize", 28_000, 8_000, 900_000);
        SPEC = b.build();
    }

    private CommonConfig() {}
}
