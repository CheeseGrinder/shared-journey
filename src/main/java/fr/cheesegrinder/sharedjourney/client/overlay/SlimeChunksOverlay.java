package fr.cheesegrinder.sharedjourney.client.overlay;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.client.MapView;
import fr.cheesegrinder.sharedjourney.api.client.event.FullMapInfoBarEvent;
import fr.cheesegrinder.sharedjourney.api.client.event.FullMapToolbarEvent;
import fr.cheesegrinder.sharedjourney.api.client.event.MapRenderEvent;
import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.config.MapClientConfig;
import fr.cheesegrinder.sharedjourney.common.overlay.ChunkOverlayKind;
import fr.cheesegrinder.sharedjourney.common.util.Lang;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Slime chunks overlay (overworld only). Built like an addon would be, on
 * the public API only — see {@link ForcedChunksOverlay}. The slime chunks
 * are computed by the server from the world seed, which is never sent: the
 * client only receives the resulting chunk bitmaps of regions it already
 * explored. Nothing is drawn nor requested when the server disallows the
 * overlay or the player turned it off ({@code map.showSlimeChunks}).
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class SlimeChunksOverlay {

    /** Translucent green. */
    private static final int COLOR = 0x5040E060;

    /**
     * Below this zoom (GUI px per block) a chunk is under 4 px wide: the
     * overlay is not drawn nor requested (a zoomed-out fullscreen map would
     * need thousands of rectangles and hundreds of regions).
     */
    private static final float MIN_ZOOM = 0.25f;

    private SlimeChunksOverlay() {}

    private static boolean active() {
        return ChunkOverlayStore.isAllowed(ChunkOverlayKind.SLIME_CHUNKS) && MapClientConfig.SHOW_SLIME_CHUNKS.get();
    }

    private static boolean inOverworld(MapView view) {
        return Level.OVERWORLD.location().equals(view.dimension());
    }

    @SubscribeEvent
    public static void onMapRender(MapRenderEvent event) {
        if (active() && inOverworld(event.getView())) {
            ChunkOverlayRenderer.draw(event, ChunkOverlayKind.SLIME_CHUNKS, COLOR, MIN_ZOOM);
        }
    }

    @SubscribeEvent
    public static void onToolbar(FullMapToolbarEvent event) {
        if (!ChunkOverlayStore.isAllowed(ChunkOverlayKind.SLIME_CHUNKS) || !inOverworld(event.getView())) {
            return;
        }

        event.addButton(FullMapToolbarEvent.ToolbarButton.toggle(
                FullMapToolbarEvent.Slot.TOP_LEFT,
                new ItemStack(Items.SLIME_BALL),
                Component.translatable(Lang.ACTION_SHOW_SLIME_CHUNKS),
                SlimeChunksOverlay::toggle,
                MapClientConfig.SHOW_SLIME_CHUNKS::get));
    }

    @SubscribeEvent
    public static void onHover(FullMapInfoBarEvent.Hover event) {
        if (active()
                && inOverworld(event.getView())
                && ChunkOverlayStore.isFlagged(
                        ChunkOverlayKind.SLIME_CHUNKS,
                        event.getView().dimension(),
                        event.getBlockX() >> 4,
                        event.getBlockZ() >> 4)) {
            event.addSegment(Component.translatable(Lang.OVERLAY_SLIME_CHUNK));
        }
    }

    private static void toggle() {
        MapClientConfig.SHOW_SLIME_CHUNKS.set(!MapClientConfig.SHOW_SLIME_CHUNKS.get());
        ClientConfig.SPEC.save();
    }
}
