package fr.cheesegrinder.sharedjourney.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Commandes CLIENT sous la même racine /map (spec §7). Le dispatcher client
 * intercepte "/map purge ..." et "/map cache" ; tout autre "/map ..." passe
 * au serveur (stats, admin).
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class ClientCommands {

    private ClientCommands() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("map")
                // /map purge <layer|all> : supprime le cache local d'un calque
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
                // /map cache : état du cache local
                .then(Commands.literal("cache")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            src.sendSuccess(() -> Component.literal(
                                    "Cache local : " + DiskCache.index().size() + " région(s) sur disque, "
                                            + ClientMapCache.loadedCount() + " texture(s) chargée(s), "
                                            + ClientMapCache.pendingCount() + " en cours de réception"), false);
                            return 1;
                        })));
    }
}
