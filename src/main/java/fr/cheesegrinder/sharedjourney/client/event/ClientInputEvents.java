package fr.cheesegrinder.sharedjourney.client.event;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.gui.screen.FullMapScreen;
import fr.cheesegrinder.sharedjourney.client.gui.screen.WaypointEditScreen;
import fr.cheesegrinder.sharedjourney.client.gui.screen.WaypointListScreen;
import fr.cheesegrinder.sharedjourney.client.render.MinimapRenderer;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;

import net.minecraft.client.Minecraft;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Keyboard input and deferred actions: consumes the shortcuts (declared in
 * {@link ClientSetupEvents}) and handles the deferred map opening.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class ClientInputEvents {

    public static boolean minimapVisible = true;

    /** Check cadence (ticks) for reached temp waypoints. */
    private static final int TEMP_WAYPOINT_CHECK_TICKS = 20;

    private static int tickCounter;

    /**
     * Deferred map opening target (/sj goto, click on a position message):
     * applied on the next tick so that the chat closing does not clobber
     * the screen we just opened.
     */
    private static volatile double[] pendingMapOpen = null;

    private ClientInputEvents() {}

    public static void openMapAt(double x, double z) {
        pendingMapOpen = new double[] {x, z};
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        double[] target = pendingMapOpen;
        if (target != null && mc.player != null) {
            pendingMapOpen = null;
            mc.setScreen(new FullMapScreen(target[0], target[1]));
        }
        while (ClientSetupEvents.OPEN_FULL_MAP.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new FullMapScreen());
            }
        }
        while (ClientSetupEvents.TOGGLE_MINIMAP.consumeClick()) {
            minimapVisible = !minimapVisible;
        }
        while (ClientSetupEvents.CYCLE_LAYER.consumeClick()) {
            MinimapRenderer.cycleLayer();
        }
        while (ClientSetupEvents.ZOOM_IN.consumeClick()) {
            MinimapRenderer.zoomIn();
        }
        while (ClientSetupEvents.ZOOM_OUT.consumeClick()) {
            MinimapRenderer.zoomOut();
        }
        while (ClientSetupEvents.OPEN_WAYPOINTS.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new WaypointListScreen(null));
            }
        }
        while (ClientSetupEvents.CREATE_WAYPOINT.consumeClick()) {
            if (mc.screen == null) {
                WaypointEditScreen.openCreateAtPlayer(null, Waypoint.GROUP_DEFAULT);
            }
        }

        tickCounter++;
        if (tickCounter >= TEMP_WAYPOINT_CHECK_TICKS && mc.player != null) {
            tickCounter = 0;
            WaypointStore.removeReachedTemp(mc.player);
        }
    }
}
