package fr.cheesegrinder.sharedjourney.server.command;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.common.config.LayersServerConfig;
import fr.cheesegrinder.sharedjourney.common.util.Lang;
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
 * /sharedjourney and /sj (alias) roots — spec §7.
 *   /sj tp <x> <z>                              -> map teleport, Y computed server-side
 *   /sj stats                                   -> level 0: own sync stats
 *                                                  level 2: + engine state and everyone's stats
 *   /sj admin sync force <players|all> [rx rz]  -> forced resend (ignores the index)
 *   /sj admin rerender <chunkRadius>            -> re-renders around self
 *   /sj admin layer <dim> <layer> <true|false>  -> per-dimension layers, hot
 *   /sj admin save                              -> disk flush
 * (/sj purge <layer> is registered CLIENT-SIDE — see ClientCommands.)
 */
public final class MapCommands {

    private MapCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(buildRoot("sharedjourney"));
        dispatcher.register(buildRoot("sj"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name);

        // ---- /sj stats (all players: self; OP: everything)
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
                .then(Commands.argument("player", EntityArgument.player())
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                            ctx.getSource().sendSuccess(() -> Component.literal(SyncService.statsFor(p)), false);
                            return 1;
                        })));

        // ---- /sj tp <x> <z>: teleport from the map, Y computed server-side
        // (same permission level as the vanilla /tp).
        root.then(Commands.literal("tp")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> teleportToSurface(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z"))))));

        // ---- /sj admin ... (OP)
        LiteralArgumentBuilder<CommandSourceStack> admin =
                Commands.literal("admin").requires(src -> src.hasPermission(2));

        // sync force <players|all> [rx rz]
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
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(
                                        ctx -> force(ctx.getSource(), EntityArgument.getPlayers(ctx, "players"), null))
                                .then(Commands.argument("rx", IntegerArgumentType.integer())
                                        .then(Commands.argument("rz", IntegerArgumentType.integer())
                                                .executes(ctx -> force(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayers(ctx, "players"),
                                                        new int[] {
                                                            IntegerArgumentType.getInteger(ctx, "rx"),
                                                            IntegerArgumentType.getInteger(ctx, "rz")
                                                        })))))));

        // rerender <chunkRadius>: local re-render around the player, with
        // a private progress bar (RegenService local mode).
        admin.then(Commands.literal("rerender")
                .then(Commands.argument("chunkRadius", IntegerArgumentType.integer(0, 32))
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            ServerPlayer player = src.getPlayerOrException();
                            int r = IntegerArgumentType.getInteger(ctx, "chunkRadius");
                            int count = RegenService.startAround(player, r);
                            if (count < 0) {
                                src.sendFailure(Component.literal(
                                        RegenService.isRunning()
                                                ? "A regeneration is already running (/sj admin regen cancel to stop it)"
                                                : "Map engine unavailable"));
                                return 0;
                            }
                            if (count == 0) {
                                src.sendSuccess(() -> Component.literal("No painted chunks in this radius"), false);
                                return 0;
                            }

                            src.sendSuccess(() -> Component.literal(count + " chunk(s) queued for re-render"), true);
                            return count;
                        })));

        // layer <dim> <layer> <true|false>
        var layerCmd = Commands.literal("layer");
        for (MapLayer layer : MapLayer.values()) {
            // The INFO data layer is not a display layer: never toggleable.
            if (layer == MapLayer.INFO) {
                continue;
            }
            layerCmd.then(Commands.argument("dimension", DimensionArgument.dimension())
                    .then(Commands.literal(layer.name().toLowerCase(Locale.ROOT))
                            .then(Commands.argument("enabled", BoolArgumentType.bool())
                                    .executes(ctx -> {
                                        ServerLevel dim = DimensionArgument.getDimension(ctx, "dimension");
                                        boolean on = BoolArgumentType.getBool(ctx, "enabled");
                                        LayersServerConfig.setLayer(dim.dimension(), layer, on);
                                        SyncService.broadcastLayerSettings(
                                                ctx.getSource().getServer());
                                        ctx.getSource()
                                                .sendSuccess(
                                                        () -> Component.literal("Layer " + layer + " "
                                                                + (on ? "enabled" : "disabled") + " for "
                                                                + dim.dimension()
                                                                        .location()),
                                                        true);
                                        return 1;
                                    }))));
        }
        admin.then(layerCmd);

        // regen: re-renders the whole known map (boss bar progress)
        // regen full: renders ALL chunks generated on disk (pregenerated worlds)
        admin.then(Commands.literal("regen")
                .executes(ctx -> {
                    if (RegenService.isRunning()) {
                        ctx.getSource()
                                .sendFailure(Component.literal(
                                        "A regeneration is already running (/sj admin regen cancel to stop it)"));
                        return 0;
                    }
                    int total = RegenService.start(ctx.getSource().getServer());
                    if (total < 0) {
                        ctx.getSource().sendFailure(Component.literal("Map engine unavailable"));
                        return 0;
                    }
                    ctx.getSource()
                            .sendSuccess(
                                    () -> Component.literal(
                                            "Regeneration started: " + total + " chunk(s) to re-render"),
                                    true);
                    return total;
                })
                .then(Commands.literal("full").executes(ctx -> {
                    if (RegenService.isRunning()) {
                        ctx.getSource()
                                .sendFailure(Component.literal(
                                        "A regeneration is already running (/sj admin regen cancel to stop it)"));
                        return 0;
                    }
                    if (!RegenService.startFull(ctx.getSource().getServer())) {
                        ctx.getSource().sendFailure(Component.literal("Map engine unavailable"));
                        return 0;
                    }
                    ctx.getSource()
                            .sendSuccess(
                                    () -> Component.literal(
                                            "Region file scan started — rendering begins once the scan completes"),
                                    true);
                    return 1;
                }))
                .then(Commands.literal("cancel").executes(ctx -> {
                    if (!RegenService.isRunning()) {
                        ctx.getSource().sendFailure(Component.literal("No regeneration running"));
                        return 0;
                    }
                    RegenService.cancel();
                    ctx.getSource().sendSuccess(() -> Component.literal("Regeneration cancelled"), true);
                    return 1;
                })));

        // save
        admin.then(Commands.literal("save").executes(ctx -> {
            MapManager mgr = MapManager.get();
            if (mgr != null) {
                mgr.saveAllAsync();
            }

            ctx.getSource().sendSuccess(() -> Component.literal("Region save started"), true);
            return 1;
        }));

        root.then(admin);
        return root;
    }

    /**
     * Teleports the player to the coordinates clicked on the map, with an
     * arrival Y ALWAYS computed server-side: the client does not necessarily
     * have the target chunk locally, and a "~" would keep the flight
     * altitude (arriving inside rock or in mid-air). The synchronous chunk
     * load is assumed: one-off command, and the teleport would load it
     * anyway.
     */
    private static int teleportToSurface(CommandSourceStack src, int x, int z) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
        int y = arrivalY(level, chunk, x, z);
        player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot());
        src.sendSuccess(() -> Component.translatable(Lang.COMMAND_TELEPORTED, x, y, z), true);
        return 1;
    }

    /**
     * Arrival Y: surface (MOTION_BLOCKING heightmap, +1 to stand on top).
     * In ceiling dimensions (Nether), the heightmap returns the bedrock
     * roof: instead, scan from the logical ceiling downwards for a
     * non-liquid floor topped by 2 air blocks.
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
            String suffix = region == null ? " (full radius)" : " (region " + region[0] + "," + region[1] + ")";
            src.sendSuccess(
                    () -> Component.literal("Forced sync for "
                            + p.getGameProfile().getName() + suffix + " — " + queued + " region(s) queued"),
                    true);
        }
        return total;
    }
}
