package fr.cheesegrinder.sharedjourney.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Section "sync" de la config serveur : synchronisation des tuiles vers les
 * clients (rayon, bande passante, cadence) et garde-fous des requêtes à la
 * demande / du radar.
 */
public final class SyncServerConfig {

    public static ModConfigSpec.IntValue PUSH_RADIUS_REGIONS;
    /** Limite de bande passante par joueur (spec §5 : max_kb_per_second). */
    public static ModConfigSpec.IntValue MAX_KB_PER_SECOND_PER_PLAYER;

    public static ModConfigSpec.IntValue SYNC_RATE_TICKS;
    public static ModConfigSpec.BooleanValue ALLOW_ON_DEMAND_REQUESTS;
    /** Rayon max du radar toléré côté serveur (anti-triche, plafonne le client). */
    public static ModConfigSpec.IntValue RADAR_MAX_RADIUS;

    private SyncServerConfig() {}

    static void define(ModConfigSpec.Builder b) {
        b.push("sync");
        PUSH_RADIUS_REGIONS = b.comment("Rayon en régions (512 blocs) synchronisé autour des joueurs.")
                .defineInRange("pushRadiusRegions", 2, 0, 8);
        MAX_KB_PER_SECOND_PER_PLAYER = b.comment("Bande passante max par joueur (Ko/s) pour l'envoi des tuiles.")
                .defineInRange("maxKbPerSecondPerPlayer", 512, 32, 8192);
        SYNC_RATE_TICKS = b.comment("Intervalle entre deux calculs de delta de sync par joueur (ticks).")
                .defineInRange("syncRateTicks", 40, 5, 1200);
        ALLOW_ON_DEMAND_REQUESTS = b.comment(
                        "Autoriser le client à demander des régions hors rayon (carte plein écran).")
                .define("allowOnDemandRequests", true);
        RADAR_MAX_RADIUS = b.comment("Rayon max (blocs) autorisé pour le radar d'entités des clients.")
                .defineInRange("radarMaxRadius", 64, 0, 128);
        b.pop();
    }
}
