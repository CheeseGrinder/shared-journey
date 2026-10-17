# 07 — Client experience

Client-only behavior: HUD minimap, fullscreen map, radar, screens, key bindings, chat
integration and the local caches. The client NEVER renders map pixels (INV-1); it composes
what the server sent.

## 1. Key bindings (rebindable, category `key.categories.sharedjourney`)

| Action | Default | Where active |
|--------|---------|--------------|
| Open fullscreen map | `M` | In game (no screen open); inside the map it **closes** it (toggle) |
| Toggle minimap | `N` | In game |
| Cycle layer | `,` | In game (minimap); inside the map (cycles the map's own layer) |
| Zoom in / out | `=` / `-` | Minimap in game; fullscreen map when open |
| Waypoint manager | `U` | In game and in the map |
| Create waypoint | `B` | In game: at the player's position; in the map: at the cursor |
| Fixed, in the map only | arrows, `C`, `F`, keypad `+`/`-` | Pan 16 blocks / share cursor position in chat / centre on player / zoom |

Shortcuts are ignored while a text field has focus. The chat key opens the chat **over** the
map (the map keeps rendering and reopens when the chat closes).

## 2. HUD minimap (`MinimapRenderer`)

Registered as a GUI layer above the debug overlay. Hidden when: no player, GUI hidden
(F1), the vanilla debug screen is shown, `minimap.enabled = false`, or toggled off with `N`.

### 2.1 Geometry

| ID | Requirement |
|----|-------------|
| MM-01 | Size `minimap.size` (64–320, default 128) px square or circle (`minimap.shape`), anchored at `minimap.corner` (default TOP_RIGHT) with a 6 px margin. Top-anchored: 11 px reserved above for the time label. |
| MM-02 | Background colour `#36393F` (unreceived areas); square gets a 1 px dark frame; circle gets a feathered border and a depth mask clipping the corners. |
| MM-03 | The centre is the player's **interpolated** position (partial tick), so movement is smooth rather than 20 Hz. |
| MM-04 | While one block spans ≥ 1 physical pixel (`zoom × guiScale ≥ 1`), the anchor and icon positions are snapped to whole **physical** pixels (stops shimmering when walking); below that scale they glide sub-pixel. |
| MM-05 | `rotateWithPlayer`: the content rotates around the centre so the player faces up and the arrow stays fixed; otherwise north is up and the arrow follows the yaw. Cardinal letters (N/E/S/W badges) sit 8 px inside the edge and follow the rotation. |
| MM-06 | Region quads are drawn for all regions covering the window (a rotating square overshoots by √2); missing regions are loaded lazily and asynchronously from the disk cache ([03 ASM-05](03-sync-protocol.md)). |

### 2.2 Zoom

| ID | Requirement |
|----|-------------|
| MM-10 | Scale is screen px per block, range **0.25–4.0**, starting at `minimap.zoomDefault` (default 1.0, rounded to an integer when ≥ 1). |
| MM-11 | Steps: ×1.25 below 1.0 (capped at 1.0 when going up); **integer** steps 1 → 2 → 3 → 4 above 1.0. Integer ratios are mandatory when magnified (fractional ratios flip texel rows under NEAREST sampling). |

### 2.3 Layer selection

| ID | Requirement |
|----|-------------|
| MM-20 | The displayed layer is `defaultLayer` until changed. In **auto mode** (`map.autoLayer`, default on): CAVE if the player is underground and `map.showCave` is on and CAVE is allowed; else NIGHT between world time 13 000 and 23 000 if allowed; else DAY if allowed (or the allowed list is empty); else the first allowed layer. |
| MM-21 | The layer is always clamped to the layers the server announced for the current dimension. |
| MM-22 | Cycle (`,`): Auto → layer 1 → … → layer N → Auto, over the allowed list. A manual pick suspends auto mode. Explicit changes post `MapLayerChangedEvent` (`isAutomatic() == false`). Cycling back to Auto adopts the wanted layer at once (no delay). |
| MM-23 | CAVE band follows the player's own band when it is configured, else the last band index (clamped). |
| MM-24 | **Auto-mode hysteresis.** The auto rule of MM-20 is re-evaluated once per client tick (`MinimapRenderer.tickAutoLayer`) but only *commits* a different layer after the wanted layer has held steadily for **20 ticks (1 s)**; if the wish changes back or to a third layer in the meantime, the count restarts. This prevents CAVE/DAY flapping under overhangs and at cave mouths. Exceptions, adopted immediately: the first evaluation after login/logout, a committed layer the server no longer allows (e.g. dimension change), and the return to Auto from the manual cycle. |
| MM-25 | An automatic switch posts `MapLayerChangedEvent(layer, minimap = true, automatic = true)` ([09](09-public-api.md)). The initial pick after login and the return to Auto post no automatic event (the cycle posts its own explicit one). |

### 2.3.1 Smooth layer changes (minimap and fullscreen)

Implemented by `LayerTransition`, one instance per view (the minimap and the fullscreen map
each track their own displayed layer and cave band).

| ID | Requirement |
|----|-------------|
| LT-01 | Whenever the drawn `(layer, band)` changes — explicit pick, automatic switch or cave band change — the previous pair is remembered for **1.5 s**. During that window a region of the new layer that is not available yet is drawn from the *same region of the previous layer* if that texture is in RAM (never triggers a load), instead of the grey background. A region that becomes available replaces the stand-in immediately. After the window an unavailable region is plain background, so a region with nothing on the new layer does not show the old layer for longer than that. |
| LT-02 | The first `(layer, band)` a view draws is a baseline: it starts no transition and no label. |
| LT-03 | **Layer name label.** When the *layer* changes (not a band-only change) its translated name (`sharedjourney.layer.<id>`, raw id for mod layers without a lang key) is shown for **1.1 s**: fully opaque for 0.7 s, then a linear fade over 0.4 s. Minimap: on a translucent pill over the lower half of the map (0.75 scale); fullscreen: centred at y = 72, under the regen progress bar. |

### 2.4 Content, bottom to top

1. Region tiles (+ regen veil when a regen runs).
2. Chunk grid (`map.showGrid`, only at zoom ≥ 1.0) in the content pose, 1 screen-px lines.
3. Entity radar ([§4](#4-entity-radar)).
4. Bridged plugin overlays (Create rails/trains, RNS deposits) via the JourneyMap fullscreen
   bridge, not on the CAVE layer, inside the scissor.
5. `MapRenderEvent` overlays (public API), pose with `(0,0)` at the map's top-left.
6. Declarative `MapMarker`s (API), then waypoints (constant screen size, **pinned to the
   border** when out of view: radial on a circle, clamped on a square), then other players'
   skin heads (8 px, white border, pinned like waypoints), then the player arrow.
7. Labels on translucent pills at 0.75 scale: time `HH:MM <period>` (above), biome name
   and coordinates `x, y, z` (below; stacked above the map when bottom-anchored). Coordinates
   require `minimap.showCoordinates`.

The vanilla potion-effect icons layer is translated left by `size + 12` px when the minimap
occupies the top-right corner (`HudLayoutEvents`).

## 3. Fullscreen map (`FullMapScreen`)

Not a pause screen. North is always up (conversions screen↔world and bridged overlays
assume it; the compass toggle only controls the **minimap**).

### 3.1 View and navigation

| ID | Requirement |
|----|-------------|
| FM-01 | Opens centred on the player, on `MinimapRenderer.displayedLayer()`, or on the last fullscreen layer when `map.rememberLayer` is on; clamped to allowed layers. `new FullMapScreen(x, z)` opens centred and selects that block. |
| FM-02 | Drag (left button) pans and cancels train following; wheel zooms around the cursor; arrow keys pan 16 blocks. |
| FM-03 | Zoom is a power of two: scale from `64/2048` to `16384/2048`; the displayed "Zoom" label is `round(scale × 2048)`. Buttons `+`/`−`, keypad `+`/`-` and the zoom keys zoom around the screen centre. |
| FM-04 | Left single click on a block selects it (white outline, at least 6 px so it is visible at low zoom); **double-click** on the same block creates a DIMENSION waypoint via the form; double-click on a waypoint opens its edit form (not for banner/death waypoints). A single click on a waypoint selects nothing. Double-click window 350 ms; waypoint hit radius 6 px. |
| FM-05 | A click on a Create train follows it (smoothed 0.15 of the remaining distance per frame) until a pan, `F`, or close. |
| FM-06 | `F` / "follow" button centres on the player; the locate (compass) button opens X/Z fields; Enter or `→` jumps and selects the block. |
| FM-07 | Regions covering the viewport are requested from the server when missing or stale (throttled, [03 §6](03-sync-protocol.md)); the INFO sidecar of each is requested with it. |
| FM-08 | While a regen runs, painted-but-not-yet-re-rendered chunks are veiled and a progress bar + count is drawn under the top info bar. |
| FM-09 | Cave band slider (150×20, bottom centre) is visible only on the CAVE layer when bands exist; label `y <lo>..<hi>`. |
| FM-10 | The map keeps working while the chat is open (chat drawn over it; incoming chat is redrawn above the map when no chat screen is open). |

### 3.2 Toolbars

- **Top bar** (centered): layer buttons Day, Night, Biome, Topo, Cave (disabled when the
  dimension does not serve them) + one paper-icon button per custom layer; then toggles
  Show cave auto-switch, Hostile mobs, Animals, Pets, Villagers, Rotate minimap (compass),
  Hide me from the map; the "Show keys" legend button; then Chunk grid, Waypoints, Players
  (overlay group); Close at the far right. Toggle buttons write the client config
  immediately (`ClientConfig.SPEC.save()`); "hide from map" also sends the visibility
  payload.
- **Left bar** (vertically centred, never above the addon zone): Locate, Follow player,
  Zoom +, Zoom −, Waypoints (emerald diamond icon), Settings.
- **Addon zones** — visually separate from the mod's own buttons: `TOP_LEFT` cluster at
  `y = 30` (starting after the reserved footprint of bridged widgets, ~64 px each for
  Create's train toggle and the Create: Rock & Stone toggle) and `TOP_RIGHT` marching left
  from Close. Contributed through `FullMapToolbarEvent`, re-posted on every widget rebuild
  (open and resize).
- **Info bars**: top (`player ■ x,z,y ■ biome ■ Zoom: N`, y = 30) and bottom hover bar
  (`block ■ x, z, y ■ biome`, or just `x, z` when nothing is known). Segments from
  `FullMapInfoBarEvent.Top/Hover` are appended with ` ■ `.
- **Legend** ("Show keys", toggle persists for the session): keycap chips for the live key
  bindings, mouse gestures and fixed keys.

### 3.3 Context menu (right click, `ContextMenu`)

Title = clicked block `x, z`. Rows, in order: **Teleport** (OP only, runs `/sj tp`),
**Waypoints ▸** (New waypoint, Temporary waypoint, Public waypoint, Show all, Hide all,
Manage…), **Position in chat**, then rows contributed by `FullMapContextMenuEvent`
(one submenu level, enforced). Esc or an outside click closes it; the press that closes
the menu has its release swallowed so it cannot select the block below.

"Position in chat" (and `C`) pre-fills the chat input with `[x, z] ` — the mod never sends
chat on its own.

### 3.4 Layering (z)

Map tiles (0) → plugin overlays (up to ≈ 150) → `MapRenderEvent` → annotations at **z = 160**
(markers, waypoints, other players, radar) → player arrow → UI chrome at **z = 200** →
legend (300) → context menu (500) → debug overlay.

## 4. Entity radar

| ID | Requirement |
|----|-------------|
| RDR-01 | Active iff `radar.enabled` and the server cap > 0. Radius `min(radar.radius, radarMaxRadius)` blocks (client range 8–128, default 48); vertical window ±32 blocks. |
| RDR-02 | Classification, in priority order: player (never a dot — see RDR-05) > tamed animal (`showPets`, blue) > villager (`showVillagers`, green) > `Enemy` (`showHostile`, red) > any other `Mob` (`showPassive`, gray). Defaults: hostile, pets, villagers on; passive off. |
| RDR-03 | With `radar.mobHeads` on, mobs are flat **head icons** rendered from the entity's own model and texture; if an icon is not ready or cannot be built, the coloured dot (5×5 with black outline) is drawn. |
| RDR-04 | Icons are constant screen size and kept upright (counter-rotated/scaled against the map pose); one batched draw per surface per frame. |
| RDR-05 | Other players are drawn from the server's `PlayerPositionsPayload` as skin heads (`radar.showPlayers`), using the live entity position when the client tracks that player. Hidden players are absent from the payload. |

### 4.1 Mob head icons (`MobHeadIcons`, v3)

- Render-to-texture: each icon is rendered **once** into a 32 px cell of an atlas page from
  a 256² supersampled (×8) framebuffer with real depth testing, mip-generated downscale, and
  an 8-copy silhouette-dilation outline; every later frame costs one textured quad.
- Cache key `(entity type, texture)` (horse coats, cat breeds, angry wolf come free). At
  most one icon is created per 15 ms; meanwhile the coloured dot shows.
- Head resolution is generic (humanoid/headed models, `headParts`, descendant named
  `head`, reflective scan); the icon centres on the large cubes of the front third of the
  head part. Mobs without an identifiable (or with a tiny) head fall back to the whole
  model, fish in profile.
- Model frozen in a neutral pose; GL state (projection, FBO, viewport, **scissor box**, blend,
  depth, lights) is saved and restored around the capture.
- Framing is data-driven (`MobIconTweaks`): JSON files merged field by field, later wins —
  bundled `assets/sharedjourney/sharedjourney/mob_icons/*.json` → any
  `assets/<ns>/sharedjourney/mob_icons/*.json` (mods, resource packs) → user
  `config/sharedjourney/mob_icons/*.json` (re-read by `/sj icons reload`). Sections:
  `global` (`wholeModelScale`, `maxFill`, `minFill`), `models` (by model class name),
  `entities` (by type id, overriding the model). Fields: `scale`, `rotX`, `rotY`,
  `mode` (`auto|head|whole`), `headPart`, `offsetX`, `offsetY`.
- Accepted limits: render layers (sheep wool, armor, profession) are not drawn; bounds are
  cached per entity type; the reference pose is fixed.

## 5. Settings screen (`MapSettingsScreen`, from the map's Settings button)

Client tabs apply **live**; the file is saved once on close. Tabs:

| Tab | Visible | Content |
|-----|---------|---------|
| Minimap | always | enabled, size, corner, shape, rotate, coordinates, default zoom (0.25 notches) |
| Radar | always | enabled, radius (label shows the server cap), mob heads, players, hostile, animals, pets, villagers, hide-from-map (sent immediately) |
| Map | always | default layer, auto layer, cave auto-switch, remember layer, chunk grid, disk cache |
| Waypoints | always | show waypoints, names, temp radius, beacons, beacon min/max distance, death waypoints |
| Addons | iff `create` or `create_rns` loaded | one section per mod: train overlay, deposit overlay |
| Server | OP only (≥ 2) | working copy of the server config; **Apply** button ([05 §3](05-regen-commands-admin.md)): layer checkboxes per dimension (+ defaults), cave band min/max, sync, waypoints, privacy |
| Debug… | OP only, bottom-left button (hidden tab) | "Rendering overlay" toggle (runtime only, never persisted; draws zoom px/block, GUI scale, `z×gui` `[aligned]`/`[fractional]`, layer/band/dim, player region, fps on minimap and map) |

Sync rate is stored in ticks and shown in seconds (`ticks / 20`). Row tooltips reuse the
`configuration.*.tooltip` keys of the NeoForge config screen. The NeoForge config screen
(`ConfigurationScreen`) remains available from the mod list.

## 6. Chat coordinates

Incoming chat containing `[x, z]` (optional space, signed integers up to 8 digits) is
re-styled as an aqua underlined link running `/sj goto x z`, with a hover tooltip. Non
matching text keeps its style; nested component structure is flattened.

## 7. Local caches and stores

| Store | Content | Lifetime |
|-------|---------|----------|
| `ClientMapCache` (RAM) | Region textures, pending assemblies, in-flight disk loads, hover LRU (8), server announcements, hidden players, player positions, regen state/masks/progress, last ops snapshot | Per session; cleared on login/logout |
| `DiskCache` | Region files + `index.json` | Persistent per server/world ([01 §5.2](01-regions-layers-storage.md)) |
| `WaypointStore` | All known waypoints | RAM; local file for locally-stored ones ([06](06-waypoints.md)) |
| `MapMarkerStore` | API marker providers | Process |
| `DisplayNames` | Localized biome/block names, per-minute time text | Dropped on language change |

Defaults announced before the first server message: `radarMaxRadius = 64`,
`deathWaypointsEnabled = true`, `serverManagesWaypoints = true` (so a client connecting
before the handshake never writes server-managed waypoints to its local file).

## Acceptance checks

1. Walk with the minimap at zoom 1 and 4: no shimmering of icons, no 20 Hz stepping.
2. Rotate-with-player: arrow fixed, N/E/S/W letters orbit, labels stay horizontal.
3. Cycle layers with `,` on the minimap: Auto → each allowed layer → Auto; the label of the
   toolbar highlights match.
3b. Stand at a cave mouth / under an overhang so the underground rule flips repeatedly: the
   minimap does not flap; it switches only after ~1 s of a steady state, and a listener of
   `MapLayerChangedEvent` sees `isAutomatic() == true` for that switch.
3c. Switch layers on the minimap and in the fullscreen map: no grey flash, the old layer
   stays until the new texture is ready, and the layer name fades out in about a second.
4. In the Nether (only CAVE configured): the layer buttons for the others are disabled.
5. Right-click a block: menu shows the clicked coords; "Position in chat" opens the chat
   pre-filled `[x, z] `; the received message becomes a link that opens the map there.
6. Open the map during a regen: purple veil on painted, unfinished chunks and a progress bar.
7. As a non-OP the settings screen has no Server tab and no Debug button.
8. Resize the window with the map open: addon buttons are rebuilt in their zones.
