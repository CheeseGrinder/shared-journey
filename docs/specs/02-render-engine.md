# 02 — Render engine

Server-side. Entry point: `MapManager` (singleton created on `ServerStartedEvent`, closed on
`ServerStoppingEvent`).

## 1. Lifecycle

| ID | Requirement |
|----|-------------|
| ENG-01 | On `ServerStartedEvent`, in this order: post `LayerRegisterEvent` and `BlockColorRegisterEvent` on the mod bus; install API block colors; init `QuarantineService` (before the engine: region loads consult the pending set); init `MapManager`; init the three waypoint services; replay buffered early chunks. |
| ENG-02 | `MapManager` loads `index.json` and `hashes.json`, migrates legacy cave folders, and sizes the pool: `workers = max(1, min(engine.maxWorkerThreads, cores − 2))`. Worker threads are daemon, named `SharedJourney-Render`, priority `NORM − 1`. |
| ENG-03 | On `ServerStoppingEvent`: cancel any regen, shut the waypoint services down, close the engine (wait ≤ 10 s for workers, then `shutdownNow`, then save everything), then persist the quarantine set. |
| ENG-04 | Chunks loaded before the engine exists (spawn chunks) are remembered (cap 4096) and enqueued on start if not yet rendered. |

## 2. What triggers a (re)render

All paths end in `MapManager.enqueueChunk` (deduplicated queue) or `renderNow`.

| Trigger | Behavior |
|---------|----------|
| `ChunkEvent.Load`, `isNewChunk` and the chunk is a `LevelChunk` | `renderNow` — immediate submit from the event's reference. Required for pregenerated chunks (Chunky) that unload right after generation. |
| `ChunkEvent.Load`, new but not a `LevelChunk`, **or** loaded chunk never rendered | `enqueueChunk` (resolved on a later tick). |
| Block break / entity place / `NeighborNotifyEvent` | Enqueue the **3×3** chunk neighbourhood (light spreads up to 15 blocks across borders). |
| Piston `Post` | Enqueue the piston's chunk and, if different, the chunk 13 blocks away along the push direction. |
| Explosion `Detonate` | Enqueue 3×3 around every affected block. |
| Tree / huge-mushroom growth (`BlockGrowFeatureEvent`) | Enqueue 3×3 around the position. |
| `CaveTracker` unlock | `MapManager.unlockCave` enqueues the chunk. |
| `RegenService` | Enqueues each re-rendered chunk. |
| `MapApi.rerenderChunk` | Enqueues the chunk. |

| ID | Requirement |
|----|-------------|
| ENG-10 | The queue is an `ArrayDeque` plus a `LinkedHashSet` for deduplication; a chunk is queued at most once. |
| ENG-11 | `renderNow` falls back to the queue when `tasksInFlight ≥ workers × 8` (never holds an unbounded number of unloaded chunks in memory). |
| ENG-12 | Chunks modified while unloaded are not detected; they refresh on their next dirty marking or load. |

## 3. Tick budget and async pipeline

| ID | Requirement |
|----|-------------|
| ENG-20 | Each server tick (`ServerTickEvent.Post`), `MapManager.tick` polls up to `engine.renderChunksPerTick` (default 32) chunks while `tasksInFlight < workers × 8`. |
| ENG-21 | For each polled chunk, on the main thread: resolve the level and `getChunkNow(cx, cz)` (skip if unloaded or the level is gone); ask `QuarantineService.evaluate` (`RENDER` / `QUARANTINE` / `DROP`; `DROP` skips the chunk); resolve the 3×3 neighbourhood at `ChunkStatus.BIOMES` with `load = false` (a missing neighbour is approximated by the centre chunk); submit to the pool. |
| ENG-22 | All pixel computation, PNG encoding and disk I/O happen on workers. A worker failure is logged with the chunk coordinates and never stops the engine; `tasksInFlight` is always decremented. |
| ENG-23 | A rejected submit (shutdown) decrements `tasksInFlight` and is ignored. |

## 4. Rendering a chunk

`renderChunk` runs for every **active layer** of the dimension ([01 LAY-05](01-regions-layers-storage.md)).

1. For each non-CAVE layer: compute 256 pixels (custom layers via their
   `ChunkLayerRenderer`; a `null` result, a buffer ≠ 256 ints, or a throwing renderer
   skips that chunk on that layer, with a warning for the last two).
2. For `cave`: for each configured band, **only if** the band is unlocked for that chunk
   (`CaveTracker`) **or** the chunk is already painted on that band; then render the band
   and clear the unlock.
3. If at least one layer is active, extract and store the INFO sidecar for the chunk.
4. Collect the touched region keys and call `SyncService.pushRegionUpdates` (immediate
   push, without waiting for the periodic delta).

### 4.1 Write path

| ID | Requirement |
|----|-------------|
| WR-01 | `writeChunk` compares the incoming 16×16 pixels with the region content; if identical it returns without touching version, index or caches (INV-5). |
| WR-02 | Otherwise it copies the pixels, sets `version = max(version + 1, now)`, marks the region dirty, drops the cached PNG and updates the in-RAM index. |
| WR-03 | A quarantined write freezes the region's public variant first (`publicPixels = pixels.clone()`, `publicVersion = version`) if none exists; a normal write on a region that has a public variant is mirrored into it ([04](04-privacy-and-anti-exploit.md)). |
| WR-04 | `writeHoverChunk` follows the same rules for the INFO sidecar (`chunkEquals` skip, frozen copy via serialize/deserialize). |
| WR-05 | Region images and sidecars are loaded lazily from disk and then stay in RAM for the server session (no eviction, see [11](11-limitations-and-open-issues.md)). |

## 5. Per-layer rendering

Common rules for built-in renderers: a column whose ground Y is at or below the minimum
build height renders opaque black `0xFF000000`. Ground Y comes from `SurfaceHeight.groundY`
(hidden blocks and air are skipped). Biomes use a `BiomeManager` backed by the chunk and its
8 neighbours (game biome zoom: irregular block-by-block borders).

### 5.1 DAY / NIGHT (`SurfaceRenderer`)

- **Water** (fluid at ground): water colour of the biome, blended, scaled by depth
  `dark = max(0.35, 1 − 0.035 × depth)` (depth counted down to 24 blocks).
- **Land**: base colour from the palette; `grass_block` and `sugar_cane` use the biome grass
  tint; leaves and vines whose palette colour is (nearly) grayscale use the biome foliage
  tint; all leaves are dampened ×0.85.
- **Slope shading** against the north neighbour's ground height: higher ×1.10, lower ×0.82,
  equal ×1.0.
- **NIGHT** additionally scales by block light at `y + 1`:
  `f = 0.18 + 0.82 × light / 15`, with a bluish tint (R ×0.85, G ×0.90, B ×min(1, 1.05f + 0.05)).

### 5.2 TOPO (`TopoRenderer`)

- Water: blue gradient by depth below sea level, `t = clamp((sea − y) / 32)`,
  colour `(30, 80, 255 − 140t)`.
- Land: altitude gradient from sea level to max build height — green `#2E8B37` → sand
  `#C8B454` (t < 0.33) → brown `#8A7355` (t < 0.66) → near-white `#F2F2F2`.
- Contour lines: every ground Y divisible by 8 is darkened ×0.72.

### 5.3 BIOME (`BiomeRenderer`)

One colour per biome, sampled at sea level: the biome's grass colour override if it defines
one, otherwise a deterministic colour from the biome id hash (HSB, saturation 0.55,
brightness 0.80, hue `(hash & 0xFFFF) % 360`).

### 5.4 CAVE (`CaveRenderer`)

For a band `[yBottom, yTop]`, scan each column from the top: the first *open* block
(air, fluid or hidden block) starts a pocket; the first non-open block below it is the
**floor** and gives the colour.

- Floor colour = palette colour scaled by `1 − 0.45 × (yTop − (y + 1)) / 16` (deeper in the band = darker).
- Floor under lava: orange `#D45A12` (before depth scaling).
- Pocket open down to the band bottom: dark gray `#1A1A1A`.
- No pocket (solid column): opaque black.

## 6. Block colours (`BlockPalette`)

First hit wins; results are cached per block and the cache is cleared on config reload and
when API colours are installed.

| Order | Source |
|-------|--------|
| 1 | Config `engine.blockColorOverrides` (`namespace:block=#RRGGBB`) — the admin has the last word |
| 2 | `BlockColorRegisterEvent` registrations ([09](09-public-api.md)) |
| 3 | Bundled vanilla palette `assets/sharedjourney/palette/vanilla.json` (generated offline by `../../tools/PaletteGenerator.java`; regenerate on each Minecraft version bump) |
| 4 | Runtime texture extraction (`TextureColorExtractor`) from mod jars: blockstate → model (parent chain ≤ 16, texture refs ≤ 8) → texture, preferring keys `top, up, end, all, texture, particle, side`; average of opaque pixels |
| 5 | `MapColor` fallback (`NONE` → `STONE`); not cached per block (may depend on state/position) |

| ID | Requirement |
|----|-------------|
| COL-01 | Resources are looked up first in the mod's own jar (same-module `Class.getResourceAsStream`), then in the jar of the mod owning the asset namespace (`ModList.getModFileById`). Plain classloader lookups MUST NOT be used (module encapsulation). |
| COL-02 | `TextureColorExtractor` is pure Java + Gson (no Minecraft classes) so the offline generator and the runtime use the same algorithm. |
| COL-03 | Biome tints are approximated server-side from the biome climate triangle (hot-dry / cold / hot-wet) because client colormaps do not exist on a dedicated server; the grass colour modifier of the biome (swamp, dark forest) is applied. |
| COL-04 | Biome blending averages the (2r+1)² samples, `r = engine.biomeBlendRadius` (default 2, `0` = no blending). |

## 7. Hidden blocks

`engine.hiddenBlocks` lists blocks that are *not painted* (the block below is). Entries:
`namespace:block` or `#namespace:tag`.

| ID | Requirement |
|----|-------------|
| HID-01 | Default list: `#minecraft:flowers`, `#minecraft:saplings`, short/tall grass, fern, large fern, dead bush, both mushrooms, torches (4 variants), cobweb. |
| HID-02 | An explicit block entry is always honoured. A **tag** match never hides leaves (some tags, e.g. flowers, contain cherry/azalea leaves). |
| HID-03 | A hidden block holding a fluid is not skipped (water is the surface). Air is always skipped, so a hidden block floating above a gap never paints the air below it. |
| HID-04 | The same rule feeds the renderers, the INFO sidecar and the client's local hover (`SurfaceHeight`). |

## 8. Persistence

| ID | Requirement |
|----|-------------|
| PER-01 | `saveAll` is triggered by: overworld `LevelEvent.Save` (async), `/sj admin save` (async), engine close (sync). |
| PER-02 | It writes dirty region PNGs, dirty public variants, dirty hover blobs (real then public), then `index.json` and `hashes.json`. A failed file write re-marks the region dirty. |
| PER-03 | The `(version, SHA-256)` pair of the exact served bytes is recorded whenever bytes are encoded for serving or saving, even if the PNG was already cached (a version bump without pixel change must refresh the pair). |
| PER-04 | `/sj stats` (OP) reports workers, tasks in flight, RAM regions (≈ 1 MiB each) and dirty count, index size, queue length and quarantine size. |

## Acceptance checks

1. Break a block at a chunk border at night: the 3×3 neighbourhood re-renders and the map
   updates for connected players without waiting for the periodic sync.
2. Place a torch near a border: neighbouring NIGHT pixels brighten; redstone clocks do not
   spam the network (unchanged-pixel skip).
3. Pregenerate with Chunky: new chunks are rendered as they appear even though they unload
   immediately.
4. Add `minecraft:stone=#FF0000` to `blockColorOverrides` and reload: new renders use it.
5. With `biomeBlendRadius = 0`, biome borders on DAY are hard edges.
6. Set a flower at the surface: the map shows the block under it, hover reports that block.
