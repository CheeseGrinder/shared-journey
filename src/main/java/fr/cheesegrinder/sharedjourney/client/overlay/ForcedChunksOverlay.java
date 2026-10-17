package fr.cheesegrinder.sharedjourney.client.overlay;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
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

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Force-loaded chunks overlay (vanilla {@code /forceload} and NeoForge chunk
 * tickets). Built like an addon would be, on the public API only: a
 * {@link MapRenderEvent} listener draws the chunks, a
 * {@link FullMapToolbarEvent} listener contributes the toggle to the addon
 * zone of the fullscreen map (never mixed with the mod's own buttons) and a
 * {@link FullMapInfoBarEvent.Hover} listener names the hovered chunk. The
 * chunk data comes from the server ({@code ChunkOverlayPayloads}); nothing
 * is drawn nor requested when the server disallows the overlay or the
 * player turned it off ({@code map.showForcedChunks}).
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class ForcedChunksOverlay {

    /** Translucent orange. */
    private static final int COLOR = 0x60FF9A1F;

    private ForcedChunksOverlay() {}

    private static boolean active() {
        return ChunkOverlayStore.isAllowed(ChunkOverlayKind.FORCED_CHUNKS) && MapClientConfig.SHOW_FORCED_CHUNKS.get();
    }

    @SubscribeEvent
    public static void onMapRender(MapRenderEvent event) {
        if (active()) {
            ChunkOverlayRenderer.draw(event, ChunkOverlayKind.FORCED_CHUNKS, COLOR, 0f);
        }
    }

    @SubscribeEvent
    public static void onToolbar(FullMapToolbarEvent event) {
        if (!ChunkOverlayStore.isAllowed(ChunkOverlayKind.FORCED_CHUNKS)) {
            return;
        }

        event.addButton(FullMapToolbarEvent.ToolbarButton.toggle(
                FullMapToolbarEvent.Slot.TOP_LEFT,
                new ItemStack(Items.LODESTONE),
                Component.translatable(Lang.ACTION_SHOW_FORCED_CHUNKS),
                ForcedChunksOverlay::toggle,
                MapClientConfig.SHOW_FORCED_CHUNKS::get));
    }

    @SubscribeEvent
    public static void onHover(FullMapInfoBarEvent.Hover event) {
        if (active()
                && ChunkOverlayStore.isFlagged(
                        ChunkOverlayKind.FORCED_CHUNKS,
                        event.getView().dimension(),
                        event.getBlockX() >> 4,
                        event.getBlockZ() >> 4)) {
            event.addSegment(Component.translatable(Lang.OVERLAY_FORCED_CHUNK));
        }
    }

    private static void toggle() {
        MapClientConfig.SHOW_FORCED_CHUNKS.set(!MapClientConfig.SHOW_FORCED_CHUNKS.get());
        ClientConfig.SPEC.save();
    }
}
