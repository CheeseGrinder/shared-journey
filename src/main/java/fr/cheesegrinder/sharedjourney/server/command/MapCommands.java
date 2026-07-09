package fr.cheesegrinder.sharedjourney.server.command;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.config.LayersServerConfig;
import fr.cheesegrinder.sharedjourney.server.service.MapManager;
import fr.cheesegrinder.sharedjourney.server.service.RegenService;
import fr.cheesegrinder.sharedjourney.server.service.SyncService;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * Racines /sharedjourney et /sj (alias) — spec §7.
 *   /sj stats                                   -> niveau 0 : ses propres stats de sync
 *                                                  niveau 2 : + état moteur et stats de tous
 *   /sj admin sync force <joueurs|all> [rx rz]  -> renvoi forcé (ignore l'index)
 *   /sj admin rerender <rayonChunks>            -> re-rend autour de soi
 *   /sj admin layer <dim> <couche> <true|false> -> couches par dimension à chaud
 *   /sj admin save                              -> flush disque
 * (/sj purge <layer> est enregistré CÔTÉ CLIENT — voir ClientCommands.)
 */
public final class MapCommands {

    private MapCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(buildRoot("sharedjourney"));
        dispatcher.register(buildRoot("sj"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name);

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
                            ctx.getSource().sendSuccess(() -> Component.literal(SyncService.statsFor(p)), false);
                            return 1;
                        })));

        // ---- /sj tp <x> <z> : téléportation depuis la carte, Y calculé
        // côté serveur (même niveau de permission que le /tp vanilla).
        root.then(Commands.literal("tp")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> teleportToSurface(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z"))))));

        // ---- /map admin ... (OP)
        LiteralArgumentBuilder<CommandSourceStack> admin =
                Commands.literal("admin").requires(src -> src.hasPermission(2));

        // sync force <joueurs|all> [rx rz]
        admin.then(Commands.literal("sync")
                .then(Commands.literal("force")
                        .then(Commands.literal("all")
                                .executes(ctx -> forceAll(ctx.getSource(), null))
                                .then(Commands.argument("rx", IntegerArgumentType.integer())
                                        .then(Commands.argument("rz", IntegerArgumentType.integer())
                                                .executes(ctx -> forceAll(ctx.getSource(), new int[] {
                                                    IntegerArgumentType.getInteger(ctx, "rx"),
                                                    IntegerArgumentType.getInteger(ctx, "rz")
                                                })))))
                        .then(Commands.argument("joueurs", EntityArgument.players())
                                .executes(
                                        ctx -> force(ctx.getSource(), EntityArgument.getPlayers(ctx, "joueurs"), null))
                                .then(Commands.argument("rx", IntegerArgumentType.integer())
                                        .then(Commands.argument("rz", IntegerArgumentType.integer())
                                                .executes(ctx -> force(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayers(ctx, "joueurs"),
                                                        new int[] {
                                                            IntegerArgumentType.getInteger(ctx, "rx"),
                                                            IntegerArgumentType.getInteger(ctx, "rz")
                                                        })))))));

        // rerender <rayonChunks>
        admin.then(Commands.literal("rerender")
                .then(Commands.argument("rayonChunks", IntegerArgumentType.integer(0, 32))
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            ServerPlayer player = src.getPlayerOrException();
                            MapManager mgr = MapManager.get();
                            if (mgr == null) {
                                return 0;
                            }

                            ServerLevel level = player.serverLevel();
                            int r = IntegerArgumentType.getInteger(ctx, "rayonChunks");
                            ChunkPos c = player.chunkPosition();
                            int count = 0;
                            for (int cx = c.x - r; cx <= c.x + r; cx++) {
                                for (int cz = c.z - r; cz <= c.z + r; cz++) {
                                    mgr.enqueueChunk(level, cx, cz);
                                    count++;
                                }
                            }
                            final int total = count;
                            src.sendSuccess(() -> Component.literal(total + " chunk(s) mis en file de re-rendu"), true);
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
                                        LayersServerConfig.setLayer(dim.dimension(), layer, on);
                                        SyncService.broadcastLayerSettings(
                                                ctx.getSource().getServer());
                                        ctx.getSource()
                                                .sendSuccess(
                                                        () -> Component.literal("Couche " + layer + " "
                                                                + (on ? "activée" : "désactivée") + " pour "
                                                                + dim.dimension()
                                                                        .location()),
                                                        true);
                                        return 1;
                                    }))));
        }
        admin.then(layerCmd);

        // regen : re-rend toute la carte connue (progression en boss bar)
        // regen full : rend TOUS les chunks générés sur disque (mondes prégénérés)
        admin.then(Commands.literal("regen")
                .executes(ctx -> {
                    if (RegenService.isRunning()) {
                        ctx.getSource()
                                .sendFailure(Component.literal(
                                        "Une régénération est déjà en cours (/sj admin regen cancel pour l'annuler)"));
                        return 0;
                    }
                    int total = RegenService.start(ctx.getSource().getServer());
                    if (total < 0) {
                        ctx.getSource().sendFailure(Component.literal("Moteur de carte indisponible"));
                        return 0;
                    }
                    ctx.getSource()
                            .sendSuccess(
                                    () -> Component.literal("Régénération lancée : " + total + " chunk(s) à re-rendre"),
                                    true);
                    return total;
                })
                .then(Commands.literal("full").executes(ctx -> {
                    if (RegenService.isRunning()) {
                        ctx.getSource()
                                .sendFailure(Component.literal(
                                        "Une régénération est déjà en cours (/sj admin regen cancel pour l'annuler)"));
                        return 0;
                    }
                    if (!RegenService.startFull(ctx.getSource().getServer())) {
                        ctx.getSource().sendFailure(Component.literal("Moteur de carte indisponible"));
                        return 0;
                    }
                    ctx.getSource()
                            .sendSuccess(
                                    () -> Component.literal(
                                            "Scan des fichiers de région lancé — le rendu démarre dès la fin du scan"),
                                    true);
                    return 1;
                }))
                .then(Commands.literal("cancel").executes(ctx -> {
                    if (!RegenService.isRunning()) {
                        ctx.getSource().sendFailure(Component.literal("Aucune régénération en cours"));
                        return 0;
                    }
                    RegenService.cancel();
                    ctx.getSource().sendSuccess(() -> Component.literal("Régénération annulée"), true);
                    return 1;
                })));

        // save
        admin.then(Commands.literal("save").executes(ctx -> {
            MapManager mgr = MapManager.get();
            if (mgr != null) {
                mgr.saveAllAsync();
            }

            ctx.getSource().sendSuccess(() -> Component.literal("Sauvegarde des régions lancée"), true);
            return 1;
        }));

        root.then(admin);
        return root;
    }

    /**
     * Téléporte le joueur aux coordonnées cliquées sur la carte, avec un Y
     * d'arrivée TOUJOURS calculé côté serveur : le client n'a pas forcément
     * le chunk cible en local, et un « ~ » conserverait l'altitude de vol
     * (arrivée dans la roche ou en plein ciel). Le chargement synchrone du
     * chunk est assumé : commande ponctuelle, et la téléportation le
     * chargerait de toute façon.
     */
    private static int teleportToSurface(CommandSourceStack src, int x, int z) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
        int y = arrivalY(level, chunk, x, z);
        player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot());
        src.sendSuccess(() -> Component.literal("Téléporté en " + x + " " + y + " " + z), true);
        return 1;
    }

    /**
     * Y d'arrivée : surface (heightmap MOTION_BLOCKING, +1 pour être posé
     * dessus). Dans les dimensions à plafond (Nether), la heightmap renvoie
     * le toit de bedrock : on cherche à la place, du plafond logique vers le
     * bas, un sol non liquide surmonté de 2 blocs d'air.
     */
    private static int arrivalY(ServerLevel level, ChunkAccess chunk, int x, int z) {
        int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
        if (!level.dimensionType().hasCeiling()) {
            return surface;
        }

        int top = level.getMinBuildHeight() + level.dimensionType().logicalHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int airRun = 0;
        for (int yy = top - 1; yy > level.getMinBuildHeight(); yy--) {
            BlockState state = chunk.getBlockState(pos.set(x, yy, z));
            if (state.isAir()) {
                airRun++;
                continue;
            }
            if (airRun >= 2 && state.getFluidState().isEmpty()) {
                return yy + 1;
            }
            airRun = 0;
        }
        return surface;
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
            src.sendSuccess(
                    () -> Component.literal("Sync forcée pour "
                            + p.getGameProfile().getName() + suffix + " — " + queued + " région(s) en file"),
                    true);
        }
        return total;
    }
}
