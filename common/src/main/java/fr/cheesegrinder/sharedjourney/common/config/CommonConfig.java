package fr.cheesegrinder.sharedjourney.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Config COMMON : options partagées client/serveur. */
public final class CommonConfig {

    public static final ModConfigSpec SPEC;

    public static ModConfigSpec.BooleanValue DEBUG_LOGGING;
    /** Taille max d'un fragment réseau (< 1 MiB S2C ; 28 Ko est prudent). */
    public static ModConfigSpec.IntValue FRAGMENT_SIZE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        DEBUG_LOGGING = b.comment("Logs détaillés de rendu/sync.").define("debugLogging", false);
        FRAGMENT_SIZE = b.comment("Taille max d'un fragment réseau en octets.")
                .defineInRange("fragmentSize", 28_000, 8_000, 900_000);
        SPEC = b.build();
    }
}
