package fr.cheesegrinder.sharedjourney.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.config.ServerConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.Locale;

/**
 * Racine unique /map (spec §7).
 *   /map stats                                   -> niveau 0 : ses propres stats de sync
 *                                                   niveau 2 : + état moteur et stats de tous
 *   /map admin sync force <joueurs|all> [rx rz]  -> renvoi forcé (ignore l'index)
 *   /map admin rerender <rayonChunks>            -> re-rend autour de soi
 *   /map admin layer <dim> <couche> <true|false> -> couches par dimension à chaud
 *   /map admin save                              -> flush disque
 * (/map purge <layer> est enregistré CÔTÉ CLIENT — voir ClientCommands.)
 */
public final class MapCommands {

    private MapCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("map");

        // ---- /map stats (tous joueurs : soi ; OP : tout)
        root.then(Commands.literal("stats")
                .executes(ctx -> {
                    var src = ctx.getSource();
                    boolean op = src.hasPermission(2);
                    MapManager mgr = MapManager.get();
                    if (op && mgr != null) {
                        src.sendSuccess(() -> Component.literal(mgr.engineStats()), false);
                    }
                    if (op) {
                        for (ServerPlayer p : src.getServer().getPlayerList().getPlayers()) {
                            src.sendSuccess(() -> Component.literal("  " + SyncService.statsFor(p)), false);
                        }
                    } else if (src.getEntity() instanceof ServerPlayer self) {
                        src.sendSuccess(() -> Component.literal(SyncService.statsFor(self)), false);
                    }
                    return 1;
                })
                .then(Commands.argument("joueur", EntityArgument.player())
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            ServerPlayer p = EntityArgument.getPlayer(ctx, "joueur");
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(SyncService.statsFor(p)), false);
                            return 1;
                        })));

        // ---- /map admin ... (OP)
        LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin")
                .requires(src -> src.hasPermission(2));

        // sync force <joueurs|all> [rx rz]
        admin.then(Commands.literal("sync").then(Commands.literal("force")
                .then(Commands.literal("all")
                        .executes(ctx -> forceAll(ctx.getSource(), null))
                        .then(Commands.argument("rx", IntegerArgumentType.integer())
                                .then(Commands.argument("rz", IntegerArgumentType.integer())
                                        .executes(ctx -> forceAll(ctx.getSource(), new int[]{
                                                IntegerArgumentType.getInteger(ctx, "rx"),
                                                IntegerArgumentType.getInteger(ctx, "rz")})))))
                .then(Commands.argument("joueurs", EntityArgument.players())
                        .executes(ctx -> force(ctx.getSource(),
                                EntityArgument.getPlayers(ctx, "joueurs"), null))
                        .then(Commands.argument("rx", IntegerArgumentType.integer())
                                .then(Commands.argument("rz", IntegerArgumentType.integer())
                                        .executes(ctx -> force(ctx.getSource(),
                                                EntityArgument.getPlayers(ctx, "joueurs"), new int[]{
                                                        IntegerArgumentType.getInteger(ctx, "rx"),
                                                        IntegerArgumentType.getInteger(ctx, "rz")})))))));

        // rerender <rayonChunks>
        admin.then(Commands.literal("rerender")
                .then(Commands.argument("rayonChunks", IntegerArgumentType.integer(0, 32))
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            ServerPlayer player = src.getPlayerOrException();
                            MapManager mgr = MapManager.get();
                            if (mgr == null) return 0;
                            ServerLevel level = player.serverLevel();
                            int r = IntegerArgumentType.getInteger(ctx, "rayonChunks");
                            ChunkPos c = player.chunkPosition();
                            int count = 0;
                            for (int cx = c.x - r; cx <= c.x + r; cx++)
                                for (int cz = c.z - r; cz <= c.z + r; cz++) {
                                    mgr.enqueueChunk(level, cx, cz);
                                    count++;
                                }
                            final int total = count;
                            src.sendSuccess(() -> Component.literal(
                                    total + " chunk(s) mis en file de re-rendu"), true);
                            return count;
                        })));

        // layer <dim> <couche> <true|false>
        var layerCmd = Commands.literal("layer");
        for (MapLayer layer : MapLayer.values()) {
            layerCmd.then(Commands.argument("dimension", DimensionArgument.dimension())
                    .then(Commands.literal(layer.name().toLowerCase(Locale.ROOT))
                            .then(Commands.argument("actif", BoolArgumentType.bool())
                                    .executes(ctx -> {
                                        ServerLevel dim = DimensionArgument.getDimension(ctx, "dimension");
                                        boolean on = BoolArgumentType.getBool(ctx, "actif");
                                        ServerConfig.setLayer(dim.dimension(), layer, on);
                                        SyncService.broadcastLayerSettings(ctx.getSource().getServer());
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "Couche " + layer + " " + (on ? "activée" : "désactivée")
                                                        + " pour " + dim.dimension().location()), true);
                                        return 1;
                                    }))));
        }
        admin.then(layerCmd);

        // save
        admin.then(Commands.literal("save").executes(ctx -> {
            MapManager mgr = MapManager.get();
            if (mgr != null) mgr.saveAllAsync();
            ctx.getSource().sendSuccess(() -> Component.literal("Sauvegarde des régions lancée"), true);
            return 1;
        }));

        root.then(admin);
        dispatcher.register(root);
    }

    private static int forceAll(CommandSourceStack src, int[] region) {
        return force(src, src.getServer().getPlayerList().getPlayers(), region);
    }

    private static int force(CommandSourceStack src, Collection<ServerPlayer> players, int[] region) {
        int total = 0;
        for (ServerPlayer p : players) {
            int queued = SyncService.forceSync(p, region == null, region);
            total += queued;
            String suffix = region == null ? " (rayon complet)" : " (région " + region[0] + "," + region[1] + ")";
            src.sendSuccess(() -> Component.literal(
                    "Sync forcée pour " + p.getGameProfile().getName() + suffix
                            + " — " + queued + " région(s) en file"), true);
        }
        return total;
    }
}
