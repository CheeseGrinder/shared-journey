package fr.cheesegrinder.sharedjourney.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Config SERVEUR (façade) : assemble les sections par fonctionnalité —
 * {@link LayersServerConfig} (couches), {@link EngineServerConfig} (moteur de
 * rendu) et {@link SyncServerConfig} (synchronisation) — dans un spec unique
 * (un seul fichier TOML, sections "layers"/"engine"/"sync" inchangées).
 * NeoForge implémente déjà la hiérarchie de la spec §8 : `defaultconfigs/`
 * (globale) est copiée puis écrasée par `world/serverconfig/` (locale au
 * monde). Éditable en jeu par un OP via l'écran de config, ou /sj admin layer.
 */
public final class ServerConfig {

    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        LayersServerConfig.define(b);
        EngineServerConfig.define(b);
        SyncServerConfig.define(b);
        SPEC = b.build();
    }

    private ServerConfig() {}

    /** Invalide les caches de parsing des sections (appelé au reload de config). */
    public static void invalidateCache() {
        LayersServerConfig.invalidateCache();
        EngineServerConfig.invalidateCache();
    }
}
