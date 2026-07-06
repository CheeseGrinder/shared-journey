package fr.cheesegrinder.sharedjourney.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Commandes CLIENT sous les mêmes racines /sharedjourney et /sj (spec §7).
 * Le dispatcher client intercepte "/sj purge ..." et "/sj cache" ; tout autre
 * "/sj ..." passe au serveur (stats, admin).
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
                // /sj purge <layer|all> : supprime le cache local d'un calque
                .then(Commands.literal("purge")
                        .then(Commands.argument("layer", StringArgumentType.word())
                                .suggests((ctx, sb) -> {
                                    sb.suggest("all");
                                    sb.suggest("day"); sb.suggest("night");
                                    sb.suggest("topo"); sb.suggest("biome"); sb.suggest("cave");
                                    return sb.buildFuture();
                                })
                                .executes(ctx -> {
                                    String layer = StringArgumentType.getString(ctx, "layer");
                                    int deleted = DiskCache.purge(layer);
                                    ctx.getSource().sendSuccess(() -> Component.translatable(
                                            "sharedjourney.command.purged", deleted, layer), false);
                                    return deleted;
                                })))
                // /sj goto <x> <z> : ouvre la carte centrée sur la position
                // (utilisé par le message cliquable posté dans le chat)
                .then(Commands.literal("goto")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            ClientEvents.openMapAt(
                                                    IntegerArgumentType.getInteger(ctx, "x") + 0.5,
                                                    IntegerArgumentType.getInteger(ctx, "z") + 0.5);
                                            return 1;
                                        }))))
                // /sj cache : état du cache local
                .then(Commands.literal("cache")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            src.sendSuccess(() -> Component.literal(
                                    "Cache local : " + DiskCache.index().size() + " région(s) sur disque, "
                                            + ClientMapCache.loadedCount() + " texture(s) chargée(s), "
                                            + ClientMapCache.pendingCount() + " en cours de réception"), false);
                            return 1;
                        }));
    }
}
