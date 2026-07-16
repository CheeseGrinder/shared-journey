package fr.cheesegrinder.sharedjourney.client.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.common.util.Lang;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes "[x, z]" coordinates in incoming chat clickable: clicking opens
 * the fullscreen map centered there (client command /sj goto). The mod
 * never sends chat by itself — the fullscreen map pre-fills the chat
 * input with this pattern (JourneyMap style) and the player sends the
 * message like any other, so decorating received messages is enough for
 * both the sender's echo and the other players.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class ChatCoordEvents {

    /** Shared position pattern: "[x, z]" with plain integer coordinates. */
    private static final Pattern COORDS = Pattern.compile("\\[(-?\\d{1,8}), ?(-?\\d{1,8})\\]");

    private ChatCoordEvents() {}

    @SubscribeEvent
    public static void onChatReceived(ClientChatReceivedEvent event) {
        Component message = event.getMessage();
        if (!COORDS.matcher(message.getString()).find()) {
            return;
        }

        event.setMessage(linkify(message));
    }

    /**
     * Rebuilds the component, restyling every "[x, z]" occurrence as a
     * clickable link. Non-matching text keeps its original style; nested
     * component structure is flattened, which is harmless for chat lines.
     */
    private static Component linkify(Component message) {
        MutableComponent result = Component.empty();
        message.visit(
                (style, text) -> {
                    appendSegment(result, text, style);
                    return Optional.<Object>empty();
                },
                Style.EMPTY);
        return result;
    }

    /** Splits one styled text run around the coordinate matches. */
    private static void appendSegment(MutableComponent result, String text, Style style) {
        Matcher matcher = COORDS.matcher(text);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                result.append(
                        Component.literal(text.substring(last, matcher.start())).withStyle(style));
            }

            result.append(coordLink(matcher.group(), matcher.group(1), matcher.group(2), style));
            last = matcher.end();
        }

        if (last < text.length()) {
            result.append(Component.literal(text.substring(last)).withStyle(style));
        }
    }

    /** The "[x, z]" run as an aqua underlined link opening the map there. */
    private static Component coordLink(String label, String x, String z, Style base) {
        Style style = base.withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sj goto " + x + " " + z))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(Lang.COORDS_OPEN)));
        return Component.literal(label).withStyle(style);
    }
}
