package fr.cheesegrinder.sharedjourney.client.compat;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.api.client.MapView;
import fr.cheesegrinder.sharedjourney.client.config.MapClientConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import org.slf4j.Logger;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * "Fullscreen" part of the JourneyMap bridge: publishes, from OUR
 * fullscreen map, the events the real JourneyMap emits from its own —
 * FULLSCREEN_RENDER_EVENT every frame and FULLSCREEN_MAP_CLICK_EVENT
 * (cancellable PRE / POST) on clicks. This is the channel Create (train
 * map) and Create: Rock & Stone (discovered deposits) use to draw their
 * overlays. The IFullscreen handed to plugins is a dynamic proxy backed
 * by our FullMapScreen (center, zoom, UIState).
 */
public final class JourneyMapFullscreenBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean resolved;
    private static boolean available;

    private static Class<?> fullscreenInterface;
    private static Constructor<?> renderEventCtor;
    private static Constructor<?> clickEventCtor;
    private static Constructor<?> uiStateCtor;
    private static Method isCancelledMethod;
    private static Object uiFullscreen;
    private static Object stagePre;
    private static Object stagePost;
    private static final Map<MapLayer, Object> MAP_TYPES = new EnumMap<>(MapLayer.class);

    private JourneyMapFullscreenBridge() {}

    /**
     * Map view exposed to plugins via the IFullscreen proxy: the
     * fullscreen map implements it directly, the minimap provides an ad
     * hoc view. Extends the public {@link MapView} (geometry, layer) with
     * the navigation actions JourneyMap's IFullscreen expects.
     */
    public interface BridgedMapView extends MapView {

        Screen screen();

        default void centerOn(double x, double z) {}

        default void zoomIn() {}

        default void zoomOut() {}

        default void close() {}
    }

    /** Publishes the fullscreen render event (once per frame). */
    public static void fireRender(BridgedMapView map, GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        if (!JourneyMapBridge.bridgeActive() || !resolve()) {
            return;
        }

        try {
            Object fullscreen = proxyFor(map);
            Object event = renderEventCtor.newInstance(fullscreen, gg, mouseX, mouseY, partialTick);
            JourneyMapBridge.dispatchToRegistry(
                    "journeymap.api.v2.common.event.FullscreenEventRegistry",
                    "FULLSCREEN_RENDER_EVENT",
                    event,
                    overlayFilter());
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] FullscreenRenderEvent failed: {}", t.toString());
        }
    }

    /** Client config overlay toggles, by plugin modId. */
    private static Predicate<String> overlayFilter() {
        return modId -> switch (modId) {
            case "create" -> MapClientConfig.SHOW_TRAIN_OVERLAY.get();
            case "create_rns" -> MapClientConfig.SHOW_DEPOSIT_OVERLAY.get();
            default -> true;
        };
    }

    /**
     * Publishes the fullscreen click event. Returns true if a plugin
     * cancelled the click (PRE stage): the screen must then not handle it.
     */
    public static boolean fireClick(BridgedMapView map, boolean pre, double mouseX, double mouseY, int button) {
        if (!JourneyMapBridge.bridgeActive() || !resolve()) {
            return false;
        }

        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }

        try {
            BlockPos pos = new BlockPos((int) Math.floor(map.worldX(mouseX)), 0, (int) Math.floor(map.worldZ(mouseY)));
            Object event = clickEventCtor.newInstance(
                    pre ? stagePre : stagePost, pos, mc.level.dimension(), new Point2D.Double(mouseX, mouseY), button);
            JourneyMapBridge.dispatchToRegistry(
                    "journeymap.api.v2.common.event.FullscreenEventRegistry",
                    "FULLSCREEN_MAP_CLICK_EVENT",
                    event,
                    overlayFilter());
            return Boolean.TRUE.equals(isCancelledMethod.invoke(event));
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] FullscreenMapEvent.ClickEvent failed: {}", t.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------ reflective resolution

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }

        resolved = true;
        try {
            fullscreenInterface = Class.forName("journeymap.api.v2.client.fullscreen.IFullscreen");
            Class<?> renderEventClass = Class.forName("journeymap.api.v2.client.event.FullscreenRenderEvent");
            Class<?> uiStateClass = Class.forName("journeymap.api.v2.client.util.UIState");
            Class<?> uiClass = Class.forName("journeymap.api.v2.client.display.Context$UI");
            Class<?> mapTypeClass = Class.forName("journeymap.api.v2.client.display.Context$MapType");
            Class<?> clickClass = Class.forName("journeymap.api.v2.client.event.FullscreenMapEvent$ClickEvent");
            Class<?> stageClass = Class.forName("journeymap.api.v2.client.event.FullscreenMapEvent$Stage");

            renderEventCtor = renderEventClass.getConstructor(
                    fullscreenInterface, GuiGraphics.class, int.class, int.class, float.class);
            uiStateCtor = uiStateClass.getConstructor(
                    uiClass,
                    boolean.class,
                    ResourceKey.class,
                    int.class,
                    mapTypeClass,
                    BlockPos.class,
                    Integer.class,
                    AABB.class,
                    Rectangle2D.Double.class);
            clickEventCtor = clickClass.getConstructor(
                    stageClass, BlockPos.class, ResourceKey.class, Point2D.Double.class, int.class);
            isCancelledMethod = clickClass.getMethod("isCancelled");
            uiFullscreen = enumConstant(uiClass, "Fullscreen");
            stagePre = enumConstant(stageClass, "PRE");
            stagePost = enumConstant(stageClass, "POST");
            MAP_TYPES.put(MapLayer.DAY, enumConstant(mapTypeClass, "Day"));
            MAP_TYPES.put(MapLayer.NIGHT, enumConstant(mapTypeClass, "Night"));
            MAP_TYPES.put(MapLayer.TOPO, enumConstant(mapTypeClass, "Topo"));
            MAP_TYPES.put(MapLayer.BIOME, enumConstant(mapTypeClass, "Biome"));
            MAP_TYPES.put(MapLayer.CAVE, enumConstant(mapTypeClass, "Underground"));
            available = true;
        } catch (Throwable t) {
            LOGGER.info("[Bridge JM] JourneyMap fullscreen API unavailable: {}", t.toString());
            available = false;
        }
        return available;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<Enum>) enumClass, name);
    }

    /**
     * Publishes the FullscreenRenderEvent for the MINIMAP: plugins (Create
     * rails and trains, RNS deposits) only draw on the "Fullscreen" UI; so
     * we present the minimap to them as a fullscreen whose screen center
     * is mapped, via a pose translation, onto the minimap's center. The
     * minimap's scissor (active at call time) bounds the drawing. In
     * rotation mode, the icons turn with the map (accepted limitation).
     */
    public static void fireMinimapRender(
            GuiGraphics gg, int cx, int cy, double px, double pz, float zoom, MapLayer layer, float rotationDeg) {
        if (!JourneyMapBridge.bridgeActive() || !resolve()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        Screen dummy = new Screen(Component.empty()) {};
        dummy.width = sw;
        dummy.height = sh;
        BridgedMapView view = new MinimapView(dummy, sw, sh, px, pz, zoom, layer);
        var pose = gg.pose();
        pose.pushPose();
        if (rotationDeg != 0f) {
            pose.translate(cx, cy, 0);
            pose.mulPose(Axis.ZP.rotationDegrees(rotationDeg));
            pose.translate(-cx, -cy, 0);
        }
        pose.translate(cx - sw / 2f, cy - sh / 2f, 0);
        fireRender(view, gg, 0, 0, 0f);
        pose.popPose();
    }

    /** Minimap view: center = player, dimensions = screen (translated pose). */
    private record MinimapView(
            Screen screen,
            int viewWidth,
            int viewHeight,
            double centerX,
            double centerZ,
            float zoomScale,
            MapLayer currentLayer)
            implements BridgedMapView {

        @Override
        public boolean isMinimap() {
            return true;
        }

        @Override
        public ResourceLocation dimension() {
            var mc = Minecraft.getInstance();
            return mc.level != null ? mc.level.dimension().location() : Level.OVERWORLD.location();
        }

        @Override
        public int caveBand() {
            // The bridge never presents the CAVE layer to JM plugins.
            return 0;
        }
    }

    // ------------------------------------------------------------------ IFullscreen proxy

    private static Object proxyFor(BridgedMapView map) {
        return Proxy.newProxyInstance(
                fullscreenInterface.getClassLoader(), new Class<?>[] {fullscreenInterface}, new Handler(map));
    }

    /**
     * UIState (API v2) describing our screen. The constructor's int
     * "zoom" parameter equals blockSize * 512, and blockSize is in
     * PHYSICAL PIXELS per block: JourneyMap's fullscreen ignores the GUI
     * scale, and its consumers (RNS, Create) divide back by the GUI scale
     * when drawing.
     */
    private static Object buildUiState(BridgedMapView map) throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }

        double guiScale = mc.getWindow().getGuiScale();
        int zoom512 = (int) Math.round(map.zoomScale() * guiScale * 512.0);
        Object mapType = MAP_TYPES.get(map.currentLayer());
        BlockPos center = new BlockPos((int) Math.floor(map.centerX()), 0, (int) Math.floor(map.centerZ()));
        AABB blockBounds =
                new AABB(map.worldX(0), 0, map.worldZ(0), map.worldX(map.viewWidth()), 0, map.worldZ(map.viewHeight()));
        Rectangle2D.Double displayBounds =
                new Rectangle2D.Double(0, 0, map.viewWidth() * guiScale, map.viewHeight() * guiScale);
        return uiStateCtor.newInstance(
                uiFullscreen, true, mc.level.dimension(), zoom512, mapType, center, null, blockBounds, displayBounds);
    }

    private record Handler(BridgedMapView map) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getScreen" -> {
                    return map.screen();
                }
                case "getMinecraft" -> {
                    return Minecraft.getInstance();
                }
                case "getUiState" -> {
                    return buildUiState(map);
                }
                case "getCenterBlockX" -> {
                    return map.centerX();
                }
                case "getCenterBlockZ" -> {
                    return map.centerZ();
                }
                case "centerOn" -> {
                    map.centerOn((Double) args[0], (Double) args[1]);
                    return null;
                }
                case "zoomIn" -> {
                    map.zoomIn();
                    return null;
                }
                case "zoomOut" -> {
                    map.zoomOut();
                    return null;
                }
                case "close" -> {
                    map.close();
                    return null;
                }
                case "getMouseDrag" -> {
                    return new Point2D.Double(0, 0);
                }
                case "toString" -> {
                    return "SharedJourney IFullscreen bridge";
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                default -> {
                    // updateMapType / toggleMapType: tolerant no-op.
                    return null;
                }
            }
        }
    }
}
