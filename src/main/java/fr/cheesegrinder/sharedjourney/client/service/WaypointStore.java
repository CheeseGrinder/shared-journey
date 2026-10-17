package fr.cheesegrinder.sharedjourney.client.service;

import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.api.event.WaypointEvent;
import fr.cheesegrinder.sharedjourney.client.config.WaypointClientConfig;
import fr.cheesegrinder.sharedjourney.common.network.WaypointPayloads;
import fr.cheesegrinder.sharedjourney.common.util.Lang;
import fr.cheesegrinder.sharedjourney.common.waypoint.AutoWaypointKind;
import fr.cheesegrinder.sharedjourney.common.waypoint.DimensionConversion;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side waypoint store (spec §6.2): the in-memory source of truth
 * for every waypoint this client knows about, regardless of where it is
 * actually persisted. Purely local waypoints (TEMP always, DIMENSION when
 * the server does not manage them) are saved as JSON in the current
 * server's cache folder. PUBLIC and server-managed DIMENSION waypoints are
 * instead server-authoritative: they are resynchronized fresh every
 * session and never written to the local file (see the "public"/
 * "player-managed waypoints" sections below). Posts the API's
 * WaypointEvents so that other mods (or the JourneyMap bridge) can
 * react/intercept.
 */
public final class WaypointStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<UUID, Waypoint> WAYPOINTS = new ConcurrentHashMap<>();
    /**
     * Immutable per-dimension index of WAYPOINTS, rebuilt lazily by
     * {@link #forDimension} and dropped by the mutation helpers below.
     * forDimension is called several times per FRAME (minimap, fullscreen
     * map, in-world beacons): filtering the whole map on each call
     * allocated a fresh list per caller per frame.
     */
    private static volatile Map<ResourceLocation, List<Waypoint>> byDimension;
    /** Groups explicitly hidden in the management screen. */
    private static final Set<String> HIDDEN_GROUPS = ConcurrentHashMap.newKeySet();
    /**
     * User-managed groups (management screen). Persisted so an empty
     * group survives the session; reserved and bridged groups exist only
     * through their waypoints.
     */
    private static final Set<String> KNOWN_GROUPS = ConcurrentHashMap.newKeySet();
    /**
     * Waypoints hidden by THIS client, whose canonical record is
     * server-authoritative and resynchronized at login (PUBLIC waypoints,
     * and DIMENSION ones when {@code waypoints.waypointStorage=SERVER}):
     * their visible flag cannot be persisted with them, this local set is.
     */
    private static final Set<UUID> HIDDEN_IDS = ConcurrentHashMap.newKeySet();

    private static Path file;

    private WaypointStore() {}

    // ------------------------------------------------------------------ session

    /**
     * Opens the session's waypoint file, in the same per-server cache folder
     * as the tiles. Requires {@link DiskCache#openSession()} to have run
     * first (see ClientSessionEvents).
     */
    public static void openSession() {
        Path root = DiskCache.sessionRoot();
        file = root == null ? null : root.resolve("waypoints.json");
        load();
    }

    public static void closeSession() {
        save();
        clearWaypoints();
        HIDDEN_GROUPS.clear();
        HIDDEN_IDS.clear();
        KNOWN_GROUPS.clear();
        file = null;
    }

    // ------------------------------------------------------------------ CRUD

    public static List<Waypoint> all() {
        return new ArrayList<>(WAYPOINTS.values());
    }

    /**
     * Waypoints of a dimension (every type is bound to its dimension),
     * plus the read-only converted counterparts ({@link
     * Waypoint#isConvertedView()}) of the linked waypoints of the paired
     * dimension while the server allows the feature. {@link #all} never
     * lists those counterparts: they are derived views, not stored.
     */
    public static List<Waypoint> forDimension(ResourceLocation dim) {
        Map<ResourceLocation, List<Waypoint>> index = byDimension;
        if (index == null) {
            index = buildDimensionIndex();
            byDimension = index;
        }

        return index.getOrDefault(dim, List.of());
    }

    private static Map<ResourceLocation, List<Waypoint>> buildDimensionIndex() {
        Map<ResourceLocation, List<Waypoint>> index = new HashMap<>();
        boolean convert = isConversionEnabled();
        for (Waypoint wp : WAYPOINTS.values()) {
            index.computeIfAbsent(wp.dimension(), d -> new ArrayList<>()).add(wp);
            if (convert && wp.hasConvertedLink() && canLinkConversion(wp)) {
                Waypoint view = convertedView(wp);
                if (view != null) {
                    index.computeIfAbsent(view.dimension(), d -> new ArrayList<>())
                            .add(view);
                }
            }
        }

        for (Map.Entry<ResourceLocation, List<Waypoint>> entry : index.entrySet()) {
            entry.setValue(List.copyOf(entry.getValue()));
        }
        return Map.copyOf(index);
    }

    // ------------------------------------------------------------------ converted waypoints

    /** Does the server currently allow converted (Overworld / Nether) waypoints? */
    public static boolean isConversionEnabled() {
        return ClientMapCache.convertedWaypointsEnabled;
    }

    /** The server switch changed: the derived views must be rebuilt. */
    public static void invalidateConvertedViews() {
        byDimension = null;
    }

    /**
     * Can the player link this waypoint to its counterpart in the paired
     * dimension? Hand-made (user / public) DIMENSION and PUBLIC waypoints
     * of the Overworld or the Nether only: TEMP ones vanish on arrival,
     * banner and automatic ones are world-derived (the server owns them),
     * bridged ones belong to their mod, and other dimensions have no
     * counterpart. Ignores the server switch (see {@link #isConversionEnabled}).
     */
    public static boolean canLinkConversion(Waypoint wp) {
        if (wp.isConvertedView() || wp.isWorldDerived() || wp.type() == Waypoint.Type.TEMP) {
            return false;
        }

        boolean ownKind = Waypoint.SOURCE_USER.equals(wp.source()) || Waypoint.SOURCE_PUBLIC.equals(wp.source());
        return ownKind && DimensionConversion.isPaired(wp.dimension());
    }

    /**
     * Links (or unlinks) the waypoint with this id to its converted
     * counterpart. Linking is refused while the server switch is off, or
     * when the waypoint cannot be linked ({@link #canLinkConversion}); unlinking
     * is always allowed. Goes through {@link #update}, so the flag reaches
     * the server for PUBLIC and server-managed waypoints.
     */
    public static boolean setConversionLink(UUID id, boolean linked) {
        Waypoint wp = WAYPOINTS.get(id);
        if (wp == null) {
            return false;
        }

        if (linked && (!isConversionEnabled() || !canLinkConversion(wp))) {
            return false;
        }

        update(wp.withConversion(linked ? Waypoint.Conversion.LINKED : Waypoint.Conversion.NONE));
        return true;
    }

    /**
     * Derived counterpart of a linked waypoint in the paired dimension:
     * same id, group, colour, source, type and visibility as the source,
     * converted position ({@link DimensionConversion}) and a "(converted)"
     * name. Null when the dimension has no counterpart.
     */
    private static Waypoint convertedView(Waypoint source) {
        ResourceLocation from = source.dimension();
        ResourceLocation target = DimensionConversion.counterpart(from);
        if (target == null) {
            return null;
        }

        String name = Component.translatable(Lang.WAYPOINT_CONVERTED_NAME, source.name())
                .getString();
        return new Waypoint(
                source.id(),
                name,
                target,
                DimensionConversion.convertHorizontal(from, source.x()),
                DimensionConversion.convertHeight(from, source.y()),
                DimensionConversion.convertHorizontal(from, source.z()),
                source.colorRgb(),
                source.source(),
                source.group(),
                source.visible(),
                source.type(),
                Waypoint.Conversion.VIEW);
    }

    /**
     * Brings the link flag of an incoming waypoint to what is allowed: a
     * derived view never comes in as-is; a waypoint that cannot be linked
     * (TEMP, world-derived, bridged, unpaired dimension) loses the flag; and
     * while the server switch is off nothing can be linked or unlinked, so
     * the flag of the stored copy (the dormant link) is kept.
     */
    private static Waypoint normalizedConversion(Waypoint old, Waypoint wp) {
        Waypoint.Conversion target = wp.hasConvertedLink() ? Waypoint.Conversion.LINKED : Waypoint.Conversion.NONE;
        if (!canLinkConversion(wp)) {
            target = Waypoint.Conversion.NONE;
        } else if (!isConversionEnabled()) {
            target = old != null && old.hasConvertedLink() ? Waypoint.Conversion.LINKED : Waypoint.Conversion.NONE;
        }

        if (target == wp.conversion()) {
            return wp;
        }
        return wp.withConversion(target);
    }

    // ------------------------------------------------------------------ mutation helpers

    // Every write to WAYPOINTS goes through these three helpers so the
    // per-dimension index above can never go stale.

    private static void putWaypoint(UUID id, Waypoint wp) {
        WAYPOINTS.put(id, wp);
        byDimension = null;
    }

    private static Waypoint removeWaypoint(UUID id) {
        Waypoint removed = WAYPOINTS.remove(id);
        if (removed != null) {
            byDimension = null;
        }

        return removed;
    }

    private static void clearWaypoints() {
        WAYPOINTS.clear();
        byDimension = null;
    }

    public static Waypoint get(UUID id) {
        return WAYPOINTS.get(id);
    }

    // ------------------------------------------------------------------ groups

    /** Should this waypoint be rendered (own flag AND its group's)? */
    public static boolean isShown(Waypoint wp) {
        return wp.visible() && isGroupVisible(wp.group());
    }

    public static boolean isGroupVisible(String group) {
        return !HIDDEN_GROUPS.contains(group);
    }

    public static void setGroupVisible(String group, boolean visible) {
        boolean changed = visible ? HIDDEN_GROUPS.remove(group) : HIDDEN_GROUPS.add(group);
        if (changed) {
            save();
        }
    }

    /**
     * All groups: the user-managed ones (persisted, possibly empty) plus
     * those existing through their waypoints (reserved, bridged). Sorted,
     * the default group always listed first.
     */
    public static List<String> groups() {
        TreeSet<String> names = new TreeSet<>(KNOWN_GROUPS);
        for (Waypoint wp : WAYPOINTS.values()) {
            names.add(wp.group());
        }
        names.remove(Waypoint.GROUP_DEFAULT);

        List<String> ordered = new ArrayList<>();
        ordered.add(Waypoint.GROUP_DEFAULT);
        ordered.addAll(names);
        return ordered;
    }

    /**
     * Groups a user waypoint can be assigned to: default plus the
     * user-managed ones. Excludes the reserved groups (deaths is
     * automatic, public follows the PUBLIC type) and the bridged mods'.
     */
    public static List<String> assignableGroups() {
        List<String> ordered = new ArrayList<>();
        for (String group : groups()) {
            if (Waypoint.GROUP_DEFAULT.equals(group) || isEditableGroup(group)) {
                ordered.add(group);
            }
        }
        return ordered;
    }

    /**
     * Can this group be renamed/deleted in the management screen? The
     * reserved groups (default, deaths, public, banners, beds, portals,
     * anchors) and the bridged
     * mods' groups (waystones... any group holding non-user waypoints)
     * cannot.
     */
    public static boolean isEditableGroup(String group) {
        if (Waypoint.isReservedGroup(group)) {
            return false;
        }

        return WAYPOINTS.values().stream()
                .noneMatch(wp -> wp.group().equals(group) && !Waypoint.SOURCE_USER.equals(wp.source()));
    }

    /** Creates an empty group. Returns false on invalid/taken names. */
    public static boolean createGroup(String name) {
        String group = name == null ? "" : name.trim();
        if (group.isEmpty() || groups().contains(group)) {
            return false;
        }

        KNOWN_GROUPS.add(group);
        save();
        return true;
    }

    /** Renames a user group, moving its waypoints and hidden state along. */
    public static boolean renameGroup(String from, String to) {
        String next = to == null ? "" : to.trim();
        if (!isEditableGroup(from) || next.isEmpty() || groups().contains(next)) {
            return false;
        }

        // Through update(), not a direct WAYPOINTS.put: a renamed group's
        // waypoints can be server-managed and need the rename pushed too.
        for (Waypoint wp : all()) {
            if (wp.group().equals(from)) {
                update(wp.withGroup(next));
            }
        }

        KNOWN_GROUPS.remove(from);
        KNOWN_GROUPS.add(next);
        if (HIDDEN_GROUPS.remove(from)) {
            HIDDEN_GROUPS.add(next);
        }
        save();
        return true;
    }

    /** Deletes a user group AND every waypoint it holds. */
    public static boolean deleteGroup(String group) {
        if (!isEditableGroup(group)) {
            return false;
        }

        for (Waypoint wp : all()) {
            if (wp.group().equals(group)) {
                remove(wp.id());
            }
        }

        KNOWN_GROUPS.remove(group);
        HIDDEN_GROUPS.remove(group);
        save();
        return true;
    }

    /**
     * Adds a waypoint. Returns false if a listener cancelled the addition.
     * PUBLIC waypoints are routed to the server, which persists them and
     * broadcasts them back (the local copy appears with the echo). DIMENSION
     * waypoints are routed the same way (to the player's own, private
     * storage) when the server manages them; the cancellable event is only
     * meaningful for the purely local path. Both routes are gated on
     * {@link Waypoint#SOURCE_USER}: bridged mods (Waystones...) and banner
     * waypoints also flow through add/update/remove (see
     * {@code JourneyMapBridge}), but they are read-only from THIS client's
     * point of view and must never be pushed to a SharedJourney server
     * channel, whatever their type happens to be.
     */
    public static boolean add(Waypoint added) {
        // A converted view is a read-only projection, never stored.
        if (added.isConvertedView()) {
            return false;
        }

        Waypoint wp = normalizedConversion(WAYPOINTS.get(added.id()), added);
        if (isUserPublic(wp)) {
            sendPublicUpsert(wp);
            return true;
        }

        if (isServerManaged(wp)) {
            sendPlayerUpsert(wp);
            return true;
        }

        WaypointEvent.Added event = new WaypointEvent.Added(wp);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return false;
        }

        putWaypoint(wp.id(), wp);
        save();
        return true;
    }

    public static void update(Waypoint updated) {
        // A converted view is a read-only projection: its coordinates are
        // those of the OTHER dimension, writing it back would corrupt the source.
        if (updated.isConvertedView()) {
            return;
        }

        Waypoint old = WAYPOINTS.get(updated.id());
        Waypoint wp = normalizedConversion(old, updated);
        boolean wasPublic = old != null && isUserPublic(old);
        if (wasPublic || isUserPublic(wp)) {
            updatePublic(old, wp, wasPublic);
            return;
        }

        boolean wasServerManaged = old != null && isServerManaged(old);
        boolean nowServerManaged = isServerManaged(wp);
        if (wasServerManaged || nowServerManaged) {
            updateServerManaged(old, wp, wasServerManaged, nowServerManaged);
            return;
        }

        if (wp.isWorldDerived()) {
            updateWorldDerived(wp);
            return;
        }

        putWaypoint(wp.id(), wp);
        NeoForge.EVENT_BUS.post(new WaypointEvent.Updated(wp));
        save();
    }

    /**
     * Banner and automatic waypoints are read-only (no UI edits the
     * position/name/color): the only field the UI can change is
     * visibility, which is routed through {@link #HIDDEN_IDS} like PUBLIC
     * — otherwise the next login's full resync (see
     * {@code acceptBannerUpsert}, {@code acceptAutoUpsert}) would
     * silently reset it, since it recomputes {@code visible} from
     * {@code HIDDEN_IDS}, not from the in-memory copy.
     */
    private static void updateWorldDerived(Waypoint wp) {
        setHiddenId(wp.id(), !wp.visible());
        putWaypoint(wp.id(), wp);
        NeoForge.EVENT_BUS.post(new WaypointEvent.Updated(wp));
        save();
    }

    /** A genuinely user-authored waypoint (not bridged/banner) of this type. */
    private static boolean isUserType(Waypoint wp, Waypoint.Type type) {
        return Waypoint.SOURCE_USER.equals(wp.source()) && wp.type() == type;
    }

    private static boolean isUserPublic(Waypoint wp) {
        return isUserType(wp, Waypoint.Type.PUBLIC);
    }

    /** User DIMENSION waypoints go through the server when it manages them. */
    private static boolean isServerManaged(Waypoint wp) {
        return isUserType(wp, Waypoint.Type.DIMENSION) && ClientMapCache.serverManagesWaypoints;
    }

    /**
     * Updates involving a PUBLIC waypoint. Shared fields (name, position,
     * color, dimension) go through the server; the visible flag is applied
     * locally only ({@link #HIDDEN_IDS}), it is a per-client choice.
     */
    private static void updatePublic(Waypoint old, Waypoint wp, boolean wasPublic) {
        if (!wasPublic) {
            // Promoted private → public: publish; the echo re-adds it. If
            // the old copy was server-managed, drop its private copy too
            // (otherwise it comes back as a duplicate DIMENSION waypoint on
            // the next login).
            if (old != null) {
                removeWaypoint(wp.id());
                save();
                if (isServerManaged(old)) {
                    sendPlayerRemove(wp.id());
                }
            }
            sendPublicUpsert(wp);
            return;
        }

        if (wp.type() != Waypoint.Type.PUBLIC) {
            // Demoted public → private: server copy removed, local kept
            // (and re-published to the player's own storage if the new
            // type is itself server-managed).
            sendPublicRemove(wp.id());
            HIDDEN_IDS.remove(wp.id());
            Waypoint local = new Waypoint(
                    wp.id(),
                    wp.name(),
                    wp.dimension(),
                    wp.x(),
                    wp.y(),
                    wp.z(),
                    wp.colorRgb(),
                    Waypoint.SOURCE_USER,
                    Waypoint.GROUP_DEFAULT,
                    wp.visible(),
                    wp.type(),
                    wp.conversion());
            putWaypoint(local.id(), local);
            NeoForge.EVENT_BUS.post(new WaypointEvent.Updated(local));
            save();
            if (isServerManaged(local)) {
                sendPlayerUpsert(local);
            }
            return;
        }

        boolean sharedChanged = old == null
                || !old.name().equals(wp.name())
                || !old.dimension().equals(wp.dimension())
                || old.x() != wp.x()
                || old.y() != wp.y()
                || old.z() != wp.z()
                || old.colorRgb() != wp.colorRgb()
                || old.conversion() != wp.conversion();
        Waypoint normalized = normalizedPublic(
                wp.id(),
                wp.name(),
                wp.dimension(),
                wp.x(),
                wp.y(),
                wp.z(),
                wp.colorRgb(),
                wp.visible(),
                wp.conversion());
        putWaypoint(normalized.id(), normalized);
        setHiddenId(normalized.id(), !normalized.visible());
        NeoForge.EVENT_BUS.post(new WaypointEvent.Updated(normalized));
        save();
        if (sharedChanged) {
            sendPublicUpsert(normalized);
        }
    }

    /**
     * Updates a DIMENSION waypoint while it is, was, or becomes
     * server-managed (PUBLIC already handled above). Whether a waypoint is
     * server-managed is not itself stored: it is recomputed from its
     * current type against the server-wide setting, so a TYPE change (the
     * edit screen's DIMENSION/PUBLIC/TEMP cycle) is the only way old and
     * new can disagree. Shared fields go through the server; the visible
     * flag stays local ({@link #HIDDEN_IDS}), same rationale as PUBLIC.
     */
    private static void updateServerManaged(
            Waypoint old, Waypoint wp, boolean wasServerManaged, boolean nowServerManaged) {
        if (!nowServerManaged) {
            // Demoted to TEMP: drop the player's server copy, keep local.
            sendPlayerRemove(wp.id());
            HIDDEN_IDS.remove(wp.id());
            putWaypoint(wp.id(), wp);
            NeoForge.EVENT_BUS.post(new WaypointEvent.Updated(wp));
            save();
            return;
        }

        // Promoted into server management (old was TEMP, or absent): the
        // shared fields must be pushed even if unchanged from `old`.
        boolean sharedChanged = !wasServerManaged
                || old == null
                || !old.name().equals(wp.name())
                || !old.dimension().equals(wp.dimension())
                || old.x() != wp.x()
                || old.y() != wp.y()
                || old.z() != wp.z()
                || old.colorRgb() != wp.colorRgb()
                || !old.group().equals(wp.group())
                || old.conversion() != wp.conversion();
        Waypoint normalized = normalizedPlayerManaged(
                wp.id(),
                wp.name(),
                wp.dimension(),
                wp.x(),
                wp.y(),
                wp.z(),
                wp.colorRgb(),
                wp.group(),
                wp.visible(),
                wp.conversion());
        putWaypoint(normalized.id(), normalized);
        setHiddenId(normalized.id(), !normalized.visible());
        NeoForge.EVENT_BUS.post(new WaypointEvent.Updated(normalized));
        save();
        if (sharedChanged) {
            sendPlayerUpsert(normalized);
        }
    }

    public static void remove(UUID id) {
        Waypoint wp = removeWaypoint(id);
        if (wp == null) {
            return;
        }

        if (isUserPublic(wp)) {
            // Removed locally right away for responsiveness; the server
            // broadcast makes it effective for everyone (echo idempotent).
            sendPublicRemove(id);
            HIDDEN_IDS.remove(id);
        } else if (isServerManaged(wp)) {
            sendPlayerRemove(id);
            HIDDEN_IDS.remove(id);
        }
        NeoForge.EVENT_BUS.post(new WaypointEvent.Removed(wp));
        save();
    }

    // ------------------------------------------------------------------ public waypoints

    /** Server broadcast: upsert of a public waypoint (login or edit). */
    public static void acceptPublicUpsert(WaypointPayloads.PublicWaypointPayload p) {
        Waypoint wp = normalizedPublic(
                p.id(),
                p.name(),
                p.dimension(),
                p.x(),
                p.y(),
                p.z(),
                p.colorRgb(),
                !HIDDEN_IDS.contains(p.id()),
                conversionOf(p.converted()));
        putWaypoint(wp.id(), wp);
        // Updated (not the cancellable Added) even for new entries: the
        // server is authoritative, listeners cannot veto the sync.
        NeoForge.EVENT_BUS.post(new WaypointEvent.Updated(wp));
    }

    /** Server broadcast: removal of a public waypoint. */
    public static void acceptPublicRemove(UUID id) {
        Waypoint wp = removeWaypoint(id);
        HIDDEN_IDS.remove(id);
        if (wp != null) {
            NeoForge.EVENT_BUS.post(new WaypointEvent.Removed(wp));
        }
    }

    private static Waypoint normalizedPublic(
            UUID id,
            String name,
            ResourceLocation dim,
            int x,
            int y,
            int z,
            int color,
            boolean visible,
            Waypoint.Conversion conversion) {
        return new Waypoint(
                id,
                name,
                dim,
                x,
                y,
                z,
                color,
                Waypoint.SOURCE_PUBLIC,
                Waypoint.GROUP_PUBLIC,
                visible,
                Waypoint.Type.PUBLIC,
                conversion);
    }

    /** Wire flag to model state: only source waypoints travel, so LINKED or NONE. */
    private static Waypoint.Conversion conversionOf(boolean converted) {
        return converted ? Waypoint.Conversion.LINKED : Waypoint.Conversion.NONE;
    }

    private static void setHiddenId(UUID id, boolean hidden) {
        if (hidden) {
            HIDDEN_IDS.add(id);
        } else {
            HIDDEN_IDS.remove(id);
        }
    }

    private static void sendPublicUpsert(Waypoint wp) {
        PacketDistributor.sendToServer(new WaypointPayloads.PublicWaypointPayload(
                wp.id(), wp.name(), wp.dimension(), wp.x(), wp.y(), wp.z(), wp.colorRgb(), wp.hasConvertedLink()));
    }

    private static void sendPublicRemove(UUID id) {
        PacketDistributor.sendToServer(new WaypointPayloads.PublicWaypointRemovePayload(id));
    }

    // ------------------------------------------------------------------ player-managed waypoints

    /** Server echo: upsert of one of THIS player's own waypoints (login or edit). */
    public static void acceptPlayerUpsert(WaypointPayloads.PlayerWaypointPayload p) {
        Waypoint wp = normalizedPlayerManaged(
                p.id(),
                p.name(),
                p.dimension(),
                p.x(),
                p.y(),
                p.z(),
                p.colorRgb(),
                p.group(),
                !HIDDEN_IDS.contains(p.id()),
                conversionOf(p.converted()));
        putWaypoint(wp.id(), wp);
        // Updated (not the cancellable Added) even for new entries: the
        // server is authoritative, listeners cannot veto the sync.
        NeoForge.EVENT_BUS.post(new WaypointEvent.Updated(wp));
    }

    /** Server echo: removal of one of THIS player's own waypoints. */
    public static void acceptPlayerRemove(UUID id) {
        Waypoint wp = removeWaypoint(id);
        HIDDEN_IDS.remove(id);
        if (wp != null) {
            NeoForge.EVENT_BUS.post(new WaypointEvent.Removed(wp));
        }
    }

    private static Waypoint normalizedPlayerManaged(
            UUID id,
            String name,
            ResourceLocation dim,
            int x,
            int y,
            int z,
            int color,
            String group,
            boolean visible,
            Waypoint.Conversion conversion) {
        return new Waypoint(
                id,
                name,
                dim,
                x,
                y,
                z,
                color,
                Waypoint.SOURCE_USER,
                group,
                visible,
                Waypoint.Type.DIMENSION,
                conversion);
    }

    private static void sendPlayerUpsert(Waypoint wp) {
        PacketDistributor.sendToServer(new WaypointPayloads.PlayerWaypointPayload(
                wp.id(),
                wp.name(),
                wp.dimension(),
                wp.x(),
                wp.y(),
                wp.z(),
                wp.colorRgb(),
                wp.group(),
                wp.hasConvertedLink()));
    }

    private static void sendPlayerRemove(UUID id) {
        PacketDistributor.sendToServer(new WaypointPayloads.PlayerWaypointRemovePayload(id));
    }

    // ------------------------------------------------------------------ banner waypoints

    /**
     * Server broadcast: upsert of a banner waypoint (login, placement, or
     * a re-broadcast). Read-only client-side: there is no C2S counterpart,
     * the server alone detects named banners in the world.
     */
    public static void acceptBannerUpsert(WaypointPayloads.BannerWaypointPayload p) {
        Waypoint wp = normalizedBanner(
                p.id(), p.name(), p.dimension(), p.x(), p.y(), p.z(), p.colorRgb(), !HIDDEN_IDS.contains(p.id()));
        putWaypoint(wp.id(), wp);
        NeoForge.EVENT_BUS.post(new WaypointEvent.Updated(wp));
    }

    /** Server broadcast: removal of a banner waypoint (the banner was broken). */
    public static void acceptBannerRemove(UUID id) {
        Waypoint wp = removeWaypoint(id);
        HIDDEN_IDS.remove(id);
        if (wp != null) {
            NeoForge.EVENT_BUS.post(new WaypointEvent.Removed(wp));
        }
    }

    private static Waypoint normalizedBanner(
            UUID id, String name, ResourceLocation dim, int x, int y, int z, int color, boolean visible) {
        return new Waypoint(
                id,
                name,
                dim,
                x,
                y,
                z,
                color,
                Waypoint.SOURCE_BANNER,
                Waypoint.GROUP_BANNERS,
                visible,
                Waypoint.Type.PUBLIC);
    }

    // ------------------------------------------------------------------ automatic waypoints

    /**
     * Server push: upsert of an automatic waypoint (bed, nether portal,
     * respawn anchor) at login, on creation, or when its kind is
     * re-enabled. Read-only client-side: there is no C2S counterpart. The
     * name, colour and group are derived from the kind; an unknown kind
     * (a newer server) is ignored.
     */
    public static void acceptAutoUpsert(WaypointPayloads.AutoWaypointPayload p) {
        AutoWaypointKind kind = AutoWaypointKind.fromId(p.kind());
        if (kind == null) {
            return;
        }

        Waypoint wp = normalizedAuto(p, kind, !HIDDEN_IDS.contains(p.id()));
        putWaypoint(wp.id(), wp);
        NeoForge.EVENT_BUS.post(new WaypointEvent.Updated(wp));
    }

    /** Server push: removal of an automatic waypoint (block gone, or kind disabled). */
    public static void acceptAutoRemove(UUID id) {
        Waypoint wp = removeWaypoint(id);
        if (wp != null) {
            NeoForge.EVENT_BUS.post(new WaypointEvent.Removed(wp));
        }
    }

    private static Waypoint normalizedAuto(
            WaypointPayloads.AutoWaypointPayload p, AutoWaypointKind kind, boolean visible) {
        return new Waypoint(
                p.id(),
                Component.translatable(Lang.autoWaypointName(kind.id())).getString(),
                p.dimension(),
                p.x(),
                p.y(),
                p.z(),
                kind.colorRgb(),
                Waypoint.SOURCE_AUTO,
                kind.group(),
                visible,
                kind.shared() ? Waypoint.Type.PUBLIC : Waypoint.Type.DIMENSION);
    }

    /**
     * Forces the visibility of every waypoint shown in a dimension. A
     * converted counterpart shares its source's visibility flag, so it
     * is applied to the source (which lives in the paired dimension).
     */
    public static void setAllVisible(ResourceLocation dim, boolean visible) {
        for (Waypoint shown : forDimension(dim)) {
            Waypoint wp = shown.isConvertedView() ? WAYPOINTS.get(shown.id()) : shown;
            if (wp != null && wp.visible() != visible) {
                update(wp.withVisible(visible));
            }
        }
    }

    /**
     * Removes the temp waypoints the player has reached (configurable
     * radius). Called periodically from the client tick.
     */
    public static void removeReachedTemp(Player player) {
        ResourceLocation dim = player.level().dimension().location();
        int radius = WaypointClientConfig.TEMP_WAYPOINT_RADIUS.get();
        long radiusSq = (long) radius * radius;
        for (Waypoint wp : all()) {
            if (wp.type() != Waypoint.Type.TEMP || !wp.dimension().equals(dim)) {
                continue;
            }

            double dx = player.getX() - (wp.x() + 0.5);
            double dy = player.getY() - wp.y();
            double dz = player.getZ() - (wp.z() + 0.5);
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                remove(wp.id());
            }
        }
    }

    /** Removes every waypoint of a given source (used by the bridge). */
    public static void removeBySource(String source) {
        List<UUID> ids = WAYPOINTS.values().stream()
                .filter(w -> w.source().equals(source))
                .map(Waypoint::id)
                .toList();
        ids.forEach(WaypointStore::remove);
    }

    // ------------------------------------------------------------------ IO

    private static void load() {
        clearWaypoints();
        HIDDEN_GROUPS.clear();
        HIDDEN_IDS.clear();
        KNOWN_GROUPS.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }

        try {
            JsonElement root = GSON.fromJson(Files.readString(file), JsonElement.class);
            if (root == null) {
                return;
            }

            // Current format: {hiddenGroups: [...], waypoints: [...]}.
            // Legacy format (pre-groups): a bare waypoint array.
            JsonArray waypoints;
            if (root.isJsonObject()) {
                JsonObject o = root.getAsJsonObject();
                waypoints = o.getAsJsonArray("waypoints");
                if (o.has("hiddenGroups")) {
                    for (JsonElement el : o.getAsJsonArray("hiddenGroups")) {
                        HIDDEN_GROUPS.add(el.getAsString());
                    }
                }
                if (o.has("hiddenIds")) {
                    for (JsonElement el : o.getAsJsonArray("hiddenIds")) {
                        HIDDEN_IDS.add(UUID.fromString(el.getAsString()));
                    }
                }
                if (o.has("groups")) {
                    for (JsonElement el : o.getAsJsonArray("groups")) {
                        KNOWN_GROUPS.add(el.getAsString());
                    }
                }
            } else {
                waypoints = root.getAsJsonArray();
            }
            if (waypoints == null) {
                return;
            }

            for (JsonElement el : waypoints) {
                JsonObject o = el.getAsJsonObject();
                // Only user waypoints are persisted: those of bridged mods
                // (Waystones...) are resynchronized every session by their
                // mod. Also purges duplicates from older versions.
                if (o.has("source")
                        && !Waypoint.SOURCE_USER.equals(o.get("source").getAsString())) {
                    continue;
                }

                ResourceLocation dim =
                        ResourceLocation.tryParse(o.get("dimension").getAsString());
                if (dim == null) {
                    continue;
                }

                Waypoint wp = new Waypoint(
                        UUID.fromString(o.get("id").getAsString()),
                        o.get("name").getAsString(),
                        dim,
                        o.get("x").getAsInt(),
                        o.get("y").getAsInt(),
                        o.get("z").getAsInt(),
                        o.get("color").getAsInt(),
                        o.has("source") ? o.get("source").getAsString() : Waypoint.SOURCE_USER,
                        o.has("group") ? o.get("group").getAsString() : Waypoint.GROUP_DEFAULT,
                        !o.has("visible") || o.get("visible").getAsBoolean(),
                        readType(o),
                        conversionOf(o.has("converted") && o.get("converted").getAsBoolean()));
                putWaypoint(wp.id(), wp);
            }

            // Migration (pre group-management files): groups created as
            // free text only exist on their waypoints — adopt them.
            for (Waypoint wp : WAYPOINTS.values()) {
                if (isEditableGroup(wp.group())) {
                    KNOWN_GROUPS.add(wp.group());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read waypoints", e);
        }
    }

    /**
     * Waypoint type, DIMENSION by default (files from older versions).
     * The former GLOBAL type (cross-dimension display) also migrates to
     * DIMENSION: PUBLIC has different semantics (server-shared) and old
     * personal waypoints must not get silently published.
     */
    private static Waypoint.Type readType(JsonObject o) {
        if (!o.has("type")) {
            return Waypoint.Type.DIMENSION;
        }

        try {
            return Waypoint.Type.valueOf(o.get("type").getAsString());
        } catch (IllegalArgumentException e) {
            return Waypoint.Type.DIMENSION;
        }
    }

    private static void save() {
        if (file == null) {
            return;
        }

        JsonArray arr = new JsonArray();
        for (Waypoint wp : WAYPOINTS.values()) {
            // Bridged mods' and PUBLIC waypoints are volatile (see load()).
            if (!Waypoint.SOURCE_USER.equals(wp.source())) {
                continue;
            }

            // Server-managed DIMENSION waypoints are volatile too: the
            // server re-syncs them fresh every session (see acceptPlayer*).
            if (isServerManaged(wp)) {
                continue;
            }

            JsonObject o = new JsonObject();
            o.addProperty("id", wp.id().toString());
            o.addProperty("name", wp.name());
            o.addProperty("dimension", wp.dimension().toString());
            o.addProperty("x", wp.x());
            o.addProperty("y", wp.y());
            o.addProperty("z", wp.z());
            o.addProperty("color", wp.colorRgb());
            o.addProperty("source", wp.source());
            o.addProperty("group", wp.group());
            o.addProperty("visible", wp.visible());
            o.addProperty("type", wp.type().name());
            if (wp.hasConvertedLink()) {
                o.addProperty("converted", true);
            }
            arr.add(o);
        }

        JsonArray hidden = new JsonArray();
        for (String group : HIDDEN_GROUPS) {
            hidden.add(group);
        }

        JsonArray hiddenIds = new JsonArray();
        for (UUID id : HIDDEN_IDS) {
            hiddenIds.add(id.toString());
        }

        JsonArray groups = new JsonArray();
        for (String group : KNOWN_GROUPS) {
            groups.add(group);
        }

        JsonObject root = new JsonObject();
        root.add("hiddenGroups", hidden);
        root.add("hiddenIds", hiddenIds);
        root.add("groups", groups);
        root.add("waypoints", arr);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("Failed to save waypoints", e);
        }
    }
}
