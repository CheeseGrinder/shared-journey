package fr.cheesegrinder.sharedjourney.client.command;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.event.ClientInputEvents;
import fr.cheesegrinder.sharedjourney.client.service.ClientMapCache;
import fr.cheesegrinder.sharedjourney.client.service.DiskCache;
import fr.cheesegrinder.sharedjourney.common.util.Lang;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

/**
 * CLIENT commands under the same /sharedjourney and /sj roots (spec §7).
 * The client dispatcher intercepts "/sj purge ..." and "/sj cache"; any other
 * "/sj ..." goes to the server (stats, admin, tp).
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class ClientCommands {

    private ClientCommands() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(buildRoot("sharedjourney"));
        event.getDispatcher().register(buildRoot("sj"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
        return Commands.literal(name)
                // /sj purge <layer|all>: deletes the local cache of a layer
                .then(Commands.literal("purge")
                        .then(Commands.argument("layer", StringArgumentType.word())
                                .suggests((ctx, sb) -> {
                                    sb.suggest("all");
                                    sb.suggest("day");
                                    sb.suggest("night");
                                    sb.suggest("topo");
                                    sb.suggest("biome");
                                    sb.suggest("cave");
                                    return sb.buildFuture();
                                })
                                .executes(ctx -> {
                                    String layer = StringArgumentType.getString(ctx, "layer");
                                    int deleted = DiskCache.purge(layer);
                                    ctx.getSource()
                                            .sendSuccess(
                                                    () -> Component.translatable(Lang.COMMAND_PURGED, deleted, layer),
                                                    false);
                                    return deleted;
                                })))
                // /sj goto <x> <z>: opens the map centered on the position
                // (used by the clickable message posted in the chat)
                .then(Commands.literal("goto")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            ClientInputEvents.openMapAt(
                                                    IntegerArgumentType.getInteger(ctx, "x") + 0.5,
                                                    IntegerArgumentType.getInteger(ctx, "z") + 0.5);
                                            return 1;
                                        }))))
                // /sj cache: local cache state
                .then(Commands.literal("cache").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    src.sendSuccess(
                            () -> Component.literal(
                                    "Local cache: " + DiskCache.index().size() + " region(s) on disk, "
                                            + ClientMapCache.loadedCount() + " texture(s) loaded, "
                                            + ClientMapCache.pendingCount() + " being received"),
                            false);
                    return 1;
                }));
    }
}
