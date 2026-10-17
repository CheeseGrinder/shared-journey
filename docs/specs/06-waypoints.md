# 06 — Waypoints

## 1. Model (`api.Waypoint`, immutable record)

| Field | Meaning |
|-------|---------|
| `id` (UUID) | Identity. Random for user waypoints; deterministic for banners (§6) and bridged ones. |
| `name` | Display name (≤ 48 chars on the wire). |
| `dimension` | Home dimension (every type is bound to one). |
| `x, y, z` | Block position. |
| `colorRgb` | 24-bit RGB. |
| `source` | `user`, `public`, `banner`, or the modId of a bridged mod. |
| `group` | Group name; blank/null is normalized to `default`. |
| `visible` | Per-waypoint display flag. |
| `type` | `DIMENSION`, `PUBLIC`, `TEMP`. |

Reserved groups: `default`, `deaths`, `public`, `banners`. A group is **editable** (can be
renamed/deleted) iff it is not reserved and holds no non-`user` waypoint.

### 1.1 Types and where they live

| Type | Created by | Persisted | Shared |
|------|-----------|-----------|--------|
| `DIMENSION` (`source=user`) | Player | **Server**, per player (`waypointStorage=SERVER`, default) or client file (`CLIENT`) | Never |
| `TEMP` | Player | Client file | Never; auto-removed on arrival |
| `PUBLIC` (`source=user` in the editor, normalized to `source=public`, group `public`) | Player | Server world file | Everyone |
| Banner (`source=banner`, type `PUBLIC`, group `banners`) | Server (named banner placed) | Server world file | Everyone, read-only |
| Bridged (`source=<modId>`, group=<modId>, type `DIMENSION`) | JourneyMap-API mod | Nowhere (volatile) | Never |

| ID | Requirement |
|----|-------------|
| WP-01 | `visible` of PUBLIC, banner and server-managed DIMENSION waypoints is a **per-client** choice kept in a local `hiddenIds` set; it is never sent to the server nor shared. |
| WP-02 | Group visibility is also local (`hiddenGroups`). A waypoint is shown iff its own flag **and** its group's flag are on (`WaypointStore.isShown`). |
| WP-03 | Server-managed (`serverManagesWaypoints`) applies to **user DIMENSION** waypoints only; `TEMP` stay local; `PUBLIC` always go through the server. |

## 2. Client store (`WaypointStore`)

In-memory source of truth for everything the client knows, regardless of persistence.

| ID | Requirement |
|----|-------------|
| WS-01 | Local file `<cache>/waypoints.json`: `{hiddenGroups, hiddenIds, groups, waypoints[]}`; a bare array is accepted as the legacy format. |
| WS-02 | Only `source=user` waypoints that are **not** server-managed are written. Bridged, public, banner and server-managed ones are volatile (resynchronized each session). Loading skips any entry whose source is not `user`. |
| WS-03 | Loading migrates: unknown/missing type → `DIMENSION` (the former `GLOBAL` type also becomes `DIMENSION`, never `PUBLIC`); groups that exist only through a waypoint are adopted as known groups. |
| WS-04 | `add` routes: user PUBLIC → `PublicWaypointPayload` (the local copy appears with the server echo); server-managed user DIMENSION → `PlayerWaypointPayload`; otherwise post the cancellable `WaypointEvent.Added`, then store locally. Routes are gated on `source=user`: bridged and banner waypoints MUST NEVER be pushed to a Shared Journey channel. |
| WS-05 | `update` handles type transitions: private → PUBLIC (publish, drop the private server copy if any, echo re-adds), PUBLIC → private (server copy removed, group reset to `default`, source `user`), DIMENSION ↔ TEMP (server copy added/removed). Shared fields go through the server; the `visible` flag stays local. |
| WS-06 | Server echoes (`accept*Upsert`) post `WaypointEvent.Updated` (never the cancellable `Added`): the server is authoritative, listeners cannot veto a sync. |
| WS-07 | `remove` posts `WaypointEvent.Removed` and sends the matching remove payload for PUBLIC and server-managed waypoints. |
| WS-08 | `forDimension` serves an immutable per-dimension index rebuilt lazily and invalidated by every mutation (called several times per frame). |
| WS-09 | `removeReachedTemp` runs every 20 ticks: a TEMP waypoint in the player's dimension within `waypoints.tempWaypointRadius` (3-D distance, default 8) is removed. |
| WS-10 | Group management: `createGroup` (trimmed, non-empty, unique), `renameGroup` (re-routes each member through `update` so server-managed ones are pushed; moves hidden state), `deleteGroup` (deletes the group **and all its waypoints**). |
| WS-11 | Session close saves the file, then clears waypoints, hidden sets and groups. |

## 3. Server services

### 3.1 Public waypoints (`PublicWaypointService`)

| ID | Requirement |
|----|-------------|
| PUB-01 | File `waypoints_public.json` (array of `{id,name,dimension,x,y,z,color}`), loaded at start, saved after every change. |
| PUB-02 | `handleUpsert`: name trimmed, empty → rejected, > 48 → truncated; colour masked to 24 bits; at most **1 024** waypoints (an upsert of a *new* one at the cap is ignored with a warning). Accepted upserts are persisted and broadcast to **all** players. |
| PUB-03 | `handleRemove`: unknown id ignored; otherwise persisted and broadcast. |
| PUB-04 | Full list is sent to each player at login (one upsert per waypoint). |
| PUB-05 | **No ownership and no permission check**: any player may edit or delete any public waypoint. |

### 3.2 Private waypoints (`PlayerWaypointService`)

| ID | Requirement |
|----|-------------|
| PRI-01 | One file per player: `waypoints/<uuid>.json` (array of `{id,name,dimension,x,y,z,color,group}`); no in-memory cache, read/written on each edit. |
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
| BCN-03 | Icon: a camera-facing sprite (diamond; death and banner variants) tinted with the colour, anchored 1.2 blocks above the waypoint, always visible through blocks, apparent size constant with distance (clamped to the render distance); sampled with a dedicated render type and shader (sharp bilinear, half-texel UV inset) to avoid a crushed look. |
| BCN-04 | Label (name + distance) above the icon, shown only while the crosshair is within ~12° of the waypoint; boosted up to ×1.5 beyond 32 blocks. The beam, icon and label are drawn in two passes (beams, flush, then text) so the translucent beam never masks the text. |

## 6. Waypoint UI

### 6.1 Management screen (`WaypointListScreen`, key `U`)

- Left panel: "All" pseudo-group plus every group; click selects (gold), checkbox toggles
  the group's visibility, live counters; buttons New / Rename / Delete / Done. Rename and
  Delete are enabled only for editable groups.
- Right panel: waypoints of the selected group, with a final "+ New waypoint" row. Per row:
  colour swatch, name and info, **visibility toggle**, **Edit** (hidden for banner and
  death), **View on map** (enabled in the same dimension), **Teleport** (OP only),
  **Delete** `x` (hidden for banner).
- Modals (`ModalScreen`): group name prompt, delete-group confirmation.

### 6.2 Edit / create form (`WaypointEditScreen`, key `B` = create at the player)

Fields: name (≤ 48), X/Y/Z, group (free text with autocompletion over assignable groups —
a new name creates the group on save; **locked to `public` while the type is PUBLIC**),
colour (hex field, 10-colour palette, random, HSB picker), visibility, type (cycles
DIMENSION → PUBLIC → TEMP). Enter saves.

| ID | Requirement |
|----|-------------|
| UI-WP-01 | A blank name on save falls back to the original name; a non-numeric coordinate falls back to the original value. |
| UI-WP-02 | `assignableGroups` = `default` + editable groups (excludes `deaths`, `public`, `banners`, bridged groups). |
| UI-WP-03 | Default name for a new waypoint: `X:<x> Z:<z>` (temp: `Temp X:… Z:…`); default colour random; default Y = loaded-chunk surface Y if known, else the player's Y. |

## 7. Events (client side, `NeoForge.EVENT_BUS`)

`WaypointEvent.Added` (cancellable, local path only), `Updated`, `Removed`. Waypoints from
the JourneyMap bridge also flow through them.

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
