package fr.cheesegrinder.sharedjourney.client.compat;

import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import net.neoforged.fml.ModList;

import com.mojang.logging.LogUtils;
import org.objectweb.asm.Type;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * JourneyMap compatibility bridge (spec §9).
 *
 * Goal: third-party mods that integrate with JourneyMap via its API
 * (e.g. Waystones) work with SharedJourney WITHOUT modification.
 *
 * How it works:
 *  1. If the real JourneyMap is present, the bridge disables itself (JM handles it).
 *  2. Otherwise, scan every mod's annotation data looking for
 *     @JourneyMapPlugin (v2 then v1), exactly like JourneyMap itself does.
 *  3. Each plugin found is instantiated and initialized with a DYNAMIC
 *     PROXY of IClientAPI (java.lang.reflect.Proxy): no compile-time
 *     dependency on the JourneyMap API, and tolerance to signature
 *     variations across API versions.
 *  4. "Waypoint"-type calls are translated to our WaypointStore
 *     (source = the plugin's modId); overlays/events are logged and
 *     cleanly ignored (see README §Bridge for the limitations).
 *
 * IMPORTANT: the JourneyMap API classes must be present at runtime for
 * third-party mods' plugin classes to load (they reference them). See the
 * README: API jar in mods/ or jarJar.
 */
public final class JourneyMapBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Known plugin annotations (v2: @JourneyMapPlugin; v1/legacy: @ClientPlugin). */
    private static final String[] PLUGIN_ANNOTATIONS = {
        "Ljourneymap/api/v2/client/JourneyMapPlugin;", "Ljourneymap/client/api/ClientPlugin;"
    };

    /** Corresponding IClientAPI interfaces. */
    private static final String[] CLIENT_API_INTERFACES = {
        "journeymap.api.v2.client.IClientAPI", "journeymap.client.api.IClientAPI"
    };

    private static boolean initialized;
    private static final List<String> loadedPlugins = new ArrayList<>();
    /** IClientAPI handlers by modId: used to resync mutated waypoints. */
    private static final Map<String, ClientApiHandler> HANDLERS = new ConcurrentHashMap<>();

    private JourneyMapBridge() {}

    public static List<String> loadedPlugins() {
        return List.copyOf(loadedPlugins);
    }

    /** Call after every mod has been constructed (FMLLoadCompleteEvent, client). */
    public static void init() {
        if (initialized) {
            return;
        }

        initialized = true;

        if (isRealJourneyMapPresent()) {
            LOGGER.info("[Bridge JM] JourneyMap is installed: the SharedJourney bridge disables itself.");
            return;
        }

        // Before initializing the plugins: the API's static WaypointFactory
        // must exist, otherwise their waypoint creations crash.
        installWaypointFactory();

        for (int i = 0; i < PLUGIN_ANNOTATIONS.length; i++) {
            Class<?> apiInterface = tryLoad(CLIENT_API_INTERFACES[i]);
            // This API version is not on the classpath.
            if (apiInterface == null) {
                continue;
            }

            Set<String> pluginClasses = scanPlugins(PLUGIN_ANNOTATIONS[i]);
            for (String cls : pluginClasses) {
                initializePlugin(cls, apiInterface);
            }
        }

        if (loadedPlugins.isEmpty()) {
            LOGGER.info(
                    "[Bridge JM] No third-party JourneyMap plugin detected (or JourneyMap API absent from the classpath).");
        } else {
            LOGGER.info(
                    "[Bridge JM] {} JourneyMap plugin(s) initialized via SharedJourney: {}",
                    loadedPlugins.size(),
                    loadedPlugins);
        }
    }

    /** Is the bridge active (no real JourneyMap, at least one plugin)? */
    static boolean bridgeActive() {
        return initialized && !loadedPlugins.isEmpty();
    }

    /**
     * Publishes a MappingEvent (API v2) to plugins subscribed via the
     * API's STATIC registry (ClientEventRegistry.MAPPING_EVENT) —
     * subscriptions don't go through IClientAPI, so not through our proxy.
     * The real JourneyMap publishes MAPPING_STARTED/MAPPING_STOPPED on
     * world entry/exit; some plugins (Waystones) wait for MAPPING_STARTED
     * before creating any waypoint at all.
     */
    public static void fireMappingEvent(boolean started, ResourceKey<Level> dimension) {
        if (!bridgeActive()) {
            return;
        }

        try {
            Class<?> eventClass = tryLoad("journeymap.api.v2.client.event.MappingEvent");
            Class<?> stageClass = tryLoad("journeymap.api.v2.client.event.MappingEvent$Stage");
            if (eventClass == null || stageClass == null) {
                return;
            }

            @SuppressWarnings({"unchecked", "rawtypes"})
            Object stage = Enum.valueOf((Class<Enum>) stageClass, started ? "MAPPING_STARTED" : "MAPPING_STOPPED");
            Object event = eventClass
                    .getConstructor(stageClass, ResourceKey.class, String.class)
                    .newInstance(stage, dimension, null);
            dispatchToRegistry("journeymap.api.v2.common.event.ClientEventRegistry", "MAPPING_EVENT", event);
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Unable to publish MappingEvent: {}", t.toString());
        }
    }

    /**
     * The v2 API exposes a STATIC WaypointFactory whose instance is
     * normally injected by the real JourneyMap at startup — without it,
     * WaypointFactory.createClientWaypoint (used by Waystones) throws a
     * NullPointerException. The bridge installs its own implementation:
     * "in-memory" waypoints (dynamic proxies) that mods fill in and then
     * hand back to us via IClientAPI.addWaypoint/show.
     */
    private static void installWaypointFactory() {
        try {
            Class<?> factoryClass = tryLoad("journeymap.api.v2.common.waypoint.WaypointFactory");
            Class<?> storeInterface = tryLoad("journeymap.api.v2.common.waypoint.WaypointFactory$WaypointStore");
            Class<?> waypointInterface = tryLoad("journeymap.api.v2.common.waypoint.Waypoint");
            Class<?> groupInterface = tryLoad("journeymap.api.v2.common.waypoint.WaypointGroup");
            if (factoryClass == null || storeInterface == null || waypointInterface == null) {
                return;
            }

            Field instanceField = factoryClass.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            if (instanceField.get(null) != null) {
                return;
            }

            InvocationHandler storeHandler = (proxy, method, args) -> switch (method.getName()) {
                case "createWaypoint", "createClientWaypoint" -> newBridgeWaypoint(waypointInterface, args);
                case "createWaypointGroup" -> newBridgeGroup(groupInterface, args);
                default -> null;
            };
            Object store = Proxy.newProxyInstance(
                    storeInterface.getClassLoader(), new Class<?>[] {storeInterface}, storeHandler);
            instanceField.set(null, factoryClass.getConstructor(storeInterface).newInstance(store));
            LOGGER.info("[Bridge JM] API WaypointFactory provided by the bridge.");
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Unable to install the WaypointFactory: {}", t.toString());
        }
    }

    /** Mutable state of an API v2 waypoint created by the bridge's factory. */
    private static final class BridgeWaypointState {
        final String guid = UUID.randomUUID().toString();
        String modId;
        String name;
        BlockPos pos;
        String primaryDimension;
        final TreeSet<String> dimensions = new TreeSet<>();
        int color;
        boolean enabled = true;
        boolean persistent = true;
        String groupId = "";
        String customData = "";
    }

    /** Waypoint v2 proxy. Store arguments: (modId, pos, name, dimension, persistent). */
    private static Object newBridgeWaypoint(Class<?> waypointInterface, Object[] args) {
        BridgeWaypointState st = new BridgeWaypointState();
        st.modId = String.valueOf(args[0]);
        st.pos = (BlockPos) args[1];
        st.name = String.valueOf(args[2]);
        st.primaryDimension = String.valueOf(args[3]);
        st.dimensions.add(st.primaryDimension);
        st.persistent = Boolean.TRUE.equals(args[4]);
        // STABLE color derived from the waypoint's identity (mods like
        // Waystones never set the color): identical across sessions and
        // players, unlike a random draw.
        st.color = stableColor(st.modId + "|" + st.primaryDimension + "|" + st.pos);
        InvocationHandler handler = (proxy, method, margs) -> waypointCall(proxy, st, method, margs);
        return Proxy.newProxyInstance(waypointInterface.getClassLoader(), new Class<?>[] {waypointInterface}, handler);
    }

    /**
     * Stable color derived from a text seed: hue comes from the hash,
     * saturation and value are fixed to stay vivid and readable on the
     * map. Same seed → same color, regardless of session or player.
     */
    private static int stableColor(String seed) {
        float hue = (seed.hashCode() & 0xFFFF) / (float) 0x10000;
        return 0xFFFFFF & Mth.hsvToRgb(hue, 0.85f, 1.0f);
    }

    private static Object waypointCall(Object proxy, BridgeWaypointState st, Method method, Object[] args) {
        Object result = waypointCallInner(proxy, st, method, args);
        // In the real JourneyMap, waypoint objects are "live": mods mutate
        // them (setName after renaming a waystone...) WITHOUT calling
        // addWaypoint again. So we propagate every mutation to the store
        // if this waypoint has already been added.
        if (method.getName().startsWith("set")) {
            ClientApiHandler handler = HANDLERS.get(st.modId);
            if (handler != null) {
                handler.resyncIfKnown(st.guid, proxy);
            }
        }
        return result;
    }

    private static Object waypointCallInner(Object proxy, BridgeWaypointState st, Method method, Object[] args) {
        switch (method.getName()) {
            case "getId", "getGuid":
                return st.guid;
            case "getModId":
                return st.modId;
            case "getName":
                return st.name;
            case "setName":
                st.name = String.valueOf(args[0]);
                return null;
            case "getBlockPos":
                return st.pos;
            case "setBlockPos":
                st.pos = (BlockPos) args[0];
                return null;
            case "setPos":
                st.pos = new BlockPos((Integer) args[0], (Integer) args[1], (Integer) args[2]);
                return null;
            case "getX":
                return st.pos.getX();
            case "getY":
                return st.pos.getY();
            case "getZ":
                return st.pos.getZ();
            case "setX":
                st.pos = new BlockPos((Integer) args[0], st.pos.getY(), st.pos.getZ());
                return null;
            case "setY":
                st.pos = new BlockPos(st.pos.getX(), (Integer) args[0], st.pos.getZ());
                return null;
            case "setZ":
                st.pos = new BlockPos(st.pos.getX(), st.pos.getY(), (Integer) args[0]);
                return null;
            case "getColor":
                return st.color;
            case "setColor":
                st.color = 0xFFFFFF & (Integer) args[0];
                return null;
            case "getRed":
                return (st.color >> 16) & 0xFF;
            case "getGreen":
                return (st.color >> 8) & 0xFF;
            case "getBlue":
                return st.color & 0xFF;
            case "setRed":
                st.color = (st.color & 0x00FFFF) | (((Integer) args[0] & 0xFF) << 16);
                return null;
            case "setGreen":
                st.color = (st.color & 0xFF00FF) | (((Integer) args[0] & 0xFF) << 8);
                return null;
            case "setBlue":
                st.color = (st.color & 0xFFFF00) | ((Integer) args[0] & 0xFF);
                return null;
            case "getDimensions":
                return new TreeSet<>(st.dimensions);
            case "setDimensions":
                st.dimensions.clear();
                for (Object d : (Collection<?>) args[0]) {
                    st.dimensions.add(String.valueOf(d));
                }
                return null;
            case "getPrimaryDimension":
                return st.primaryDimension;
            case "setPrimaryDimension":
                if (args[0] instanceof ResourceKey<?> key) {
                    st.primaryDimension = key.location().toString();
                } else {
                    st.primaryDimension = String.valueOf(args[0]);
                }
                st.dimensions.add(st.primaryDimension);
                return null;
            case "isEnabled":
                return st.enabled;
            case "setEnabled":
                st.enabled = (Boolean) args[0];
                return null;
            case "isPersistent":
                return st.persistent;
            case "setPersistent":
                st.persistent = (Boolean) args[0];
                return null;
            case "getGroupId":
                return st.groupId;
            case "setGroupId":
                st.groupId = String.valueOf(args[0]);
                return null;
            case "getCustomData":
                return st.customData;
            case "setCustomData":
                st.customData = String.valueOf(args[0]);
                return null;
            case "toString":
                return "BridgeWaypoint[" + st.name + "]";
            case "hashCode":
                return st.guid.hashCode();
            case "equals":
                return proxy == args[0];
            default:
                return ClientApiHandler.defaultValue(method.getReturnType());
        }
    }

    /** Minimal WaypointGroup v2 proxy (Waystones files its waypoints in there). */
    private static Object newBridgeGroup(Class<?> groupInterface, Object[] args) {
        if (groupInterface == null) {
            return null;
        }

        String name = args == null || args.length == 0 ? "group" : String.valueOf(args[0]);
        String id = UUID.nameUUIDFromBytes(("group|" + name).getBytes()).toString();
        InvocationHandler handler = (proxy, method, margs) -> switch (method.getName()) {
            case "getId", "getGuid" -> id;
            case "getName" -> name;
            case "addWaypoint" -> true;
            case "toString" -> "BridgeWaypointGroup[" + name + "]";
            case "hashCode" -> id.hashCode();
            case "equals" -> proxy == margs[0];
            default -> ClientApiHandler.defaultValue(method.getReturnType());
        };
        return Proxy.newProxyInstance(groupInterface.getClassLoader(), new Class<?>[] {groupInterface}, handler);
    }

    /**
     * Dispatches an event to the subscribers of a STATIC JourneyMap API
     * registry (EventImpl.getListeners), via pure reflection.
     */
    static void dispatchToRegistry(String registryClassName, String fieldName, Object event) {
        dispatchToRegistry(registryClassName, fieldName, event, modId -> true);
    }

    /** Variant filtered by subscriber modId (config overlay toggles). */
    static void dispatchToRegistry(
            String registryClassName, String fieldName, Object event, Predicate<String> modIdFilter) {
        try {
            Class<?> registryClass = tryLoad(registryClassName);
            if (registryClass == null) {
                return;
            }

            Object eventImpl = registryClass.getField(fieldName).get(null);
            Object listeners = eventImpl.getClass().getMethod("getListeners").invoke(eventImpl);
            if (!(listeners instanceof List<?> list)) {
                return;
            }

            for (Object entry : list) {
                Object listenerModId = entry.getClass().getMethod("modId").invoke(entry);
                if (!modIdFilter.test(String.valueOf(listenerModId))) {
                    continue;
                }

                Object consumer = entry.getClass().getMethod("listener").invoke(entry);
                if (consumer instanceof Consumer<?> c) {
                    @SuppressWarnings("unchecked")
                    Consumer<Object> typed = (Consumer<Object>) c;
                    typed.accept(event);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Unable to publish {}.{}: {}", registryClassName, fieldName, t.toString());
        }
    }

    // ------------------------------------------------------------------ detection

    /** The real JourneyMap (not our low-code shim) exposes its main classes. */
    private static boolean isRealJourneyMapPresent() {
        if (!ModList.get().isLoaded("journeymap")) {
            return false;
        }

        return tryLoad("journeymap.common.Journeymap") != null || tryLoad("journeymap.client.JourneymapClient") != null;
    }

    private static Class<?> tryLoad(String name) {
        try {
            return Class.forName(name, false, JourneyMapBridge.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Scans annotations the way JourneyMap does (FML scan data, without loading classes). */
    private static Set<String> scanPlugins(String annotationDescriptor) {
        Set<String> classes = new HashSet<>();
        Type annotationType = Type.getType(annotationDescriptor);
        ModList.get().getAllScanData().forEach(scan -> scan.getAnnotations().forEach(a -> {
            if (annotationType.equals(a.annotationType())) {
                classes.add(a.clazz().getClassName());
            }
        }));
        return classes;
    }

    // ------------------------------------------------------------------ plugin initialization

    private static void initializePlugin(String className, Class<?> apiInterface) {
        try {
            Class<?> pluginClass = Class.forName(className);
            Object plugin = pluginClass.getDeclaredConstructor().newInstance();

            String modId = "journeymap-bridge";
            try {
                Object id = pluginClass.getMethod("getModId").invoke(plugin);
                if (id instanceof String s && !s.isBlank()) {
                    modId = s;
                }
            } catch (Throwable ignored) {
            }

            ClientApiHandler handler = new ClientApiHandler(modId);
            HANDLERS.put(modId, handler);
            Object apiProxy =
                    Proxy.newProxyInstance(apiInterface.getClassLoader(), new Class<?>[] {apiInterface}, handler);

            // IClientPlugin.initialize(IClientAPI) — look up the method by
            // name and parameter compatibility to tolerate API variations.
            Method init = null;
            for (Method m : pluginClass.getMethods()) {
                if (m.getName().equals("initialize")
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(apiInterface)) {
                    init = m;
                    break;
                }
            }
            if (init == null) {
                LOGGER.warn("[Bridge JM] {}: no compatible initialize(IClientAPI) method, ignored.", className);
                return;
            }
            init.invoke(plugin, apiProxy);
            loadedPlugins.add(modId + " (" + className + ")");
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Unable to initialize plugin {}: {}", className, t.toString());
        }
    }

    // ------------------------------------------------------------------ IClientAPI proxy

    /**
     * Translates IClientAPI calls to SharedJourney. Coverage:
     *  - waypoints (show/addWaypoint/removeWaypoint/remove/exists/getAll...)
     *  - playerAccepts -> true (accept everything)
     *  - subscribe/toggleDisplay/overlays -> no-op, logged once per method
     */
    private static final class ClientApiHandler implements InvocationHandler {

        private final String modId;
        /** JM guid/object -> our UUID, to find waypoints to remove. */
        private final Map<String, UUID> knownWaypoints = new ConcurrentHashMap<>();
        /**
         * Added (API) Waypoint objects, by guid: used by getWaypoint and
         * getAllWaypoints. Essential so mods (Waystones) can find and
         * UPDATE their waypoints instead of recreating one on every
         * change (otherwise stacking duplicates).
         */
        private final Map<String, Object> apiWaypoints = new ConcurrentHashMap<>();

        private final Set<String> warnedMethods = ConcurrentHashMap.newKeySet();

        ClientApiHandler(String modId) {
            this.modId = modId;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            try {
                switch (name) {
                    // ---- identity / capabilities
                    case "playerAccepts" -> {
                        return true;
                    }
                    case "getModId" -> {
                        return modId;
                    }

                    // ---- waypoints v2
                    case "addWaypoint" -> {
                        Object wp = args[args.length - 1];
                        addOrUpdate(wp);
                        return null;
                    }
                    case "removeWaypoint" -> {
                        removeWaypoint(args[args.length - 1]);
                        return null;
                    }
                    case "getAllWaypoints", "getWaypoints" -> {
                        return List.copyOf(apiWaypoints.values());
                    }
                    case "getWaypoint" -> {
                        // (modId, guid/id): find the previously added object.
                        if (args == null || args.length == 0) {
                            return null;
                        }

                        return apiWaypoints.get(String.valueOf(args[args.length - 1]));
                    }

                    // ---- generic Displayable (v1 and v2 overlays)
                    case "show" -> {
                        Object displayable = args[0];
                        if (isWaypoint(displayable)) {
                            addOrUpdate(displayable);
                        } else {
                            warnOnce("show:" + displayable.getClass().getSimpleName());
                        }

                        return null;
                    }
                    case "remove" -> {
                        Object displayable = args[0];
                        if (isWaypoint(displayable)) {
                            removeWaypoint(displayable);
                        }

                        return null;
                    }
                    case "removeAll" -> {
                        apiWaypoints.clear();
                        WaypointStore.removeBySource(modId);
                        return null;
                    }
                    case "exists" -> {
                        Object displayable = args[0];
                        return isWaypoint(displayable) && knownWaypoints.containsKey(guidOf(displayable));
                    }

                    // ---- events / display: tolerant no-op
                    case "subscribe",
                            "unsubscribe",
                            "toggleDisplay",
                            "setWorldId",
                            "requestMapTile",
                            "flagPlayerSprite",
                            "toggleWaypoints" -> {
                        warnOnce(name);
                        return null;
                    }

                    // ---- Object
                    case "toString" -> {
                        return "SharedJourney IClientAPI bridge (" + modId + ")";
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[Bridge JM] Error handling {}.{}: {}", modId, name, t.toString());
                return defaultValue(method.getReturnType());
            }
            warnOnce(name);
            return defaultValue(method.getReturnType());
        }

        private void warnOnce(String what) {
            if (warnedMethods.add(what)) {
                LOGGER.info("[Bridge JM] {} called '{}' — not supported by the bridge, ignored.", modId, what);
            }
        }

        /** Propagates the mutation of an already-added waypoint to the store. */
        void resyncIfKnown(String guid, Object jmWaypoint) {
            if (apiWaypoints.containsKey(guid)) {
                addOrUpdate(jmWaypoint);
            }
        }

        // -------------------------------------------------------------- waypoint translation

        private boolean isWaypoint(Object o) {
            if (o == null) {
                return false;
            }

            if (o.getClass().getName().toLowerCase().contains("waypoint")) {
                return true;
            }

            // Waypoints from our WaypointFactory are proxies: their class
            // name is "jdk.proxy...", so check the interfaces instead.
            for (Class<?> iface : o.getClass().getInterfaces()) {
                if (iface.getName().toLowerCase().contains("waypoint")) {
                    return true;
                }
            }
            return false;
        }

        private void addOrUpdate(Object jmWaypoint) {
            String guid = guidOf(jmWaypoint);
            String name = str(call(jmWaypoint, "getName"), str(call(jmWaypoint, "getTitle"), "Waypoint"));
            BlockPos pos = posOf(jmWaypoint);
            if (pos == null) {
                return;
            }

            int color = intOf(call(jmWaypoint, "getColor"), stableColor(modId + "|" + name));
            ResourceLocation dim = dimOf(jmWaypoint);

            apiWaypoints.put(guid, jmWaypoint);
            UUID id = knownWaypoints.computeIfAbsent(guid, g -> UUID.nameUUIDFromBytes((modId + "|" + g).getBytes()));
            // Bridged waypoints are grouped under their source mod's id.
            Waypoint wp = new Waypoint(
                    id,
                    name,
                    dim,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    color & 0xFFFFFF,
                    modId,
                    modId,
                    true,
                    Waypoint.Type.DIMENSION);
            // add() posts WaypointEvent.Added (cancellable); update if already present.
            if (WaypointStore.get(id) != null) {
                WaypointStore.update(wp);
            } else {
                WaypointStore.add(wp);
            }
        }

        private void removeWaypoint(Object jmWaypoint) {
            String guid = guidOf(jmWaypoint);
            apiWaypoints.remove(guid);
            UUID id = knownWaypoints.remove(guid);
            if (id != null) {
                WaypointStore.remove(id);
            }
        }

        private String guidOf(Object wp) {
            Object guid = call(wp, "getGuid");
            if (guid == null) {
                guid = call(wp, "getId");
            }

            if (guid == null) {
                BlockPos pos = posOf(wp);
                guid = pos == null
                        ? String.valueOf(System.identityHashCode(wp))
                        : pos.getX() + "," + pos.getY() + "," + pos.getZ();
            }
            return String.valueOf(guid);
        }

        private BlockPos posOf(Object wp) {
            Object pos = call(wp, "getPos");
            if (pos == null) {
                pos = call(wp, "getPosition");
            }

            if (pos instanceof BlockPos bp) {
                return bp;
            }

            Object x = call(wp, "getX"), y = call(wp, "getY"), z = call(wp, "getZ");
            if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
                return new BlockPos(nx.intValue(), ny.intValue(), nz.intValue());
            }
            return null;
        }

        private ResourceLocation dimOf(Object wp) {
            // v2: getDimensions() -> Collection<String> or String[]; v1: getDimension()
            Object dims = call(wp, "getDimensions");
            String first = null;
            if (dims instanceof Collection<?> c && !c.isEmpty()) {
                first = String.valueOf(c.iterator().next());
            } else if (dims instanceof String[] arr && arr.length > 0) {
                first = arr[0];
            } else {
                Object d = call(wp, "getDimension");
                if (d != null) {
                    first = String.valueOf(d);
                }
            }
            ResourceLocation rl = first == null ? null : ResourceLocation.tryParse(first);
            if (rl != null) {
                return rl;
            }

            var mc = Minecraft.getInstance();
            return mc.level != null
                    ? mc.level.dimension().location()
                    : ResourceLocation.withDefaultNamespace("overworld");
        }

        // -------------------------------------------------------------- reflection utilities

        private static Object call(Object target, String methodName) {
            try {
                Method m = target.getClass().getMethod(methodName);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable t) {
                return null;
            }
        }

        private static String str(Object o, String def) {
            return o == null ? def : String.valueOf(o);
        }

        private static int intOf(Object o, int def) {
            return o instanceof Number n ? n.intValue() : def;
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                if (List.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)) {
                    return List.of();
                }

                if (Optional.class.isAssignableFrom(type)) {
                    return Optional.empty();
                }

                return null;
            }
            if (type == boolean.class) {
                return false;
            }

            if (type == void.class) {
                return null;
            }

            if (type == float.class) {
                return 0f;
            }

            if (type == double.class) {
                return 0d;
            }

            if (type == long.class) {
                return 0L;
            }

            return 0;
        }
    }
}
