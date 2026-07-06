package fr.cheesegrinder.sharedjourney.server;

import fr.cheesegrinder.sharedjourney.common.RegionKey;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Régénération complète de la carte (/sj admin regen) : re-rend tous les
 * chunks déjà peints, en les rechargeant depuis le disque au besoin, à un
 * rythme throttlé pour ne pas étouffer le serveur. La progression est
 * affichée dans une boss bar temporaire visible par tous les joueurs.
 * Uniquement des chunks déjà peints : ils ont donc déjà été générés, le
 * chargement ne déclenche pas de génération de terrain.
 */
public final class RegenService {

    /** Chunks (re)chargés par tick serveur : borne le coût du chargement disque. */
    private static final int CHUNKS_PER_TICK = 4;
    /** Pause du chargement si la file de rendu prend trop de retard. */
    private static final int MAX_PENDING_RENDERS = 256;

    private static ArrayDeque<Entry> queue;
    private static ServerBossEvent bossBar;
    private static int total;
    private static int done;

    private record Entry(ResourceKey<Level> dim, int cx, int cz) {}
    private record RegionPos(ResourceKey<Level> dim, int rx, int rz) {}

    private RegenService() {}

    public static boolean isRunning() { return queue != null; }

    /**
     * Construit la file depuis l'index des régions (chunks déjà peints
     * uniquement) et affiche la boss bar. Retourne le nombre de chunks à
     * traiter, ou -1 si le moteur n'est pas prêt ou une regen déjà en cours.
     */
    public static int start(MinecraftServer server) {
        MapManager mgr = MapManager.get();
        if (mgr == null || isRunning()) return -1;

        // Régions uniques, toutes couches/bandes confondues.
        Set<RegionPos> regions = new HashSet<>();
        for (RegionKey key : mgr.indexedRegions()) {
            regions.add(new RegionPos(key.dimension(), key.rx(), key.rz()));
        }

        ArrayDeque<Entry> q = new ArrayDeque<>();
        for (RegionPos region : regions) {
            ServerLevel level = server.getLevel(region.dim());
            if (level == null) continue;
            for (int cx = 0; cx < RegionKey.REGION_CHUNKS; cx++) {
                for (int cz = 0; cz < RegionKey.REGION_CHUNKS; cz++) {
                    int acx = region.rx() * RegionKey.REGION_CHUNKS + cx;
                    int acz = region.rz() * RegionKey.REGION_CHUNKS + cz;
                    if (mgr.isChunkRendered(level, acx, acz)) {
                        q.add(new Entry(region.dim(), acx, acz));
                    }
                }
            }
        }

        queue = q;
        total = q.size();
        done = 0;
        bossBar = new ServerBossEvent(barName(),
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        bossBar.setProgress(0f);
        return total;
    }

    public static void cancel() {
        if (bossBar != null) bossBar.removeAllPlayers();
        queue = null;
        bossBar = null;
    }

    /** Appelé chaque tick serveur (main thread). */
    public static void tick(MinecraftServer server) {
        if (queue == null) return;
        MapManager mgr = MapManager.get();
        if (mgr == null) {
            cancel();
            return;
        }

        if (mgr.queueSize() < MAX_PENDING_RENDERS) {
            for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
                Entry e = queue.poll();
                ServerLevel level = server.getLevel(e.dim());
                if (level == null) continue;
                // Chargement bloquant mais throttlé ; le chunk a déjà été
                // peint, donc il existe sur disque (pas de génération).
                level.getChunk(e.cx(), e.cz());
                mgr.enqueueChunk(level, e.cx(), e.cz());
                done++;
            }
        }

        if (bossBar != null) {
            bossBar.setProgress(total == 0 ? 1f : (float) done / total);
            bossBar.setName(barName());
            // Couvre aussi les joueurs connectés en cours de route (idempotent).
            for (ServerPlayer p : server.getPlayerList().getPlayers()) bossBar.addPlayer(p);
        }

        // Fin : attend que le pool de rendu ait terminé avant de retirer la bar.
        if (queue.isEmpty() && mgr.queueSize() == 0 && mgr.tasksInFlight() == 0) {
            cancel();
        }
    }

    private static Component barName() {
        return Component.translatable("sharedjourney.regen.bossbar", done, total);
    }
}