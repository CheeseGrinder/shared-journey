package fr.cheesegrinder.sharedjourney.client.compat;

import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Bridge de compatibilité JourneyMap (spec §9).
 *
 * Objectif : que les mods tiers qui s'intègrent à JourneyMap via son API
 * (ex: Waystones) fonctionnent avec SharedJourney SANS modification.
 *
 * Fonctionnement :
 *  1. Si le vrai JourneyMap est présent, le bridge se désactive (JM gère).
 *  2. Sinon, on scanne les données d'annotations de tous les mods à la
 *     recherche de @JourneyMapPlugin (v2 puis v1), exactement comme le fait
 *     JourneyMap lui-même.
 *  3. Chaque plugin trouvé est instancié et initialisé avec un PROXY DYNAMIQUE
 *     de IClientAPI (java.lang.reflect.Proxy) : aucune dépendance de
 *     compilation vers l'API JourneyMap, et tolérance aux variations de
 *     signatures entre versions de l'API.
 *  4. Les appels de type "waypoint" sont traduits vers notre WaypointStore
 *     (source = modId du plugin) ; les overlays/événements sont journalisés
 *     et ignorés proprement (voir README §Bridge pour les limites).
 *
 * IMPORTANT : les classes de l'API JourneyMap doivent être présentes au
 * runtime pour que les classes plugin des mods tiers puissent se charger
 * (elles y font référence). Voir README : jar de l'API dans mods/ ou jarJar.
 */
public final class JourneyMapBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Annotations plugin connues (v2 : @JourneyMapPlugin ; v1/legacy : @ClientPlugin). */
    private static final String[] PLUGIN_ANNOTATIONS = {
        "Ljourneymap/api/v2/client/JourneyMapPlugin;", "Ljourneymap/client/api/ClientPlugin;"
    };

    /** Interfaces IClientAPI correspondantes. */
    private static final String[] CLIENT_API_INTERFACES = {
        "journeymap.api.v2.client.IClientAPI", "journeymap.client.api.IClientAPI"
    };

    private static boolean initialized;
    private static final List<String> loadedPlugins = new ArrayList<>();
    /** Handlers IClientAPI par modId : sert à resynchroniser les waypoints mutés. */
    private static final Map<String, ClientApiHandler> HANDLERS = new ConcurrentHashMap<>();

    private JourneyMapBridge() {}

    public static List<String> loadedPlugins() {
        return List.copyOf(loadedPlugins);
    }

    /** À appeler après la construction de tous les mods (FMLLoadCompleteEvent, client). */
    public static void init() {
        if (initialized) {
            return;
        }

        initialized = true;

        if (isRealJourneyMapPresent()) {
            LOGGER.info("[Bridge JM] JourneyMap est installé : le bridge SharedJourney se désactive.");
            return;
        }

        // Avant d'initialiser les plugins : la WaypointFactory statique de
        // l'API doit exister, sinon leurs créations de waypoints crashent.
        installWaypointFactory();

        for (int i = 0; i < PLUGIN_ANNOTATIONS.length; i++) {
            Class<?> apiInterface = tryLoad(CLIENT_API_INTERFACES[i]);
            // Cette version de l'API n'est pas sur le classpath.
            if (apiInterface == null) {
                continue;
            }

            Set<String> pluginClasses = scanPlugins(PLUGIN_ANNOTATIONS[i]);
            for (String cls : pluginClasses) {
                initializePlugin(cls, apiInterface);
            }
        }

        if (loadedPlugins.isEmpty()) {
            LOGGER.info("[Bridge JM] Aucun plugin JourneyMap tiers détecté (ou API JourneyMap absente du classpath).");
        } else {
            LOGGER.info(
                    "[Bridge JM] {} plugin(s) JourneyMap initialisé(s) via SharedJourney : {}",
                    loadedPlugins.size(),
                    loadedPlugins);
        }
    }

    /** Le bridge est-il actif (pas de vrai JourneyMap, au moins un plugin) ? */
    static boolean bridgeActive() {
        return initialized && !loadedPlugins.isEmpty();
    }

    /**
     * Publie un MappingEvent (API v2) aux plugins abonnés via le registre
     * STATIQUE de l'API (ClientEventRegistry.MAPPING_EVENT) — les abonnements
     * ne passent pas par IClientAPI, donc pas par notre proxy. Le vrai
     * JourneyMap publie MAPPING_STARTED/MAPPING_STOPPED à l'entrée/sortie
     * d'un monde ; certains plugins (Waystones) attendent MAPPING_STARTED
     * avant de créer le moindre waypoint.
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
            LOGGER.warn("[Bridge JM] Impossible de publier MappingEvent : {}", t.toString());
        }
    }

    /**
     * L'API v2 expose une WaypointFactory STATIQUE dont l'instance est
     * normalement injectée par le vrai JourneyMap au démarrage — sans elle,
     * WaypointFactory.createClientWaypoint (utilisée par Waystones) lève une
     * NullPointerException. Le bridge installe sa propre implémentation :
     * des waypoints "en mémoire" (proxys dynamiques) que les mods remplissent
     * puis nous repassent via IClientAPI.addWaypoint/show.
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
            LOGGER.info("[Bridge JM] WaypointFactory de l'API fournie par le bridge.");
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Impossible d'installer la WaypointFactory : {}", t.toString());
        }
    }

    /** État mutable d'un waypoint API v2 créé par la factory du bridge. */
    private static final class BridgeWaypointState {
        final String guid = UUID.randomUUID().toString();
        String modId;
        String name;
        BlockPos pos;
        String primaryDimension;
        final TreeSet<String> dimensions = new TreeSet<>();
        int color = 0xFFFFFF & ThreadLocalRandom.current().nextInt();
        boolean enabled = true;
        boolean persistent = true;
        String groupId = "";
        String customData = "";
    }

    /** Proxy Waypoint v2. Arguments du store : (modId, pos, nom, dimension, persistant). */
    private static Object newBridgeWaypoint(Class<?> waypointInterface, Object[] args) {
        BridgeWaypointState st = new BridgeWaypointState();
        st.modId = String.valueOf(args[0]);
        st.pos = (BlockPos) args[1];
        st.name = String.valueOf(args[2]);
        st.primaryDimension = String.valueOf(args[3]);
        st.dimensions.add(st.primaryDimension);
        st.persistent = Boolean.TRUE.equals(args[4]);
        InvocationHandler handler = (proxy, method, margs) -> waypointCall(proxy, st, method, margs);
        return Proxy.newProxyInstance(waypointInterface.getClassLoader(), new Class<?>[] {waypointInterface}, handler);
    }

    private static Object waypointCall(Object proxy, BridgeWaypointState st, Method method, Object[] args) {
        Object result = waypointCallInner(proxy, st, method, args);
        // Dans le vrai JourneyMap les objets waypoint sont "vivants" : les
        // mods les mutent (setName après renommage d'une waystone...) SANS
        // rappeler addWaypoint. On répercute donc chaque mutation vers le
        // store si ce waypoint a déjà été ajouté.
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

    /** Proxy WaypointGroup v2 minimal (Waystones range ses waypoints dedans). */
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
     * Distribue un événement aux abonnés d'un registre STATIQUE de l'API
     * JourneyMap (EventImpl.getListeners), en réflexion pure.
     */
    static void dispatchToRegistry(String registryClassName, String fieldName, Object event) {
        dispatchToRegistry(registryClassName, fieldName, event, modId -> true);
    }

    /** Variante filtrée par modId d'abonné (toggles d'overlays de la config). */
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
            LOGGER.warn("[Bridge JM] Impossible de publier {}.{} : {}", registryClassName, fieldName, t.toString());
        }
    }

    // ------------------------------------------------------------------ détection

    /** Le vrai JourneyMap (pas notre shim lowcode) expose ses classes principales. */
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

    /** Scan des annotations comme le fait JourneyMap (données de scan FML, sans charger les classes). */
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

    // ------------------------------------------------------------------ initialisation des plugins

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

            // IClientPlugin.initialize(IClientAPI) — on cherche la méthode par nom
            // et compatibilité de paramètre pour tolérer les variations d'API.
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
                LOGGER.warn("[Bridge JM] {} : pas de méthode initialize(IClientAPI) compatible, ignoré.", className);
                return;
            }
            init.invoke(plugin, apiProxy);
            loadedPlugins.add(modId + " (" + className + ")");
        } catch (Throwable t) {
            LOGGER.warn("[Bridge JM] Impossible d'initialiser le plugin {} : {}", className, t.toString());
        }
    }

    // ------------------------------------------------------------------ proxy IClientAPI

    /**
     * Traduit les appels IClientAPI vers SharedJourney. Couverture :
     *  - waypoints (show/addWaypoint/removeWaypoint/remove/exists/getAll...)
     *  - playerAccepts -> true (on accepte tout)
     *  - subscribe/toggleDisplay/overlays -> no-op journalisé une fois par méthode
     */
    private static final class ClientApiHandler implements InvocationHandler {

        private final String modId;
        /** JM guid/objet -> notre UUID, pour retrouver les waypoints à supprimer. */
        private final Map<String, UUID> knownWaypoints = new ConcurrentHashMap<>();
        /**
         * Objets Waypoint (API) ajoutés, par guid : sert getWaypoint et
         * getAllWaypoints. Indispensable pour que les mods (Waystones)
         * retrouvent et METTENT À JOUR leurs waypoints au lieu d'en recréer
         * un à chaque changement (doublons superposés sinon).
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
                    // ---- identité / capacités
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
                        // (modId, guid/id) : retrouve l'objet ajouté précédemment.
                        if (args == null || args.length == 0) {
                            return null;
                        }

                        return apiWaypoints.get(String.valueOf(args[args.length - 1]));
                    }

                    // ---- Displayable générique (v1 et overlays v2)
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

                    // ---- événements / affichage : no-op tolérant
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
                LOGGER.warn("[Bridge JM] Erreur en traitant {}.{} : {}", modId, name, t.toString());
                return defaultValue(method.getReturnType());
            }
            warnOnce(name);
            return defaultValue(method.getReturnType());
        }

        private void warnOnce(String what) {
            if (warnedMethods.add(what)) {
                LOGGER.info("[Bridge JM] {} a appelé '{}' — non supporté par le bridge, ignoré.", modId, what);
            }
        }

        /** Répercute la mutation d'un waypoint déjà ajouté vers le store. */
        void resyncIfKnown(String guid, Object jmWaypoint) {
            if (apiWaypoints.containsKey(guid)) {
                addOrUpdate(jmWaypoint);
            }
        }

        // -------------------------------------------------------------- traduction waypoint

        private boolean isWaypoint(Object o) {
            if (o == null) {
                return false;
            }

            if (o.getClass().getName().toLowerCase().contains("waypoint")) {
                return true;
            }

            // Les waypoints de notre WaypointFactory sont des proxys : leur
            // nom de classe est "jdk.proxy...", il faut regarder les interfaces.
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

            int color = intOf(call(jmWaypoint, "getColor"), 0x00FFFF & (name.hashCode() | 0x404040));
            ResourceLocation dim = dimOf(jmWaypoint);

            apiWaypoints.put(guid, jmWaypoint);
            UUID id = knownWaypoints.computeIfAbsent(guid, g -> UUID.nameUUIDFromBytes((modId + "|" + g).getBytes()));
            Waypoint wp = new Waypoint(
                    id,
                    name,
                    dim,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    color & 0xFFFFFF,
                    modId,
                    true,
                    Waypoint.Type.DIMENSION);
            // add() poste WaypointEvent.Added (annulable) ; update si déjà présent.
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
            // v2: getDimensions() -> Collection<String> ou String[] ; v1: getDimension()
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

        // -------------------------------------------------------------- utilitaires réflexion

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
