# 09 — Public API

Package `fr.cheesegrinder.sharedjourney.api` (and `.event`, `.client`, `.client.event`). The
`api` package depends on nothing else in the mod. Facades that need engine/store state use
static `Hooks` fields wired by the entry points; calling them before wiring returns the
"unknown" value (false / empty / −1) or drops the action.

| Surface | Side | Entry points |
|---------|------|--------------|
| Waypoints | Client | `WaypointApi`, `WaypointEvent` |
| Map read/actions | Server | `MapApi` |
| Custom layers | Server (mod bus) | `LayerRegisterEvent`, `ChunkLayerRenderer`, `MapLayer` |
| Block colours | Server (mod bus) | `BlockColorRegisterEvent` |
| Map overlays | Client | `MapRenderEvent`, `MapView`, `FullMapScreenEvent`, `MapLayerChangedEvent` |
| Declarative markers | Client | `MapMarkerApi`, `MapMarkerProvider`, `MapMarker` |
| Fullscreen UI extension | Client | `FullMapToolbarEvent`, `FullMapContextMenuEvent`, `FullMapInfoBarEvent` |
| Constants | Both | `SharedJourneyConstants.MOD_ID` |

## 1. `WaypointApi` (client)

All calls operate on the client's `WaypointStore`.

| Method | Contract |
|--------|----------|
| `all()`, `forDimension(dim)`, `get(id)` | Read (copies / immutable lists); `get` returns null if unknown |
| `add(wp)` | Routes like the UI (PUBLIC → server). Returns false if a listener cancelled `WaypointEvent.Added` (local path only) |
| `update(wp)`, `remove(id)` | By id |
| `isShown(wp)` | Own flag AND group flag |
| `groups()` | Sorted, `default` first |
| `createGroup(name)` | false on blank or taken |
| `renameGroup(from, to)` | false if not editable or target invalid/taken; moves waypoints and hidden state |
| `deleteGroup(group)` | Deletes the group **and its waypoints**; false if not editable |
| `isGroupEditable(group)` | false for `default`, `deaths`, `public`, `banners` and bridged-mod groups |
| `isGroupVisible`, `setGroupVisible` | Local per-client choice |

`WaypointEvent.{Added (cancellable), Updated, Removed}` are posted on `NeoForge.EVENT_BUS`
(client side) whatever the entry point (facade, store, bridge). Server echoes post
`Updated`, never `Added`.

Deliberately not exposed: session open/close, temp cleanup, per-source removal.

## 2. `MapApi` (server)

| Method | Contract |
|--------|----------|
| `isChunkRendered(level, cx, cz)` | True when the first active layer of the dimension has a painted pixel at the chunk's origin pixel (the dimension having no active layer counts as true); false while the engine is down |
| `rerenderChunk(level, cx, cz)` | Enqueues all layers as if a block changed; async and throttled; dropped while the engine is down |
| `regionVersion(dim, layerId, caveBand, rx, rz)` | Version (grows on every rewrite) or −1 (unknown layer id, never rendered, engine down); `caveBand` ignored except for `"cave"` |
| `isHiddenFromMap(uuid)` | The player's hide-from-map preference. Player positions are intentionally not exposed |

Layer ids are strings: `day`, `night`, `topo`, `biome`, `cave` or a custom id (looked up
case-insensitively against the registry; unknown ⇒ −1).

## 3. Custom layers (mod bus, posted on `ServerStartedEvent`)

```java
public void onLayerRegister(LayerRegisterEvent e) {
    e.register("mymod_ores", (level, chunk, caveBand) -> pixels256OrNull);
}
```

| ID | Contract |
|----|----------|
| API-L-01 | `ChunkLayerRenderer.render(ServerLevel, ChunkAccess, int caveBand)` returns **256 ARGB ints** (`x + z*16`). It runs on a **worker thread**: read-only chunk access only. |
| API-L-02 | Return `null` to skip the chunk; a wrong size or an exception logs a warning and skips the chunk. |
| API-L-03 | Id: lowercase `[a-z0-9_]+`, prefixed with the mod id; must not collide with a built-in id. |
| API-L-04 | A custom layer rides the full pipeline: render, storage `<dim>/<id>/`, versioning, delta sync, handshake, disk cache, fullscreen button (paper icon), keyboard cycle. |
| API-L-05 | Not provided: per-dimension config / ops-UI toggle (active in every dimension), vertical bands, a dedicated icon. Name = lang key `sharedjourney.layer.<id>` or the raw id. `/sj admin layer` and the ops layer editor handle built-ins only. |
| API-L-06 | Clients without the registering mod still display the layer (lenient id decoding). |

## 4. Block colours (mod bus, posted before the engine renders anything)

`BlockColorRegisterEvent.register(Block, 0xRRGGBB)`. Sits between the config overrides
(admin wins) and the bundled palette. Applies to chunks rendered from then on; already
rendered regions are not repainted (use `/sj admin regen`).

## 5. Screen render API (client)

| Type | Contract |
|------|----------|
| `MapView` | Read-only view of a map being drawn: `isMinimap`, `dimension`, `currentLayer`, `caveBand`, `centerX/Z` (blocks), `zoomScale` (GUI px per block), `viewWidth/Height`, and conversions `screenX/screenY/worldX/worldZ`. Screen coordinates are GUI pixels local to the view (origin top-left). |
| `MapRenderEvent` | Once per frame per visible map (minimap and fullscreen), after the tiles and bridged overlays, below player markers and widgets; draw with `getGraphics()`; clipped to the map area. On a rotating minimap the pose is rotated, so world-anchored draws land correctly but rotate with the map. |
| `FullMapScreenEvent.Opened(view)` / `.Closed` | Visibility semantics: also fired when the map is temporarily replaced (waypoint modal, chat) and shown again. |
| `MapLayerChangedEvent(layer, minimap)` | Explicit selections only (toolbar, keyboard), never automatic day/night/cave switches. |

## 6. Declarative markers (client)

```java
MapMarkerApi.register(ResourceLocation.fromNamespaceAndPath("mymod", "pois"),
    view -> view.dimension().equals(MY_DIM)
        ? List.of(MapMarker.create(x, z, 0xFF8800, MapMarker.Shape.DIAMOND).withClampToEdge(true))
        : List.of());
```

| ID | Contract |
|----|----------|
| API-M-01 | `MapMarker(x, z, colorRgb, shape ∈ {DIAMOND, DOT}, clampToEdge)`; position in world blocks (add 0.5 to centre on a block); colour 24-bit. |
| API-M-02 | The provider is polled once per visible map per frame with that map's `MapView`: it must be cheap, never return null, and does its own dimension/zoom/layer filtering. |
| API-M-03 | `clampToEdge` pins an out-of-view marker to the border (radial on a round minimap, rectangular otherwise, screen edge on the fullscreen map); otherwise it is culled. |
| API-M-04 | Z-order: above tiles and grid, below Shared Journey's own waypoints, player heads and arrow; order relative to `MapRenderEvent` overlays is unspecified. |
| API-M-05 | A provider that throws or returns null is logged once (per registration id) and skipped for the frame. Re-registering an id replaces the provider and clears its "reported" flag. |
| API-M-06 | Not exposed: textures, labels, click handling, per-marker z-order, in-world beacons. |

## 7. Fullscreen UI extension points (client, `NeoForge.EVENT_BUS`)

| Event | When | Contribution |
|-------|------|--------------|
| `FullMapToolbarEvent` | Every widget rebuild (open **and** resize) | `ToolbarButton.action/toggle(slot, ItemStack icon, tooltip, onPress[, selected])`, slots `TOP_LEFT` (addon cluster) and `TOP_RIGHT` (marching left from Close). Layout, drawing and click routing belong to the mod. Toggle outlines refresh after any toolbar press and on rebuild. |
| `FullMapContextMenuEvent(view, blockX, blockZ)` | When the right-click menu is built | `Entry.action(label, Runnable)`, `Entry.submenu(label, children)`; appended below the internal rows; **one submenu level**, nested submenus throw `IllegalArgumentException`. |
| `FullMapInfoBarEvent.Top` / `.Hover(blockX, blockZ)` | **Every frame** | `addSegment(Component)`; joined with ` ■ ` after the internal segments. Listeners must be cheap and allocation-light. |

Not exposed: absolute positioning, custom widgets, texture icons (ItemStack only), keyboard
shortcuts, overflow handling, removal/reordering of internal rows. Buttons contributed to
these zones are visually separated from the mod's own bars by design.

## 8. Stability

- Public API additions are tracked in `../../TODO.md`; the toolbar API currently has no internal
  consumer (validated by an example mod only).
- Wire-format changes bump the network protocol version ([03 NET-01](03-sync-protocol.md));
  API signatures have no version number yet.

## Acceptance checks

1. A test mod registers a custom layer: it shows up in the layer toolbar and cycle, its
   regions land in `<dim>/<id>/`, and a second client without that mod still displays it.
2. `MapApi.regionVersion` increases after a block change nearby, and `rerenderChunk` makes
   an otherwise unchanged chunk re-evaluate without a version bump if nothing differs.
3. A provider throwing on every call produces exactly one warning.
4. A toolbar listener adding two `TOP_LEFT` buttons places them right of Create's reserved
   toggle footprint when Create is loaded, in contribution order.
