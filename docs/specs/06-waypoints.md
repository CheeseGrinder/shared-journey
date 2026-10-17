# 06 — Waypoints

## 1. Model (`api.Waypoint`, immutable record)

| Field | Meaning |
|-------|---------|
| `id` (UUID) | Identity. Random for user waypoints; deterministic for banners (§6), automatic ones (§3.4) and bridged ones. |
| `name` | Display name (≤ 48 chars on the wire). |
| `dimension` | Home dimension (every type is bound to one). |
| `x, y, z` | Block position. |
| `colorRgb` | 24-bit RGB. |
| `source` | `user`, `public`, `banner`, `auto`, or the modId of a bridged mod. |
| `group` | Group name; blank/null is normalized to `default`. |
| `visible` | Per-waypoint display flag. |
| `type` | `DIMENSION`, `PUBLIC`, `TEMP`. |
| `conversion` | `NONE`, `LINKED` (the player asked to also show a converted counterpart in the paired Overworld / Nether dimension) or `VIEW` (that derived counterpart, never stored). See [14](14-converted-waypoints.md). |

Reserved groups: `default`, `deaths`, `public`, `banners`, `beds`, `portals`, `anchors`. A group is **editable** (can be
renamed/deleted) iff it is not reserved and holds no non-`user` waypoint.

### 1.1 Types and where they live

| Type | Created by | Persisted | Shared |
|------|-----------|-----------|--------|
| `DIMENSION` (`source=user`) | Player | **Server**, per player (`waypointStorage=SERVER`, default) or client file (`CLIENT`) | Never |
| `TEMP` | Player | Client file | Never; auto-removed on arrival |
| `PUBLIC` (`source=user` in the editor, normalized to `source=public`, group `public`) | Player | Server world file | Everyone |
| Banner (`source=banner`, type `PUBLIC`, group `banners`) | Server (named banner placed) | Server world file | Everyone, read-only |
| Automatic bed / respawn anchor (`source=auto`, type `DIMENSION`, group `beds` / `anchors`) | Server (spawn point set) | Server world file | Its owner only, read-only |
| Automatic nether portal (`source=auto`, type `PUBLIC`, group `portals`) | Server (portal used) | Server world file | Everyone, read-only |
| Bridged (`source=<modId>`, group=<modId>, type `DIMENSION`) | JourneyMap-API mod | Nowhere (volatile) | Never |

| ID | Requirement |
|----|-------------|
| WP-01 | `visible` of PUBLIC, banner, automatic and server-managed DIMENSION waypoints is a **per-client** choice kept in a local `hiddenIds` set; it is never sent to the server nor shared. |
| WP-02 | Group visibility is also local (`hiddenGroups`). A waypoint is shown iff its own flag **and** its group's flag are on (`WaypointStore.isShown`). |
| WP-03 | Server-managed (`serverManagesWaypoints`) applies to **user DIMENSION** waypoints only; `TEMP` stay local; `PUBLIC` always go through the server. |

## 2. Client store (`WaypointStore`)

In-memory source of truth for everything the client knows, regardless of persistence.

| ID | Requirement |
|----|-------------|
| WS-01 | Local file `<cache>/waypoints.json`: `{hiddenGroups, hiddenIds, groups, waypoints[]}`; a bare array is accepted as the legacy format. An entry carries `"converted": true` when linked to the paired dimension ([14 CONV-09](14-converted-waypoints.md)). |
| WS-02 | Only `source=user` waypoints that are **not** server-managed are written. Bridged, public, banner, automatic and server-managed ones are volatile (resynchronized each session). Loading skips any entry whose source is not `user`. |
| WS-03 | Loading migrates: unknown/missing type → `DIMENSION` (the former `GLOBAL` type also becomes `DIMENSION`, never `PUBLIC`); groups that exist only through a waypoint are adopted as known groups. |
| WS-04 | `add` routes: user PUBLIC → `PublicWaypointPayload` (the local copy appears with the server echo); server-managed user DIMENSION → `PlayerWaypointPayload`; otherwise post the cancellable `WaypointEvent.Added`, then store locally. Routes are gated on `source=user`: bridged, banner and automatic waypoints MUST NEVER be pushed to a Shared Journey channel. |
| WS-05 | `update` handles type transitions: private → PUBLIC (publish, drop the private server copy if any, echo re-adds), PUBLIC → private (server copy removed, group reset to `default`, source `user`), DIMENSION ↔ TEMP (server copy added/removed). Shared fields go through the server; the `visible` flag stays local. |
| WS-06 | Server echoes (`accept*Upsert`) post `WaypointEvent.Updated` (never the cancellable `Added`): the server is authoritative, listeners cannot veto a sync. |
| WS-07 | `remove` posts `WaypointEvent.Removed` and sends the matching remove payload for PUBLIC and server-managed waypoints. |
| WS-08 | `forDimension` serves an immutable per-dimension index rebuilt lazily and invalidated by every mutation (called several times per frame). It also holds the derived converted counterparts of linked waypoints ([14 CONV-02](14-converted-waypoints.md)); `all()` does not. |
| WS-09 | `removeReachedTemp` runs every 20 ticks: a TEMP waypoint in the player's dimension within `waypoints.tempWaypointRadius` (3-D distance, default 8) is removed. |
| WS-10 | Group management: `createGroup` (trimmed, non-empty, unique), `renameGroup` (re-routes each member through `update` so server-managed ones are pushed; moves hidden state), `deleteGroup` (deletes the group **and all its waypoints**). |
| WS-11 | Session close saves the file, then clears waypoints, hidden sets and groups. |

## 3. Server services

### 3.1 Public waypoints (`PublicWaypointService`)

| ID | Requirement |
|----|-------------|
| PUB-01 | File `waypoints_public.json` (array of `{id,name,dimension,x,y,z,color[,converted]}`), loaded at start, saved after every change. |
| PUB-02 | `handleUpsert`: name trimmed, empty → rejected, > 48 → truncated; colour masked to 24 bits; at most **1 024** waypoints (an upsert of a *new* one at the cap is ignored with a warning). Accepted upserts are persisted and broadcast to **all** players. |
| PUB-03 | `handleRemove`: unknown id ignored; otherwise persisted and broadcast. |
| PUB-04 | Full list is sent to each player at login (one upsert per waypoint). |
| PUB-05 | **No ownership and no permission check**: any player may edit or delete any public waypoint (including its Overworld / Nether link, [14](14-converted-waypoints.md)). |
| PUB-06 | The Overworld / Nether link flag (`converted`) is a shared field of the upsert; the server keeps the stored value while `convertedWaypointsEnabled` is off ([14 CONV-11](14-converted-waypoints.md)). Same rule for private waypoints (PRI-02). |

### 3.2 Private waypoints (`PlayerWaypointService`)

| ID | Requirement |
|----|-------------|
| PRI-01 | One file per player: `waypoints/<uuid>.json` (array of `{id,name,dimension,x,y,z,color,group[,converted]}`); no in-memory cache, read/written on each edit. |
| PRI-02 | Upsert sanitization as for public, plus group: blank → `default`, trimmed, ≤ 32 chars. Cap **512** per player (new ones above the cap ignored). |
| PRI-03 | The echo goes **only** to the originating player; nothing is ever broadcast. Full list sent at login. |

### 3.3 Banner waypoints (`BannerWaypointService`)

| ID | Requirement |
|----|-------------|
| BAN-01 | Detection (`BannerWaypointEvents`): an entity places a banner block whose block entity has a custom name (set by renaming the *item* at an anvil) → waypoint named after it. Breaking a tracked banner removes it. |
| BAN-02 | `id = UUID.nameUUIDFromBytes("banner:<dim>:<x>,<y>,<z>")`: stable across restarts and re-placements at the same spot. |
| BAN-03 | Colour = the exact colour of the vanilla map-decoration icon for that dye (16-entry table), not the leather-dye formula. |
| BAN-04 | Persisted in `waypoints_banners.json`, broadcast S2C only, full send at login. No client can create or delete one. |
| BAN-05 | Client side: read-only (no Edit, no Delete, no edit-on-double-click); only teleport (OP), view and local visibility apply. |
| BAN-06 | Not detected: named banners created by world generation, commands or non-entity placement. |

### 3.4 Automatic waypoints (`AutoWaypointService`, `AutoWaypointEvents`)

Contextual waypoints created by the server from what players do, one **kind** each
(`common.waypoint.AutoWaypointKind`): bed, nether portal, respawn anchor. Same architecture as
banners (S2C only, world-persisted, read-only on the client), plus a per-kind scope.

| Kind | Id | Group | Colour | Scope | Trigger |
|------|----|-------|--------|-------|---------|
| Bed | `bed` | `beds` | `#D84040` | **Private** to the player | `PlayerSetSpawnEvent` whose new spawn block is a bed (sleeping in it, even by day) |
| Respawn anchor | `anchor` | `anchors` | `#E8A640` | **Private** to the player | `PlayerSetSpawnEvent` whose new spawn block is a respawn anchor (charged, then used) |
| Nether portal | `portal` | `portals` | `#A040E0` | **Shared** with everyone | A player going through a portal: recorded at both ends |

| ID | Requirement |
|----|-------------|
| AUTO-01 | Bed and anchor follow the real vanilla spawn point (they hook the event vanilla fires for it; a cancelled event records nothing). A player has a single spawn point, so setting a new one **replaces** that player's previous bed/anchor waypoint (of either kind); a spawn cleared or moved onto a non-bed/non-anchor block (`/spawnpoint`, reset) just removes it. |
| AUTO-02 | Portal trigger is "a portal that was actually used": the origin end on `EntityTravelToDimensionEvent` (the player still stands inside the portal), the arrival end on `PlayerChangedDimensionEvent` (just placed inside the destination portal). Lighting a frame creates nothing, so unused portals never spam the map. A player hidden from the map ([04](04-privacy-and-anti-exploit.md)) records nothing, so a shared marker never reveals where they are. |
| AUTO-03 | Portal position is canonical: from any portal block overlapped by the player's body, walk down to the bottom row, to the low-coordinate end of the portal along its axis, then to its middle block. Every crossing of one portal gives the same position, hence the same id (no duplicates). |
| AUTO-04 | `id = UUID.nameUUIDFromBytes("auto:<kind>:[<owner>:]<dim>:<x>,<y>,<z>")`: stable across restarts; a repeated trigger for an existing entry is ignored (no re-broadcast). Private ids include the owner, so two players sharing a bed each keep their own waypoint. |
| AUTO-05 | Each kind has its own server switch (`bedWaypointsEnabled`, `portalWaypointsEnabled`, `anchorWaypointsEnabled`, default true; ops tab and config, [08](08-configuration.md)). A disabled kind records nothing new and is **hidden** from clients (removal pushed on a config change, skipped at login); its entries stay on disk, so re-enabling restores them. |
| AUTO-06 | Persisted in `waypoints_auto.json` (array of `{id, kind, owner?, dimension, x, y, z}`), loaded at start. At login a player receives every shared entry plus their own private ones. Shared entries are capped at **256** (new portals above the cap are ignored with a warning); private ones are bounded by design (one per player). |
| AUTO-07 | Instant removal: a player breaking a bed (both halves are covered) or an anchor drops its waypoint (`BlockEvent.BreakEvent`, lowest priority, ignored when cancelled). |
| AUTO-08 | Lazy removal: every 200 ticks the tracked positions whose chunk is **already loaded** are checked against the expected block (any bed, nether portal, respawn anchor); a mismatch removes the waypoint. No chunk is ever loaded and nothing is scanned; this covers explosions, pistons, a broken portal frame, creative tools and commands. Entries in unloaded chunks stay until their chunk loads. |
| AUTO-09 | Wire: `AutoWaypointPayload(id, kind, dimension, x, y, z)` / `AutoWaypointRemovePayload(id)`, S2C only. Name, colour and group are derived client-side from the kind (translated `sharedjourney.waypoint.auto.<id>`, resolved in the client's language when the payload is received). An unknown kind is ignored (newer server). |
| AUTO-10 | Client: `source=auto`, group by kind, type `PUBLIC` for shared / `DIMENSION` for private, read-only (`Waypoint.isWorldDerived()`: no Edit, no Delete, no edit-on-double-click; view, teleport (OP) and local visibility apply). Dedicated icon per kind (`textures/gui/waypoint/{bed,portal,anchor}.png`, grayscale 16x16 tinted by the colour, drawn by every surface through `WaypointIcons.textureFor`); type label "Automatic" in the manager. Group visibility toggles work per kind. |
| AUTO-11 | The reserved groups `beds`, `portals`, `anchors` cannot be created, renamed or deleted by the user and are not assignable. |

## 4. Death waypoints

| ID | Requirement |
|----|-------------|
| DTH-01 | When the vanilla death screen opens and both `waypoints.deathWaypoints` (client) and `waypoints.deathWaypointsEnabled` (server, announced) are true, a waypoint is created at the player's block position. |
| DTH-02 | Name `Death HH:mm` (local time), colour `#C03030`, group `deaths`, `source=user`, type `DIMENSION` (so it follows the normal storage rules). |
| DTH-03 | A death at the same block as the last one does not create a duplicate (the death screen can reopen on resize). |
| DTH-04 | Death waypoints cannot be edited (list: no Edit; map: no edit-on-double-click); they can be deleted. |

## 5. In-world beacons (`WaypointBeaconRenderer`)

Rendered after translucent blocks when `waypoints.waypointBeacons` is on.

| ID | Requirement |
|----|-------------|
| BCN-01 | A waypoint is drawn iff shown (`isShown`), in the player's dimension, and its **horizontal** distance from the camera lies in `[beaconMinDistance, beaconMaxDistance]` (defaults 4 / 512; ranges 0–512 / 16–4096). |
| BCN-02 | Beam: the vanilla beacon beam texture tinted with the waypoint colour; beyond 80 % of the render distance it is drawn clamped toward the camera (same direction) so far beams stay visible. |
| BCN-03 | Icon: a camera-facing sprite (diamond; death, banner, bed, portal, anchor and converted-counterpart variants) tinted with the colour, anchored 1.2 blocks above the waypoint, always visible through blocks, apparent size constant with distance (clamped to the render distance); sampled with a dedicated render type and shader (sharp bilinear, half-texel UV inset) to avoid a crushed look. |
| BCN-04 | Label (name + distance) above the icon, shown only while the crosshair is within ~12° of the waypoint; boosted up to ×1.5 beyond 32 blocks. The beam, icon and label are drawn in two passes (beams, flush, then text) so the translucent beam never masks the text. |

## 6. Waypoint UI

### 6.1 Management screen (`WaypointListScreen`, key `U`)

- Left panel: "All" pseudo-group plus every group; click selects (gold), checkbox toggles
  the group's visibility, live counters; buttons New / Rename / Delete / Done. Rename and
  Delete are enabled only for editable groups.
- Right panel: waypoints of the selected group, with a final "+ New waypoint" row. Per row:
  colour swatch, name and info, **visibility toggle**, **Overworld / Nether link** (arrows button,
  only for linkable waypoints while the server allows it, [14](14-converted-waypoints.md)), **Edit** (hidden for banner, automatic and
  death), **View on map** (enabled in the same dimension), **Teleport** (OP only),
  **Delete** `x` (hidden for banner and automatic).
- Modals (`ModalScreen`): group name prompt, delete-group confirmation.

### 6.2 Edit / create form (`WaypointEditScreen`, key `B` = create at the player)

Fields: name (≤ 48), X/Y/Z, group (free text with autocompletion over assignable groups —
a new name creates the group on save; **locked to `public` while the type is PUBLIC**),
colour (hex field, 10-colour palette, random, HSB picker), visibility, type (cycles
DIMENSION → PUBLIC → TEMP), and, for a linkable waypoint while the server allows it, a
full-width "Converted: On/Off" row ([14 CONV-07](14-converted-waypoints.md)). Enter saves.

| ID | Requirement |
|----|-------------|
| UI-WP-01 | A blank name on save falls back to the original name; a non-numeric coordinate falls back to the original value. |
| UI-WP-02 | `assignableGroups` = `default` + editable groups (excludes `deaths`, `public`, `banners`, `beds`, `portals`, `anchors`, bridged groups). |
| UI-WP-03 | Default name for a new waypoint: `X:<x> Z:<z>` (temp: `Temp X:… Z:…`); default colour random; default Y = loaded-chunk surface Y if known, else the player's Y. |

## 7. Events (client side, `NeoForge.EVENT_BUS`)

`WaypointEvent.Added` (cancellable, local path only), `Updated`, `Removed`. Waypoints from
the JourneyMap bridge also flow through them. The derived converted counterparts
([14](14-converted-waypoints.md)) never do: only their source waypoint raises events.

## Acceptance checks

1. Create a private waypoint, reconnect from another machine: it is still there
   (`waypointStorage=SERVER`).
2. Set `waypointStorage=CLIENT`: waypoints are in the client's `waypoints.json` only.
3. Create a public waypoint: every connected client sees it; hide it on one client — the
   others still see it; reconnect — it stays hidden there.
4. Rename an item `Home` at an anvil, place it as a banner: a "Home" marker appears for
   everyone; break the banner: it disappears.
5. Die: a `Death HH:mm` waypoint appears in group `deaths`; it has no Edit button.
6. Walk within 8 blocks of a temp waypoint: it is removed within a second.
7. Change a private waypoint's type to PUBLIC and back: no duplicate appears after
   reconnecting.
8. Sleep in a bed (even by day): a "Bed" marker appears for you only, in group `beds`; sleep
   in another bed: it moves; break the bed: it disappears. Charge and use a respawn anchor: a
   "Respawn Anchor" marker replaces the bed one.
9. Go through a nether portal: a "Nether Portal" marker appears for everyone at both ends,
   once per portal (go through it again: nothing new). Break its frame: both markers
   disappear within about 10 seconds once their chunk is loaded.
10. Set `bedWaypointsEnabled=false` (ops tab): bed markers vanish from the clients; set it
    back to true: they return (nothing was deleted).
11. Link a waypoint to its Overworld / Nether counterpart and verify the scenarios of
    [14](14-converted-waypoints.md).
