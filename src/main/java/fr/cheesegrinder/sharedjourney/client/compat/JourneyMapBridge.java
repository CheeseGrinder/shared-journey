package fr.cheesegrinder.sharedjourney.client.compat;

import com.mojang.logging.LogUtils;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.objectweb.asm.Type;
import org.slf4j.Logger;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Annotations plugin connues (v2 puis v1/legacy). */
    private static final String[] PLUGIN_ANNOTATIONS = {
            "Ljourneymap/api/v2/client/JourneyMapPlugin;",
            "Ljourneymap/client/api/JourneyMapPlugin;"
    };

    /** Interfaces IClientAPI correspondantes. */
    private static final String[] CLIENT_API_INTERFACES = {
            "journeymap.api.v2.client.IClientAPI",
            "journeymap.client.api.IClientAPI"
    };

    private static boolean initialized;
    private static final List<String> loadedPlugins = new ArrayList<>();

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
            LOGGER.info("[Bridge JM] {} plugin(s) JourneyMap initialisé(s) via SharedJourney : {}",
                    loadedPlugins.size(), loadedPlugins);
        }
    }

    // ------------------------------------------------------------------ détection

    /** Le vrai JourneyMap (pas notre shim lowcode) expose ses classes principales. */
    private static boolean isRealJourneyMapPresent() {
        if (!ModList.get().isLoaded("journeymap")) {
            return false;
        }

        return tryLoad("journeymap.common.Journeymap") != null
                || tryLoad("journeymap.client.JourneymapClient") != null;
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
        ModList.get().getAllScanData().forEach(scan ->
                scan.getAnnotations().forEach(a -> {
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
            } catch (Throwable ignored) {}

            Object apiProxy = Proxy.newProxyInstance(
                    apiInterface.getClassLoader(),
                    new Class<?>[]{apiInterface},
                    new ClientApiHandler(modId));

            // IClientPlugin.initialize(IClientAPI) — on cherche la méthode par nom
            // et compatibilité de paramètre pour tolérer les variations d'API.
            Method init = null;
            for (Method m : pluginClass.getMethods()) {
                if (m.getName().equals("initialize") && m.getParameterCount() == 1
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
                    case "playerAccepts" -> { return true; }
                    case "getModId" -> { return modId; }

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
                    case "getAllWaypoints", "getWaypoints" -> { return List.of(); }
                    case "getWaypoint" -> { return null; }

                    // ---- Displayable générique (v1 et overlays v2)
                    case "show" -> {
                        Object displayable = args[0];
                        if (isWaypoint(displayable)) {
                            addOrUpdate(displayable);
                        }
                        else {
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
                        WaypointStore.removeBySource(modId);
                        return null;
                    }
                    case "exists" -> {
                        Object displayable = args[0];
                        return isWaypoint(displayable) && knownWaypoints.containsKey(guidOf(displayable));
                    }

                    // ---- événements / affichage : no-op tolérant
                    case "subscribe", "unsubscribe", "toggleDisplay", "setWorldId",
                         "requestMapTile", "flagPlayerSprite", "toggleWaypoints" -> {
                        warnOnce(name);
                        return null;
                    }

                    // ---- Object
                    case "toString" -> { return "SharedJourney IClientAPI bridge (" + modId + ")"; }
                    case "hashCode" -> { return System.identityHashCode(proxy); }
                    case "equals" -> { return proxy == args[0]; }
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

        // -------------------------------------------------------------- traduction waypoint

        private boolean isWaypoint(Object o) {
            return o != null && o.getClass().getName().toLowerCase().contains("waypoint");
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

            UUID id = knownWaypoints.computeIfAbsent(guid,
                    g -> UUID.nameUUIDFromBytes((modId + "|" + g).getBytes()));
            Waypoint wp = new Waypoint(id, name, dim, pos.getX(), pos.getY(), pos.getZ(),
                    color & 0xFFFFFF, modId, true);
            // add() poste WaypointEvent.Added (annulable) ; update si déjà présent.
            if (WaypointStore.get(id) != null) {
                WaypointStore.update(wp);
            }
            else {
                WaypointStore.add(wp);
            }
        }

        private void removeWaypoint(Object jmWaypoint) {
            UUID id = knownWaypoints.remove(guidOf(jmWaypoint));
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
                guid = pos == null ? String.valueOf(System.identityHashCode(wp))
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
            }
            else if (dims instanceof String[] arr && arr.length > 0) {
                first = arr[0];
            }
            else {
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
            return mc.level != null ? mc.level.dimension().location()
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
