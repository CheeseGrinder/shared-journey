package fr.cheesegrinder.sharedjourney.client.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.client.render.MinimapRenderer;

import net.minecraft.client.Minecraft;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Vanilla HUD coexistence with the minimap: when it occupies the top-right
 * corner, the potion effect icons layer is translated to its left instead
 * of being covered.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class HudLayoutEvents {

    /** Push/pop balancing guard (Post does not fire if a mod cancels Pre). */
    private static boolean shifted;

    private HudLayoutEvents() {}

    @SubscribeEvent
    public static void onEffectsPre(RenderGuiLayerEvent.Pre event) {
        if (!VanillaGuiLayers.EFFECTS.equals(event.getName())) {
            return;
        }

        int shift = MinimapRenderer.effectIconsShift(Minecraft.getInstance());
        if (shift == 0) {
            return;
        }

        event.getGuiGraphics().pose().pushPose();
        event.getGuiGraphics().pose().translate(-shift, 0, 0);
        shifted = true;
    }

    @SubscribeEvent
    public static void onEffectsPost(RenderGuiLayerEvent.Post event) {
        if (!VanillaGuiLayers.EFFECTS.equals(event.getName())) {
            return;
        }

        if (shifted) {
            event.getGuiGraphics().pose().popPose();
            shifted = false;
        }
    }
}
