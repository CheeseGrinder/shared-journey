# 10 — Compatibility bridges

All bridges are **reflection-based**: Shared Journey has no compile-time dependency on
JourneyMap, Create or Create: Rock & Stone. A missing or incompatible target disables the
bridge cleanly (a failure is logged once and the feature turns itself off).

## 1. JourneyMap plugin bridge (`JourneyMapBridge`, client)

Goal: mods that integrate with the JourneyMap client API (Waystones, Create add-ons,
resource-deposit mods…) work with Shared Journey without modification.

### 1.1 Activation

| ID | Requirement |
|----|-------------|
| JM-01 | Initialized once on `FMLLoadCompleteEvent` (enqueued), after **all** mods are constructed, so the annotation scan sees third-party plugins. |
| JM-02 | If the **real** JourneyMap is present the bridge disables itself. Detection checks for JourneyMap's main classes, not only the modId, because `jmshim` also declares `journeymap`. |
| JM-03 | Otherwise it scans every mod's annotation data for `@JourneyMapPlugin` (API v2) then `@ClientPlugin` (v1), instantiates each plugin and calls `initialize` with a **dynamic proxy** of `IClientAPI` (`java.lang.reflect.Proxy`). The JourneyMap API classes must be on the runtime classpath for plugin classes to load. |
| JM-04 | The API v2 static `WaypointFactory.INSTANCE` is installed (when null) with an in-memory implementation; without it `createClientWaypoint` throws a NullPointerException. Bridged waypoint objects are live proxies: every `set*` on an added waypoint is propagated to the store. |
| JM-05 | On login/logout the bridge publishes `MappingEvent` `MAPPING_STARTED` / `MAPPING_STOPPED` (some plugins, e.g. Waystones, wait for it before creating anything). |

### 1.2 Waypoint translation

| ID | Requirement |
|----|-------------|
| JM-10 | `addWaypoint`, `show` (waypoint), `removeWaypoint`, `remove`, `removeAll`, `exists`, `getWaypoint(s)`, `getAllWaypoints`, `playerAccepts` (always true), `getModId` are implemented. |
| JM-11 | A bridged waypoint becomes a `Waypoint` with `source = group = <plugin modId>`, type `DIMENSION`, visible; its id is `UUID.nameUUIDFromBytes(modId + "|" + guid)` (stable). Existing ids are updated, new ones added through `WaypointStore.add` (cancellable `WaypointEvent.Added`). |
| JM-12 | Default colour (mods like Waystones never set one) is derived from the waypoint identity — hue from the hash of `modId|dimension|pos`, saturation 0.85, value 1.0 — so it is stable across sessions and players. |
| JM-13 | Bridged waypoints are volatile (not persisted, not pushed to Shared Journey server channels), read-only in the UI sense of group editing, and live in a group named after the mod. They cannot be linked to the Overworld / Nether counterpart, and Shared Journey's derived converted counterparts are never exposed to bridged mods ([14 CONV-14](14-converted-waypoints.md)). |
| JM-14 | `subscribe`, `unsubscribe`, `toggleDisplay`, `setWorldId`, `requestMapTile`, `flagPlayerSprite`, `toggleWaypoints` and other unknown methods are tolerant no-ops, **logged once per method**. Themes and mapping events beyond MappingEvent are acknowledged and ignored. |
| JM-15 | Event dispatch to plugins resolves and caches the registry, listener list and accessors per `(registry, field)`; listener lookups are not repeated per frame. |

## 2. Fullscreen / minimap overlay bridge (`JourneyMapFullscreenBridge`)

Publishes from **our** maps the events the real JourneyMap emits from its own, which is the
channel Create (train map) and Create: Rock & Stone (deposits) use to draw overlays.

| ID | Requirement |
|----|-------------|
| JMF-01 | `FULLSCREEN_RENDER_EVENT` every frame on the fullscreen map and (via a minimap-shaped view) on the minimap; `FULLSCREEN_MAP_CLICK_EVENT` on clicks, cancellable PRE (a plugin can consume the click) and POST. |
| JMF-02 | The `IFullscreen` handed to plugins is a dynamic proxy backed by `FullMapScreen` (centre, zoom, UI state). A single proxy and a single mutable minimap view are reused (one surface renders at a time), not reallocated per frame. |
| JMF-03 | Overlay toggles in the settings screen filter the dispatch by subscriber modId: `create` ↔ `map.showTrainOverlay`, `create_rns` ↔ `map.showDepositOverlay`. |
| JMF-04 | No overlays are drawn on the CAVE layer of the minimap. |
| JMF-05 | Create: Rock & Stone anchors its toggle widget at the centre of the left edge (colliding with our left toolbar). The bridge relocates it to the top-left addon zone next to Create's widget by reflecting on RNS's `ToggleLocation.JOURNEY` constant — render and click hitbox share that constant, so they stay in sync. On failure the widget stays where RNS puts it. |
| JMF-06 | The bridged widgets keep their native look; the addon API buttons start after their reserved footprint (~64 px each, Create via `toggleWidgetActive()`, RNS via `ModList`). |

## 3. Create train map bridge (`CreateTrainMapBridge`) and path service

Create's own tick only requests train data when JourneyMap's *internal* fullscreen is open.

| ID | Requirement |
|----|-------------|
| CTM-01 | When **our** map is shown, the bridge reproduces Create's work every tick (`TrainMapManager.tick()` + `TrainMapSyncClient.requestData()`) and calls `stopRequesting()` on close. |
| CTM-02 | Create's JM render path computes the hover pick in blocks relative to the map centre but compares it with absolute world coordinates (a missing `+ mapCenter`, a Create bug), so station/train name tooltips can never show. The bridge therefore keeps the render event away from Create and replays Create's rendering itself with a corrected pick, plus tooltips. |
| CTM-03 | Hot calls (`renderAndPick`, hover/render toggles, `getPixel`, `getPosition`, synced fields) use typed `MethodHandle`s with `invokeExact`; Create's overlay config is re-read at most once per second (and invalidated by the toggle click). |
| CTM-04 | The 4th boolean of `renderAndPick` (LINEAR filtering of the rail buffer) follows Create's FTBChunks rule: `linear = regionPixelSize × guiScale < 512`. |
| CTM-05 | Clicking a train follows it; hovering requests its remaining route: `TrainPathRequestPayload` → server → polyline. |
| CTM-06 | Server `CreateTrainPathService` simulates the route (Create's `Navigation.currentPath` only stores branch decisions): walks the track graph from the train's front, samples edges (3-block step on curves), consumes pending decisions at junctions until the remaining distance is spent. Limits: 512 edges, 2 048 points, one request per 250 ms per player. Empty reply when the train vanished or is not navigating. |
| CTM-07 | The path is repainted in gold over Create's rail pixels (relief and outlines preserved). |
| CTM-08 | Known accepted limits: the walk stops at a branch with no recorded decision; slight overshoot possible at crossings. The "first option" fallback and the distance-to-segment filter MUST NOT be retried without a better understanding of the orientation of Create's `TravellingPoint`s. |

## 4. `jmshim` (optional companion jar)

- A separate sub-project producing `sharedjourney-jmshim-<version>.jar`, a low-code mod
  declaring modId `journeymap` (version `6.0.0`) and depending (client, AFTER) on
  `sharedjourney` ≥ 1.0.0.
- It does nothing by itself: it only satisfies mods that test `ModList.isLoaded("journeymap")`
  before enabling their integration, so the bridge can then serve them.
- It MUST stay a separate jar and MUST NOT be installed together with the real JourneyMap.

## 5. Other automatic compatibility

- **Modded blocks**: colours extracted from the mod's own textures at runtime
  ([02 §6](02-render-engine.md)).
- **Modded mobs**: radar icons rendered from their own models
  ([07 §4.1](07-client-ui.md)), including framing overrides through data packs / resource packs.
- **Chunky** and similar pregenerators: new chunks are rendered immediately from the load
  event; `regen full` can render an already pregenerated world.

## Acceptance checks

1. With Waystones installed and no JourneyMap: waystone waypoints appear in the map,
   group named `waystones`, not editable, not saved to the local file.
2. With the real JourneyMap installed: the bridge logs that it disables itself.
3. With Create: trains and rails show on the fullscreen map and minimap; hovering a train
   shows its name and a gold route; clicking follows it.
4. With Create: Rock & Stone: its deposit toggle sits next to Create's toggle, not under
   the left toolbar.
5. Turning `showTrainOverlay` off removes the rails from both maps.
