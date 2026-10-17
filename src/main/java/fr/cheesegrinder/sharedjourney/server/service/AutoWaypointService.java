package fr.cheesegrinder.sharedjourney.server.service;

import fr.cheesegrinder.sharedjourney.common.config.WaypointServerConfig;
import fr.cheesegrinder.sharedjourney.common.network.WaypointPayloads;
import fr.cheesegrinder.sharedjourney.common.waypoint.AutoWaypointKind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.network.PacketDistributor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatic contextual waypoints (bed, nether portal, respawn anchor),
 * S2C only like {@link BannerWaypointService}: the server alone detects
 * them ({@code AutoWaypointEvents}) and pushes them, no client request
 * exists. Persisted in the world folder so they survive a restart without
 * re-scanning any chunk.
 *
 * <p>Scope per kind: BED and ANCHOR are PRIVATE (they follow the player's
 * own spawn point: at most one of the two per player, moved at every spawn
 * change, sent to that player only); PORTAL is SHARED (a landmark, sent to
 * everyone, one per physical portal). Each kind is individually switchable
 * in the server config; a disabled kind is hidden from the clients but its
 * entries are kept on disk so re-enabling restores them.
 *
 * <p>Cleanup without scanning: a player breaking the block is handled
 * instantly (see the events class); everything else (explosions, pistons,
 * a broken portal frame, creative tools) is caught by a cheap periodic
 * check of the few tracked positions that sit in already-loaded chunks —
 * never loading a chunk.
 */
public final class AutoWaypointService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Sanity cap on shared (portal) entries: a griefer cannot flood every client. */
    private static final int MAX_SHARED = 256;

    /** Validation period (ticks) of the tracked positions against the world. */
    private static final int VALIDATE_INTERVAL_TICKS = 200;

    /** A nether portal interior is at most 21 blocks wide/tall: bound every walk. */
    private static final int MAX_PORTAL_SCAN = 24;

    /** Period (ticks) of the "standing in a portal" safety net over the online players. */
    private static final int PORTAL_SCAN_INTERVAL_TICKS = 5;

    /**
     * Extra margin (blocks) around the player's box when the travel events
     * find no portal block inside it: the player may stand a hair off the
     * portal cell at the moment the event fires.
     */
    private static final double PORTAL_SEARCH_MARGIN = 1.0;

    /**
     * One tracked waypoint. {@code owner} is null for shared kinds;
     * {@code color} is an RGB override (the bed's dye colour) or
     * {@link AutoWaypointKind#NO_COLOR} for the kind's own colour.
     */
    private record Entry(
            UUID id,
            AutoWaypointKind kind,
            @Nullable UUID owner,
            ResourceLocation dimension,
            int x,
            int y,
            int z,
            int color) {

        BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        WaypointPayloads.AutoWaypointPayload toPayload() {
            return new WaypointPayloads.AutoWaypointPayload(id, kind.id(), dimension, x, y, z, color);
        }

        Entry withColor(int newColor) {
            return new Entry(id, kind, owner, dimension, x, y, z, newColor);
        }
    }

    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();

    /** Enabled state last pushed to the clients, per kind (server thread only). */
    private static final Map<AutoWaypointKind, Boolean> APPLIED = new EnumMap<>(AutoWaypointKind.class);

    private static MinecraftServer server;
    private static Path file;
    private static int tickCounter;
    private static int portalScanCounter;

    private AutoWaypointService() {}

    // ------------------------------------------------------------------ lifecycle

    public static void init(MinecraftServer minecraftServer) {
        server = minecraftServer;
        Path root =
                minecraftServer.getWorldPath(LevelResource.ROOT).resolve("data").resolve("sharedjourney");
        file = root.resolve("waypoints_auto.json");
        load();
        for (AutoWaypointKind kind : AutoWaypointKind.values()) {
            APPLIED.put(kind, isEnabled(kind));
        }
    }

    public static void shutdown() {
        ENTRIES.clear();
        APPLIED.clear();
        file = null;
        server = null;
        tickCounter = 0;
        portalScanCounter = 0;
    }

    /** Full send at login: one upsert per enabled entry visible to this player. */
    public static void sendAllTo(ServerPlayer player) {
        for (Entry entry : ENTRIES.values()) {
            if (isEnabled(entry.kind()) && isVisibleTo(entry, player.getUUID())) {
                PacketDistributor.sendToPlayer(player, entry.toPayload());
            }
        }
    }

    /** Server config switches: server-side toggle of one kind. */
    public static boolean isEnabled(AutoWaypointKind kind) {
        return switch (kind) {
            case BED -> WaypointServerConfig.BED_WAYPOINTS_ENABLED.get();
            case PORTAL -> WaypointServerConfig.PORTAL_WAYPOINTS_ENABLED.get();
            case ANCHOR -> WaypointServerConfig.ANCHOR_WAYPOINTS_ENABLED.get();
        };
    }

    /**
     * Called after a config change (reload, ops screen): for every kind
     * whose switch flipped, pushes its entries to (or retracts them from)
     * the clients. Entries are kept either way.
     */
    public static void refreshEnabled() {
        if (server == null) {
            return;
        }

        for (AutoWaypointKind kind : AutoWaypointKind.values()) {
            boolean now = isEnabled(kind);
            Boolean before = APPLIED.put(kind, now);
            if (before != null && before == now) {
                continue;
            }

            for (Entry entry : ENTRIES.values()) {
                if (entry.kind() == kind) {
                    push(entry, now);
                }
            }
        }
    }

    // ------------------------------------------------------------------ world events

    /**
     * A player's spawn point changed (bed, respawn anchor, command, reset).
     * Their previous BED/ANCHOR waypoint is dropped (a player has a single
     * spawn point) and, when the new point is a bed or an anchor of an
     * enabled kind, replaced by one at the new position. {@code pos} is
     * null when the spawn point is being cleared.
     */
    public static void onSpawnSet(ServerPlayer player, ResourceKey<Level> dimension, @Nullable BlockPos pos) {
        Entry fresh = spawnEntry(player, dimension, pos);
        UUID keep = fresh == null ? null : fresh.id();
        boolean changed = removeOwnedSpawnEntries(player.getUUID(), keep);
        if (fresh != null && !fresh.equals(ENTRIES.put(fresh.id(), fresh))) {
            changed = true;
            push(fresh, true);
        }

        if (changed) {
            save();
        }
    }

    /** The spawn waypoint entry for a new spawn point, or null when it deserves none. */
    @Nullable
    private static Entry spawnEntry(ServerPlayer player, ResourceKey<Level> dimension, @Nullable BlockPos pos) {
        if (pos == null || server == null) {
            return null;
        }

        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.isLoaded(pos)) {
            return null;
        }

        AutoWaypointKind kind = spawnKind(level.getBlockState(pos));
        if (kind == null || !isEnabled(kind)) {
            return null;
        }

        return new Entry(
                entryId(kind, player.getUUID(), dimension.location(), pos),
                kind,
                player.getUUID(),
                dimension.location(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                colorOf(kind, level.getBlockState(pos)));
    }

    /** The bed's dye colour (RGB) for a BED entry, otherwise the "kind colour" marker. */
    private static int colorOf(AutoWaypointKind kind, BlockState state) {
        if (kind == AutoWaypointKind.BED && state.getBlock() instanceof BedBlock bed) {
            return bed.getColor().getTextureDiffuseColor() & 0xFFFFFF;
        }

        return AutoWaypointKind.NO_COLOR;
    }

    @Nullable
    private static AutoWaypointKind spawnKind(BlockState state) {
        if (state.is(BlockTags.BEDS)) {
            return AutoWaypointKind.BED;
        }

        if (state.is(Blocks.RESPAWN_ANCHOR)) {
            return AutoWaypointKind.ANCHOR;
        }

        return null;
    }

    /** Drops this player's BED/ANCHOR entries except {@code keep}; true if any was removed. */
    private static boolean removeOwnedSpawnEntries(UUID owner, @Nullable UUID keep) {
        boolean removed = false;
        for (Entry entry : ENTRIES.values()) {
            if (owner.equals(entry.owner()) && !entry.id().equals(keep)) {
                ENTRIES.remove(entry.id());
                push(entry, false);
                removed = true;
            }
        }

        return removed;
    }

    /**
     * A player went through (or arrived by) a nether portal: records it as
     * a shared waypoint. Any portal block the player's body overlaps leads
     * to the same canonical position (bottom row, middle of the portal), so
     * every crossing of one portal maps to one single waypoint. The portal
     * block is looked up inside the player's box first, then within a small
     * margin around it; {@code trigger} only labels the diagnostic log line
     * written when no portal block is found at all.
     */
    public static void onPortalTravel(ServerPlayer player, String trigger) {
        if (!canRecordPortal(player)) {
            return;
        }

        ServerLevel level = player.serverLevel();
        AABB box = player.getBoundingBox();
        BlockPos portalBlock = findPortalBlock(level, box);
        if (portalBlock == null) {
            LOGGER.info(
                    "Automatic portal waypoint: no portal block found on {} for {} in {} at {} (box {})",
                    trigger,
                    player.getGameProfile().getName(),
                    level.dimension().location(),
                    player.blockPosition(),
                    box);
            return;
        }

        recordPortal(level, portalBlock);
    }

    /**
     * Safety net independent of the travel events: every few ticks, a
     * player whose box overlaps a nether portal block is recorded. Standing
     * inside a portal is "using" it for our purpose; the deterministic id
     * dedups the repeated checks.
     */
    private static void scanPortals(MinecraftServer minecraftServer) {
        if (!isEnabled(AutoWaypointKind.PORTAL)) {
            return;
        }

        for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
            scanPortal(player);
        }
    }

    private static void scanPortal(ServerPlayer player) {
        if (player.isSpectator() || !canRecordPortal(player)) {
            return;
        }

        ServerLevel level = player.serverLevel();
        BlockPos portalBlock = nearestPortalBlock(level, player.getBoundingBox(), true);
        if (portalBlock != null) {
            recordPortal(level, portalBlock);
        }
    }

    /** A hidden player must not leak where they are through a shared marker. */
    private static boolean canRecordPortal(ServerPlayer player) {
        return isEnabled(AutoWaypointKind.PORTAL) && !SyncService.isHidden(player.getUUID());
    }

    /** Records (once) the shared waypoint of the portal that contains {@code portalBlock}. */
    private static void recordPortal(ServerLevel level, BlockPos portalBlock) {
        BlockPos anchor = portalAnchor(level, portalBlock);
        ResourceLocation dimension = level.dimension().location();
        UUID id = entryId(AutoWaypointKind.PORTAL, null, dimension, anchor);
        if (ENTRIES.containsKey(id)) {
            return;
        }

        if (countShared() >= MAX_SHARED) {
            LOGGER.warn("Automatic portal waypoint limit reached ({}), new portal ignored", MAX_SHARED);
            return;
        }

        Entry entry = new Entry(
                id,
                AutoWaypointKind.PORTAL,
                null,
                dimension,
                anchor.getX(),
                anchor.getY(),
                anchor.getZ(),
                AutoWaypointKind.NO_COLOR);
        ENTRIES.put(id, entry);
        save();
        push(entry, true);
    }

    /**
     * A bed or respawn anchor was broken by a player: drops every waypoint
     * at that position (the caller passes both halves of a bed).
     */
    public static void onBlockBroken(ResourceKey<Level> dimension, BlockPos pos) {
        boolean changed = false;
        for (Entry entry : ENTRIES.values()) {
            if (entry.dimension().equals(dimension.location()) && entry.pos().equals(pos)) {
                ENTRIES.remove(entry.id());
                push(entry, false);
                changed = true;
            }
        }

        if (changed) {
            save();
        }
    }

    // ------------------------------------------------------------------ periodic validation

    /**
     * Every {@value #VALIDATE_INTERVAL_TICKS} ticks, checks the tracked
     * positions that lie in loaded chunks against the block that should be
     * there. Cheap (a handful of positions, no chunk is ever loaded) and
     * catches every removal the break event cannot see.
     */
    public static void tick(MinecraftServer minecraftServer) {
        if (file == null) {
            return;
        }

        portalScanCounter++;
        if (portalScanCounter >= PORTAL_SCAN_INTERVAL_TICKS) {
            portalScanCounter = 0;
            scanPortals(minecraftServer);
        }

        tickCounter++;
        if (tickCounter < VALIDATE_INTERVAL_TICKS) {
            return;
        }

        tickCounter = 0;
        validate(minecraftServer);
    }

    private static void validate(MinecraftServer minecraftServer) {
        List<Entry> stale = new ArrayList<>();
        boolean changed = false;
        for (Entry entry : ENTRIES.values()) {
            if (isStale(minecraftServer, entry)) {
                stale.add(entry);
            } else {
                changed |= refreshColor(minecraftServer, entry);
            }
        }

        for (Entry entry : stale) {
            ENTRIES.remove(entry.id());
            push(entry, false);
            changed = true;
        }

        if (changed) {
            save();
        }
    }

    /**
     * A bed replaced in place by one of another dye colour keeps the same
     * position (hence id): the entry follows the colour of what is there
     * now. True when the entry was updated and re-pushed.
     */
    private static boolean refreshColor(MinecraftServer minecraftServer, Entry entry) {
        if (entry.kind() != AutoWaypointKind.BED) {
            return false;
        }

        ServerLevel level = minecraftServer.getLevel(ResourceKey.create(Registries.DIMENSION, entry.dimension()));
        if (level == null || !level.isLoaded(entry.pos())) {
            return false;
        }

        int color = colorOf(entry.kind(), level.getBlockState(entry.pos()));
        if (color == entry.color()) {
            return false;
        }

        Entry updated = entry.withColor(color);
        ENTRIES.put(updated.id(), updated);
        push(updated, true);
        return true;
    }

    /** True only when the chunk is loaded AND the expected block is gone. */
    private static boolean isStale(MinecraftServer minecraftServer, Entry entry) {
        ServerLevel level = minecraftServer.getLevel(ResourceKey.create(Registries.DIMENSION, entry.dimension()));
        BlockPos pos = entry.pos();
        if (level == null || !level.isLoaded(pos)) {
            return false;
        }

        BlockState state = level.getBlockState(pos);
        return switch (entry.kind()) {
            case BED -> !state.is(BlockTags.BEDS);
            case PORTAL -> !state.is(Blocks.NETHER_PORTAL);
            case ANCHOR -> !state.is(Blocks.RESPAWN_ANCHOR);
        };
    }

    // ------------------------------------------------------------------ portal geometry

    /**
     * A nether portal block overlapped by the box, else one within
     * {@value #PORTAL_SEARCH_MARGIN} block(s) of it, or null.
     */
    @Nullable
    private static BlockPos findPortalBlock(ServerLevel level, AABB box) {
        BlockPos inside = nearestPortalBlock(level, box, false);
        if (inside != null) {
            return inside;
        }

        return nearestPortalBlock(level, box.inflate(PORTAL_SEARCH_MARGIN), true);
    }

    /**
     * The portal block in the box closest to its centre, or null. With
     * {@code requireLoaded}, a box touching a chunk that is not loaded yields
     * null instead of loading it (the player's own cell is always loaded, so
     * the exact-box lookup of the travel events skips the check: a chunk
     * placed this very tick may not be visible yet).
     */
    @Nullable
    private static BlockPos nearestPortalBlock(ServerLevel level, AABB box, boolean requireLoaded) {
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        if (requireLoaded && !level.hasChunksAt(min, max)) {
            return null;
        }

        Vec3 center = box.getCenter();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
                continue;
            }

            double distance = pos.distToCenterSqr(center.x, center.y, center.z);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = pos.immutable();
            }
        }

        return nearest;
    }

    /**
     * Canonical position of the portal containing {@code start}: the
     * bottom row, middle block. Deterministic from the portal's shape, so
     * the same portal always yields the same position.
     */
    private static BlockPos portalAnchor(ServerLevel level, BlockPos start) {
        Direction.Axis axis = level.getBlockState(start).getValue(NetherPortalBlock.AXIS);
        Direction along = Direction.get(Direction.AxisDirection.POSITIVE, axis);
        BlockPos pos = start;
        for (int i = 0; i < MAX_PORTAL_SCAN && isPortal(level, pos.below()); i++) {
            pos = pos.below();
        }

        for (int i = 0; i < MAX_PORTAL_SCAN && isPortal(level, pos.relative(along.getOpposite())); i++) {
            pos = pos.relative(along.getOpposite());
        }

        int width = 1;
        while (width < MAX_PORTAL_SCAN && isPortal(level, pos.relative(along, width))) {
            width++;
        }

        return pos.relative(along, width / 2);
    }

    private static boolean isPortal(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.NETHER_PORTAL);
    }

    // ------------------------------------------------------------------ distribution

    private static boolean isVisibleTo(Entry entry, UUID player) {
        return entry.kind().shared() || player.equals(entry.owner());
    }

    private static int countShared() {
        int count = 0;
        for (Entry entry : ENTRIES.values()) {
            if (entry.kind().shared()) {
                count++;
            }
        }

        return count;
    }

    /** Sends an upsert (visible) or a removal (not) to everyone who may see the entry. */
    private static void push(Entry entry, boolean visible) {
        if (visible && !isEnabled(entry.kind())) {
            return;
        }

        CustomPacketPayload payload =
                visible ? entry.toPayload() : new WaypointPayloads.AutoWaypointRemovePayload(entry.id());
        if (entry.kind().shared()) {
            PacketDistributor.sendToAllPlayers(payload);
            return;
        }

        if (server == null || entry.owner() == null) {
            return;
        }

        ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner());
        if (owner != null) {
            PacketDistributor.sendToPlayer(owner, payload);
        }
    }

    /** Deterministic id: stable across restarts and re-placements at the same spot. */
    private static UUID entryId(AutoWaypointKind kind, @Nullable UUID owner, ResourceLocation dimension, BlockPos pos) {
        String key = "auto:" + kind.id() + ":" + (owner == null ? "" : owner + ":") + dimension + ":" + pos.getX() + ","
                + pos.getY() + "," + pos.getZ();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ IO

    private static void load() {
        ENTRIES.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }

        try {
            JsonElement root = GSON.fromJson(Files.readString(file), JsonElement.class);
            if (root == null || !root.isJsonArray()) {
                return;
            }

            for (JsonElement el : root.getAsJsonArray()) {
                Entry entry = readEntry(el.getAsJsonObject());
                if (entry != null) {
                    ENTRIES.put(entry.id(), entry);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read automatic waypoints", e);
        }
    }

    @Nullable
    private static Entry readEntry(JsonObject o) {
        AutoWaypointKind kind = AutoWaypointKind.fromId(o.get("kind").getAsString());
        ResourceLocation dimension =
                ResourceLocation.tryParse(o.get("dimension").getAsString());
        if (kind == null || dimension == null) {
            return null;
        }

        UUID owner = o.has("owner") ? UUID.fromString(o.get("owner").getAsString()) : null;
        if (!kind.shared() && owner == null) {
            return null;
        }

        return new Entry(
                UUID.fromString(o.get("id").getAsString()),
                kind,
                owner,
                dimension,
                o.get("x").getAsInt(),
                o.get("y").getAsInt(),
                o.get("z").getAsInt(),
                o.has("color") ? o.get("color").getAsInt() : AutoWaypointKind.NO_COLOR);
    }

    private static void save() {
        if (file == null) {
            return;
        }

        JsonArray arr = new JsonArray();
        for (Entry entry : ENTRIES.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", entry.id().toString());
            o.addProperty("kind", entry.kind().id());
            if (entry.owner() != null) {
                o.addProperty("owner", entry.owner().toString());
            }
            o.addProperty("dimension", entry.dimension().toString());
            o.addProperty("x", entry.x());
            o.addProperty("y", entry.y());
            o.addProperty("z", entry.z());
            if (entry.color() != AutoWaypointKind.NO_COLOR) {
                o.addProperty("color", entry.color());
            }
            arr.add(o);
        }

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(arr));
        } catch (IOException e) {
            LOGGER.error("Failed to save automatic waypoints", e);
        }
    }
}
