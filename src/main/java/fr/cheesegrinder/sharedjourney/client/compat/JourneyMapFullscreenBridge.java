package fr.cheesegrinder.sharedjourney.client.compat;

import fr.cheesegrinder.sharedjourney.api.MapLayer;
import fr.cheesegrinder.sharedjourney.client.gui.FullMapScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.phys.AABB;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;

/**
 * Volet "fullscreen" du bridge JourneyMap : publie, depuis NOTRE carte plein
 * écran, les événements que le vrai JourneyMap émet depuis la sienne —
 * FULLSCREEN_RENDER_EVENT à chaque frame et FULLSCREEN_MAP_CLICK_EVENT
 * (PRE annulable / POST) aux clics. C'est par ce canal que Create (carte des
 * trains) et Create: Rock & Stone (gisements découverts) dessinent leurs
 * overlays. L'IFullscreen fourni aux plugins est un proxy dynamique adossé à
 * notre FullMapScreen (centre, zoom, UIState).
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

    /** Publie l'événement de rendu fullscreen (une fois par frame). */
    public static void fireRender(FullMapScreen map, GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        if (!JourneyMapBridge.bridgeActive() || !resolve()) {
            return;
        }

        try {
            Object fullscreen = proxyFor(map);
            Object event = renderEventCtor.newInstance(fullscreen, gg, mouseX, mouseY, partialTick);
            JourneyMapBridge.dispatchToRegistry(
                    "journeymap.api.v2.common.event.FullscreenEventRegistry", "FULLSCREEN_RENDER_EVENT", event);
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Échec du FullscreenRenderEvent : {}", t.toString());
        }
    }

    /**
     * Publie l'événement de clic fullscreen. Retourne true si un plugin a
     * annulé le clic (stage PRE) : l'écran ne doit alors pas le traiter.
     */
    public static boolean fireClick(FullMapScreen map, boolean pre, double mouseX, double mouseY, int button) {
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
                    pre ? stagePre : stagePost,
                    pos,
                    mc.level.dimension(),
                    new Point2D.Double(mouseX, mouseY),
                    button);
            JourneyMapBridge.dispatchToRegistry(
                    "journeymap.api.v2.common.event.FullscreenEventRegistry", "FULLSCREEN_MAP_CLICK_EVENT", event);
            return Boolean.TRUE.equals(isCancelledMethod.invoke(event));
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Échec du FullscreenMapEvent.ClickEvent : {}", t.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------ résolution réflexive

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
            LOGGER.info("[Bridge JM] API fullscreen JourneyMap indisponible : {}", t.toString());
            available = false;
        }
        return available;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<Enum>) enumClass, name);
    }

    // ------------------------------------------------------------------ proxy IFullscreen

    private static Object proxyFor(FullMapScreen map) {
        return Proxy.newProxyInstance(
                fullscreenInterface.getClassLoader(), new Class<?>[] {fullscreenInterface}, new Handler(map));
    }

    /**
     * UIState (API v2) décrivant notre écran. Le paramètre int "zoom" du
     * constructeur vaut blockSize * 512, et blockSize est en PIXELS PHYSIQUES
     * par bloc : le fullscreen de JourneyMap ignore le gui scale, et ses
     * consommateurs (RNS, Create) redivisent par le gui scale au dessin.
     */
    private static Object buildUiState(FullMapScreen map) throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }

        double guiScale = mc.getWindow().getGuiScale();
        int zoom512 = (int) Math.round(map.zoomScale() * guiScale * 512.0);
        Object mapType = MAP_TYPES.get(map.currentLayer());
        BlockPos center = new BlockPos((int) Math.floor(map.centerX()), 0, (int) Math.floor(map.centerZ()));
        AABB blockBounds = new AABB(map.worldX(0), 0, map.worldZ(0), map.worldX(map.width), 0, map.worldZ(map.height));
        Rectangle2D.Double displayBounds =
                new Rectangle2D.Double(0, 0, map.width * guiScale, map.height * guiScale);
        return uiStateCtor.newInstance(
                uiFullscreen, true, mc.level.dimension(), zoom512, mapType, center, null, blockBounds, displayBounds);
    }

    private record Handler(FullMapScreen map) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getScreen" -> {
                    return map;
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
                    map.zoomInStep();
                    return null;
                }
                case "zoomOut" -> {
                    map.zoomOutStep();
                    return null;
                }
                case "close" -> {
                    map.onClose();
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
                    // updateMapType / toggleMapType : no-op tolérant.
                    return null;
                }
            }
        }
    }
}
