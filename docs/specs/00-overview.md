# 00 — Overview

## 1. Purpose

Shared Journey gives every player of a Minecraft world **one shared, server-owned map**.
The server renders the terrain into image tiles, stores them in the world save, and pushes
them to clients; clients only display what they receive. A player joining an established
server instantly sees everything the community explored.

## 2. Goals

| ID | Goal |
|----|------|
| G-1 | One map for everybody: the server is the single source of truth. |
| G-2 | Cheap on the server: the tick thread only resolves chunks; pixels, PNG encoding and disk I/O run on worker threads. |
| G-3 | Cheap on the network: delta sync, per-player bandwidth cap, client-side disk cache. |
| G-4 | Hard to abuse: radar radius is capped server-side, caves are painted only where a player went, hover data is precomputed (no chunk-load timing oracle), hidden players do not leak through map updates. |
| G-5 | Works with modded content out of the box: block colors come from textures, mob radar icons from entity models, JourneyMap-API mods (Waystones, Create add-ons) work through a bridge. |
| G-6 | Extensible: public API for waypoints, map reads/actions, custom layers, block colors, markers and fullscreen-map UI. |

## 3. Non-goals

- No per-client map rendering. A client never computes map pixels.
- No chunk generation by the mod, ever (regeneration reads only chunks that are already
  fully generated on disk).
- No world editing, no claims/permissions system beyond "hidden from map" and OP checks.
- No automated migration of pre-release data formats beyond the ones listed in
  [01](01-regions-layers-storage.md) and [06](06-waypoints.md).
- No support promise: hobby project, as-is (see README support policy).

## 4. Glossary

| Term | Meaning |
|------|---------|
| **Region** | A 512×512-block tile = 32×32 chunks, 1 pixel per block. Addressed by a `RegionKey`. |
| **RegionKey** | `(dimension, layer, caveBand, rx, rz)`; `rx = floorDiv(blockX, 512)`. |
| **Layer** | A rendering of the world: `day`, `night`, `topo`, `biome`, `cave`, or a custom layer. |
| **Cave band** | A 16-block vertical slice of the CAVE layer; `band = floorDiv(y, 16)`. |
| **INFO** | Pseudo-layer carrying the hover sidecar (`HoverRegionData`): not displayable. |
| **Version** | Per-region monotonic long, `max(previous + 1, currentTimeMillis)`; grows on every effective rewrite. |
| **Index** | `{RegionKey → version}` registry (`index.json`), on the server and in each client cache. |
| **Handshake** | The client's login upload of its local index (with recomputed file hashes). |
| **Delta** | Regions whose server version is newer than what the player is known to hold. |
| **Hidden player** | A player who asked not to appear on the others' map (`radar.hideFromMap`). |
| **Quarantine** | Delayed diffusion of map updates made near a hidden player. |
| **Public variant** | The frozen pre-quarantine copy of a region served to non-trusted players. |
| **Bridge** | Reflection proxy that lets JourneyMap-API mods run without JourneyMap. |
| **OP** | Permission level ≥ 2. |

## 5. Architecture

### 5.1 Deployment

- **Mod id** `sharedjourney`, base package `fr.cheesegrinder.sharedjourney`, single
  Gradle module plus the optional `jmshim` sub-project.
- Two `@Mod` entry points: `SharedJourney` (both sides) and `SharedJourneyClient`
  (client only, `dist = CLIENT`).
- Dependencies: NeoForge and Minecraft (required, both sides); `journeymap` (optional,
  client, ordered AFTER so the real JourneyMap is detected).
- Network protocol version: `"1"` (see [03](03-sync-protocol.md)).

### 5.2 Layering (enforced by discipline, not by the build)

```
api                      no dependency on other mod packages
common    -> api         config, network payloads, region model, shared utils
server    -> common,api  engine, sync, services, commands, events
client    -> common,api  screens, renderers, caches, bridges
```

`server` and `client` never reference each other. `common` cannot call them either, so
packet handlers are injected through static function fields (`Payloads.Hooks`, wired by
the entry points). The same pattern backs `WaypointApi.Hooks`, `MapApi.Hooks` and
`MapMarkerApi.Hooks`. This indirection is deliberate and MUST NOT be refactored away.

### 5.3 Data flow

```
 server tick thread                    worker threads                       clients
 ────────────────                      ──────────────                       ───────
 chunk events ─┐
 CaveTracker  ─┼─▶ render queue ─▶ resolve chunk (getChunkNow)
 RegenService ─┘   (dedup)                │  (+ 3×3 biome neighbours)
                                          ▼
                                   renderChunk: pixels per layer/band
                                   + INFO sidecar ─▶ RegionImage / HoverRegion (RAM)
                                          │                │
                                          │                └─▶ PNG/bin on disk (async save)
                                          ▼
                              SyncService.pushRegionUpdates ──▶ per-player queue ─▶ RegionDataPayload
                                                                   (KB/s budget)     fragments
                                                                                        │
                                                                         ClientMapCache ◀┘ ─▶ DiskCache
```

## 6. Global invariants

| ID | Invariant |
|----|-----------|
| INV-1 | The client MUST NOT derive map pixels or hover data from anything but server pushes (hover MAY prefer a locally loaded chunk, which is the client's own world data). |
| INV-2 | Chunk access (`getChunkNow`, chunk loads) happens on the server main thread only. Workers get an already-resolved `ChunkAccess` and read it read-only. |
| INV-3 | The mod never causes chunk generation. Neighbour lookups use `ChunkStatus.BIOMES` with `load=false`; regen checks the on-disk NBT status first. |
| INV-4 | Region versions strictly increase on every effective rewrite and are never reused. |
| INV-5 | A rewrite that leaves the pixels (or hover data) unchanged MUST NOT bump the version nor trigger a push. |
| INV-6 | Region files, sidecars, `index.json`, `hashes.json`, `quarantine.json` and the client region cache are written atomically (temp file + rename). Waypoint JSON files are the known exception ([11](11-limitations-and-open-issues.md), L-06). |
| INV-7 | Every server-side handler of a client payload validates permission and bounds; a client is never trusted for sizes, counts or ranges. |
| INV-8 | The map layer ids on the wire are strings; unknown ids are registered on the fly (client works without the mod that registered a custom layer). |
| INV-9 | Server owner-facing text (admin command feedback, stats, debug) is English; player-facing text is translatable (`en_us`, `fr_fr`). |

## 7. Side-by-side responsibilities

| Concern | Server | Client |
|---------|--------|--------|
| Map pixels | Render, store, version, serve | Receive, cache (RAM + disk), draw |
| Layer availability | Config per dimension, announced via `LayerSettingsPayload` | Obeys the announcement |
| Waypoints | Persists public, banner and (default) per-player private ones | Local store, local-only ones, per-client visibility |
| Radar | Caps the radius, broadcasts non-hidden player positions | Draws nearby entities it already knows; mob icons from entity models |
| Hover info | Produces the INFO sidecar | Reads it locally (or the loaded chunk) |
| Config | `SERVER` (per world), `COMMON` | `CLIENT` (per machine) |

## 8. Distribution artifacts

- `sharedjourney-<version>.jar` — the mod.
- `sharedjourney-jmshim-<version>.jar` — optional low-code mod declaring modId
  `journeymap` (version `6.0.0`) for mods that test `ModList` before enabling their
  JourneyMap integration. MUST NOT be installed together with the real JourneyMap.
- Version scheme: semver, pre-release `-dev.N` until the first stable `1.0.0` (CI workflows
  `build.yml` and `release.yml`; Modrinth publish needs `MODRINTH_ID` / `MODRINTH_TOKEN`).

## Acceptance checks

1. Start a dedicated server and two clients; explore with one — the second sees the
   explored area without having been there.
2. Disconnect and reconnect: the client sends its index and only missing/newer regions
   are pushed.
3. Join a server whose mod version differs from the client's: NeoForge's payload version
   check (`"1"`) rejects the connection instead of desynchronizing silently.
