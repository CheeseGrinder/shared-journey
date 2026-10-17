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
| MM-02 | Background colour `#36393F` (unreceived areas); the circle's background disc reaches 0.5 px past the map edge, and a depth mask clips the content's corners to a disc. The border is a texture frame (MM-07 to MM-09 and MM-12), drawn on top of the content. |
| MM-03 | The centre is the player's **interpolated** position (partial tick), so movement is smooth rather than 20 Hz. |
| MM-04 | While one block spans ≥ 1 physical pixel (`zoom × guiScale ≥ 1`), the anchor and icon positions are snapped to whole **physical** pixels (stops shimmering when walking); below that scale they glide sub-pixel. |
| MM-05 | `rotateWithPlayer`: the content rotates around the centre so the player faces up and the arrow stays fixed; otherwise north is up and the arrow follows the yaw. Cardinal letters (N/E/S/W badges) sit 8 px inside the edge and follow the rotation. |
| MM-06 | Region quads are drawn for all regions covering the window (a rotating square overshoots by √2); missing regions are loaded lazily and asynchronously from the disk cache ([03 ASM-05](03-sync-protocol.md)). The minimap always draws full-resolution textures ([§3.5](#35-level-of-detail-when-zoomed-out-lodchain-clientmapcache)). |

### 2.1.1 Frame (`MinimapFrame`)

Pre-drawn textures replace the former procedural border. Textures live in
`assets/sharedjourney/textures/gui/minimap_frame/<theme>_round.png` and `<theme>_square.png`,
generated offline by `tools/MinimapFrameGenerator.java` (procedural description baked to PNG,
supersampled; rerun after changing a theme).

| ID | Requirement |
|----|-------------|
| MM-07 | **Theme.** `minimap.frameTheme` (`CLASSIC` default, `STONE`, `WOOD`, `THIN`) selects the texture pair; it applies live from the settings screen (Minimap tab, "Frame theme"). `CLASSIC` reproduces the former look: round = 1 px black liner on the map side + gray band, feathered over 0.75 px; square = 1 px `#202020` outline. |
| MM-08 | **Band geometry.** Each theme has a band `thickness` and an `inset` (GUI px): the band overlaps the map by `inset` px (hiding the jagged cut of the circular depth mask and the background disc) and extends `thickness − inset` px outside it. Round: classic 3.25/1.0, stone and wood 4/1, thin 2.25/1. Square (integer px so edges stay pixel aligned): classic 1/0, stone and wood 4/1, thin 2/1. The frame is drawn after the content and the depth reset, before waypoints, markers, the player arrow and labels, and does **not** rotate with `rotateWithPlayer`. |
| MM-09 | **Round frame = polar strip.** The `*_round.png` texture is 512 × 32 texels: X = angle (one seamless turn), Y = radius from the inner edge (first row) to the outer edge (last row); the first and last rows are fully transparent. It is wrapped on a 64-segment ring mesh (the same polygon as the clipping mask) at the exact on-screen radii, sampled with LINEAR filtering, so the circle is antialiased by the sampler at any `minimap.size` and GUI scale rather than being a bitmap resampled. Stone and wood bevels are lit from the top-left (the lighting is baked per angle). |
| MM-12 | **Square frame = 9-slice.** The `*_square.png` texture is a 9-slice without centre authored at 4 texels per GUI px: corner size `thickness × 4` texels, stretched middle of 8 texels per edge (so edges are uniform along their length; corners are mitered). It is drawn as 8 quads at whole GUI px positions with LINEAR filtering. |

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
5. `MapRenderEvent` overlays (public API), pose with `(0,0)` at the map's top-left; this is
   where the force-loaded and slime chunk overlays ([13](13-chunk-overlays.md)) draw. The
   texture frame ([§2.1.1](#211-frame-minimapframe)) is drawn right after, over the map edge
   and under everything below.
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
| FM-04 | Left single click on a block selects it (white outline, at least 6 px so it is visible at low zoom); **double-click** on the same block creates a DIMENSION waypoint via the form; double-click on a waypoint opens its edit form (not for banner, automatic or death waypoints). A single click on a waypoint selects nothing. Double-click window 350 ms; waypoint hit radius 6 px. |
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
  (open and resize). The built-in chunk overlays use this zone too: a force-loaded chunks
  toggle (lodestone) and, in the overworld, a slime chunks toggle (slime ball), each only if
  the server allows it ([13 OVL-16](13-chunk-overlays.md)).
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

### 3.5 Level of detail when zoomed out (`LodChain`, `ClientMapCache`)

Zoomed far out, a 512×512 region covers a few dozen screen pixels; uploading and keeping the
full image of hundreds of regions only to shrink it with nearest-neighbour sampling is
wasteful. The fullscreen map therefore draws **reduced versions** of each region, built
locally from the region PNG already in the disk cache. Nothing changes on the wire (the server
and the sync protocol know nothing about it), and the hover sidecars, overlays and grid are
independent of it.

| ID | Requirement |
|----|-------------|
| LOD-01 | **Levels.** Level 0 is the full 512×512 region image. Level *n* (1 to 4) is level *n − 1* halved with a 2×2 box filter: 256, 128, 64 and 32 texels wide. Level 4 holds one texel per chunk. Every level texture covers the whole region and is painted over 512 blocks (`Region.draw`), whatever its size. |
| LOD-02 | **Selection (fullscreen map only).** Per frame, with `P = 512 × zoom × guiScale` (physical pixels one region spans on screen), the level is the coarsest *n* ≤ 4 such that `512 >> n ≥ P` (tolerance 0.001): at least one texel per screen pixel, so no level is ever magnified. `P ≥ 512` gives level 0. At GUI scale 2 the zoom labels 1 024, 512, 256, 128 and 64 map to levels 0, 1, 2, 3 and 4 (a higher GUI scale means more physical pixels per region and lands on a finer level, a lower one on a coarser level, capped at 4). The zoom is a power of two, so the level changes only on a zoom step; there is no hysteresis. |
| LOD-03 | **Availability.** Reduced levels exist only while `map.lodEnabled` is on **and** the disk cache is persisting (`map.diskCache` on, session open), because the PNG on disk is their source. Otherwise every request is level 0 and the map behaves as before. The minimap always draws level 0 (it shows a handful of regions). The INFO / INFO_CAVE sidecars are never reduced. |
| LOD-04 | **Alpha-aware averaging.** A texel's colour is the mean of the four source colours weighted by their alpha, so unexplored (transparent) pixels never darken the colours next to them. Its alpha is the mean alpha, never rounded below 1 while any source pixel is visible. A fully transparent texel touching a visible one receives the mean colour of its visible neighbours (alpha stays 0), so bilinear sampling fades an explored edge into nothing instead of into black. |
| LOD-05 | **Content mask.** The per-chunk content mask ([03 ASM-04](03-sync-protocol.md)) of a reduced texture is computed from its own alpha and equals the mask of the full image (LOD-04 keeps alpha ≥ 1 wherever a source pixel is visible). `isChunkPainted` (fullscreen CAVE hover) accepts any resident level and, if none, loads the coarsest. |
| LOD-06 | **Sampling.** Level 0 keeps `NEAREST` (pixel exact when magnified). Levels 1–4 use `LINEAR` with `CLAMP_TO_EDGE`: they are drawn at one to two texels per screen pixel, where bilinear averaging is the right reconstruction. |
| LOD-07 | **Stand-ins.** While the wanted level of a region loads, the nearest resident level of the *same* region is drawn (a finer one first, otherwise a coarser, blurrier one), so zooming never blanks tiles. Only if none exists does the layer-transition stand-in apply ([LT-01](#231-smooth-layer-changes-minimap-and-fullscreen)), at the closest level of the previous layer that is in RAM. |
| LOD-08 | **Production.** Runs on the two region loader threads, never on the render thread. A level ≥ 2 is read from the region's sidecar `region_X_Z.lod` ([01 CLI-05](01-regions-layers-storage.md)) when it carries the version the index announces; otherwise (no sidecar, other version, corrupt) the PNG is decoded, the whole chain is rebuilt (≈ 5 ms), and the sidecar is rewritten. Level 1 is not stored (it would nearly double the file) and is always rebuilt from the PNG; it is only drawn while few regions are visible. |
| LOD-09 | **Network pushes.** A completed region is first persisted (disk cache on). Only the levels already resident, or loading, are then re-decoded, on a loader thread and from the bytes in hand; the previous texture stays drawn until the new one is uploaded. A region no view displays costs no texture and no decode: it is loaded from disk the first time it is drawn. With the disk cache off, the region is decoded and uploaded as level 0 at once, as before. |
| LOD-10 | **Bounded cost.** Per frame, the render thread uploads decoded textures up to a budget of 4 × 512² texels (four full images, or sixteen level-1, or a thousand level-4 textures) and at most 32 uploads; at most 8 loads are in flight. The resident textures are capped by `map.textureBudgetMb` (default 256): a texture counts twice its RGBA size (GPU copy plus the pixel copy `DynamicTexture` keeps). Above the cap, the least recently drawn textures (any level, all layers and bands) are released until memory is back under 90 % of the cap; a texture drawn in the last second is never released, so the visible set cannot thrash, and neither is a texture of a region absent from the disk cache (it could not be reloaded). |
| LOD-11 | **Invalidation.** A region PNG with a newer version refreshes the resident levels (LOD-09); its sidecar is detected stale by its version header and rewritten on the next reduced load. `DiskCache.purge` deletes the sidecars with the PNGs and `evict` drops every level. |

Bounded working set: with at least one texel per pixel and at most two, the texture memory of
the visible regions is bounded by the screen size (at most about `W × H × 16` bytes of GPU
texture, ≈ 33 MiB at 1920 × 1080 for a fully explored view) whatever the zoom, where full
images grow with the number of visible regions.

The debug overlay ([§5](#5-settings-screen-mapsettingsscreen-from-the-maps-settings-button),
"Debug…") shows, on the fullscreen map, the current level and, on both maps, the number and
memory of resident textures per level; `/sj cache` prints the same totals in chat.

## 4. Entity radar

| ID | Requirement |
|----|-------------|
| RDR-01 | Active iff `radar.enabled` and the server cap > 0. Radius `min(radar.radius, radarMaxRadius)` blocks (client range 8–128, default 48); vertical window ±32 blocks. |
| RDR-02 | Classification is data-driven ([12](12-radar-rules.md)): ordered JSON rules, first match wins, and the toggles gate the rules that name their category. The bundled rules give, in priority order: player (never a dot — see RDR-05) > tamed animal (`showPets`, blue) > villager (`showVillagers`, green) > `Enemy` (`showHostile`, red) > any other `Mob` (`showPassive`, gray). Defaults: hostile, pets, villagers on; passive off. |
| RDR-03 | With `radar.mobHeads` on, mobs are flat **head icons** rendered from the entity's own model and texture; if an icon is not ready or cannot be built, the coloured dot (5×5 with black outline) is drawn. |
| RDR-04 | Icons are constant screen size and kept upright (counter-rotated/scaled against the map pose); one batched draw per surface per frame. |
| RDR-05 | Other players are drawn from the server's `PlayerPositionsPayload` as skin heads (`radar.showPlayers`), using the live entity position when the client tracks that player. Hidden players are absent from the payload. |
| RDR-06 | **Height fade** (`radar.heightFade`, default on), minimap and fullscreen map. For each radar entity, `dy = entity.getY() - player.getY()` (feet to feet). Opacity is 1 while `\|dy\| ≤ heightFadeStart` (default 6), then falls linearly to a floor of **0.25** at `\|dy\| ≥ heightFadeFull` (default 24; treated as start + 1 when not above the start). The floor keeps faded entities faintly visible. Fading applies to both the head icon and the dot fallback, and is a pure multiplier on opacity (no change to what is drawn, to filters or to radius). With the feature off, opacity is always 1 and drawing is identical to before. |
| RDR-07 | While `\|dy\| > heightFadeStart`, an optional **up / down arrow** (`heightFadeArrow`, default on; up when the entity is above the player) and an optional **signed Y offset label** (`heightFadeLabel`, default off; `+12`, `-7`, rounded to whole blocks, half-scale text) are drawn to the right of the icon, white with a black outline/shadow. They are drawn after the icon batch is flushed (so icons never cover them), at constant screen size and upright (counter-rotated/scaled against the minimap pose). Their opacity follows the entity's but never drops under 0.7. |
| RDR-08 | The vertical query window stays ±32 blocks around the player's bounding box (RDR-01): entities beyond it are not drawn at all, whatever the fade distances. The config is read once per frame (snapshot), not per entity. |

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
| Minimap | always | enabled, size, corner, shape, frame theme (cycle: Classic, Stone, Wood, Thin), rotate, coordinates, default zoom (0.25 notches) |
| Radar | always | enabled, radius (label shows the server cap), mob heads, height fade (toggle, start and full-fade sliders, offset label, arrow; RDR-06/07), players, hostile, animals, pets, villagers, hide-from-map (sent immediately) |
| Map | always | default layer, auto layer, cave auto-switch, remember layer, chunk grid, disk cache, reduced detail when zoomed out (`map.lodEnabled`, LOD-03); then a "Chunk overlays" section: force-loaded chunks and slime chunks toggles, each replaced by a "disabled by the server" notice when the server does not allow it ([13 OVL-18](13-chunk-overlays.md)) |
| Waypoints | always | show waypoints, names, temp radius, beacons, beacon min/max distance, death waypoints |
| Addons | iff `create` or `create_rns` loaded | one section per mod: train overlay, deposit overlay |
| Server | OP only (≥ 2) | working copy of the server config; **Apply** button ([05 §3](05-regen-commands-admin.md)): layer checkboxes per dimension (+ defaults), cave band min/max, sync, waypoints (death, storage, bed / nether portal / respawn anchor automatic waypoints), privacy |
| Debug… | OP only, bottom-left button (hidden tab) | "Rendering overlay" toggle (runtime only, never persisted; draws zoom px/block, GUI scale, `z×gui` `[aligned]`/`[fractional]`, layer/band/dim, player region, fps on minimap and map; the fullscreen map adds its level of detail, both add the resident texture count, memory and count per level) |

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
| `ClientMapCache` (RAM) | Region textures at every level of detail (LRU, capped by `map.textureBudgetMb`, [§3.5](#35-level-of-detail-when-zoomed-out-lodchain-clientmapcache)), pending assemblies, in-flight disk loads, hover LRU (8), server announcements, hidden players, player positions, regen state/masks/progress, last ops snapshot | Per session; cleared on login/logout |
| `ChunkOverlayStore` (RAM) | Allowed overlays announced by the server, chunk bitmaps per region (LRU 4 096), pending requests ([13](13-chunk-overlays.md)) | Per session; data cleared on login, everything on logout |
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
8b. Chunk overlays: see the acceptance checks of [13](13-chunk-overlays.md) (toggles in the
   addon zone, settings rows, hover segment).
9. Settings, Minimap tab: cycle "Frame theme" through Classic / Stone / Wood / Thin with both
   shapes and at GUI scales 1 to 4: the frame follows live, circle edges stay smooth (no
   stair-stepping), square edges stay pixel crisp, nothing overlaps the time label above a
   top-anchored map, and Classic looks like the former border (black liner, gray band /
   dark 1 px outline).
10. Level of detail ([§3.5](#35-level-of-detail-when-zoomed-out-lodchain-clientmapcache)): on
   a cached world of at least a few dozen regions, open the fullscreen map with the debug
   overlay on and zoom out step by step: the `lod` line goes L0 → L4, the resident texture
   count stays roughly the number of explored regions on screen while the memory line drops
   (≈ 4 KiB ×2 per region at L4), the map is never blank during a step, and unexplored areas
   show no dark fringe around explored ones. Zoom back in: full-resolution pixels return.
10b. Same map with `map.lodEnabled = false` (or `map.diskCache = false`): the `lod` line stays
   at L0 and the map looks and loads as it did before the feature.
10c. Walk around so the server pushes new region versions while the map is zoomed out and
   while it is zoomed in: tiles update in place with no flash to background, and a region
   never displayed produces no texture (`/sj cache` texture count does not jump).
10d. Lower `map.textureBudgetMb` to 64, pan across a large explored area at full zoom:
   the texture memory stays under the cap, tiles that scroll back into view reload from disk.
