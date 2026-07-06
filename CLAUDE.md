# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Shared Journey** is a server-authoritative mapping mod for Minecraft 1.21.1 / NeoForge 21.1.77, written in Java 21. The server renders, owns, and distributes the map; clients display what the server pushes. All players share the same explored map.

- **Mod ID**: `sharedjourney`
- **Base package**: `fr.cheesegrinder.sharedjourney`
- **Build system**: Gradle with ModDevGradle plugin

## Build Commands

```bash
./gradlew build               # Build the mod JAR → build/libs/sharedjourney-1.0.0.jar
./gradlew runClient_1         # Dev client run (username: Dev)
./gradlew runClient_2         # Second dev client for multiplayer testing (username: Dev2)
./gradlew runServer           # Dedicated dev server (--nogui)
./gradlew :jmshim:build       # Optional JourneyMap shim JAR
```

There are no unit or integration tests.

## Project Structure

Single Gradle module at the root (`src/main/java`, `src/main/resources`, `src/main/templates`), plus one subproject (`jmshim/`: declares modId `journeymap` as an empty mod so JourneyMap integrations activate — MUST stay a separate JAR).

Packages under `fr.cheesegrinder.sharedjourney`, organized by part then by role:

| Package          | Contents                                                                                  |
|------------------|-------------------------------------------------------------------------------------------|
| *(root)*         | `@Mod` entry points: `SharedJourney`, `SharedJourneyClient`                               |
| `api`            | Public interfaces: `Waypoint`, `MapLayer`, `ChunkLayerRenderer`, `SharedJourneyConstants` |
| `api.event`      | Custom NeoForge events: `LayerRegisterEvent`, `WaypointEvent`                             |
| `common.config`  | `CommonConfig`, `ServerConfig`                                                            |
| `common.network` | `Payloads` (network packets + `Hooks` indirection)                                        |
| `common.region`  | `RegionKey`, `RegionIndex`, `RegionStorage` (disk layout + legacy migration)              |
| `common.util`    | `UndergroundCheck` (shared client/server "is underground" rule)                           |
| `server.command` | `MapCommands` (`/sj`, `/sharedjourney`)                                                   |
| `server.event`   | `ServerLifecycleEvents`, `PlayerEvents`, `ChunkEvents`, `ConfigEvents`                    |
| `server.render`  | `ChunkColorizer` (facade) + per-layer renderers (`SurfaceRenderer`, `TopoRenderer`, `BiomeRenderer`, `CaveRenderer`), `BiomeTints`, `RenderContext`, `ColorUtil` |
| `server.service` | `MapManager` (async engine), `SyncService` (delta sync), `RegenService` (full regen), `CaveTracker` (cave anti-exploit) |
| `client.command` | `ClientCommands` (`/sj purge`, `/sj cache`, `/sj goto`)                                   |
| `client.config`  | `ClientConfig`                                                                            |
| `client.event`   | `ClientSetupEvents` (keys, HUD layer), `ClientInputEvents`, `ClientSessionEvents`         |
| `client.gui`     | `FullMapScreen`, `WaypointEditScreen`                                                     |
| `client.net`     | `ClientNetHandler` (payload handlers)                                                     |
| `client.render`  | `MinimapRenderer` (HUD minimap)                                                           |
| `client.service` | `ClientMapCache`, `DiskCache`, `WaypointStore`                                            |
| `client.compat`  | `JourneyMapBridge` (reflection proxy)                                                     |

The packages keep the layering discipline of the former multi-module split: `api` has no dependencies, `common` depends only on `api`, `server` and `client` depend on `common` and never on each other.

## Key Architectural Patterns

**Server-authoritative rendering**: `ChunkColorizer` runs server-side using `MapColor`-based rendering (same palette as vanilla maps). Clients receive pre-rendered PNGs and never compute pixels themselves.

**Async engine with main-thread constraint**: Chunk access (`getChunkNow`) must happen on the server main thread. Only chunk resolution happens on tick; all pixel computation, PNG encoding, and disk I/O are offloaded to worker threads. A `tasksInFlight` cap (`workerCount * 8`) prevents flooding the pool. The dirty chunk queue uses both `ArrayDeque` and `LinkedHashSet` for deduplication.

**Network delta sync**: Each player has a `sentVersions: Map<RegionKey, Long>`. The handshake seeds this from the client's local disk index. Periodic pushes only send regions whose server version exceeds the player's known version. Per-player `ConcurrentLinkedQueue` drains at a configurable bandwidth cap (KB/s → bytes/tick).

**`Payloads.Hooks` indirection**: `common` defines the 4 network packets but cannot reference `server` or `client` (circular deps). Instead, `Payloads` exposes static function-fields (`Hooks`) that `SharedJourney` and `SharedJourneyClient` wire at startup. This is a deliberate design choice — do not refactor it away.

**JourneyMap bridge via reflection proxy**: Zero compile-time dependency on JourneyMap. `JourneyMapBridge` uses `java.lang.reflect.Proxy` to implement `IClientAPI` at runtime, translating waypoint calls to `WaypointStore`. Detection checks for JourneyMap's main classes, not just modId, because `jmshim` also declares the `journeymap` modId.

**Three-tier NeoForge config**: `CLIENT` (per-machine), `COMMON` (shared), `SERVER` (defaults in `defaultconfigs/`, overridden per-world in `world/serverconfig/`).

## Region and Layer Model

- A **region** = 512×512 blocks = 32×32 chunks, addressed by `RegionKey` (dimension + layer + caveBand + rx + rz).
- **Layers**: `DAY`, `NIGHT`, `TOPO`, `BIOME`, `CAVE`. `CAVE` has vertical bands (floor(y/16)).
- On-disk layout: `<dim>/<layer>/region_X_Z.png`, with CAVE bands grouped under a parent folder: `<dim>/cave/<band>/` (`RegionStorage.migrateLegacyCaveFolders` migrates the old `cave_<band>` layout on startup, server and client).
- **Cave anti-exploit**: CAVE bands are only painted where a player actually went underground (`CaveTracker` scans players each second and unlocks a radius around them via `MapManager.unlockCave`; `renderChunk` skips un-unlocked, never-painted cave chunks — including during `regen full`).
- `RegionIndex` is a `ConcurrentHashMap<RegionKey, Long>` (timestamp registry) serialized to `index.json`.

## Code Conventions

- All source code comments, log messages, and the README are written in **French**. Follow this convention when adding internal documentation.
- The custom layer pipeline (`LayerRegisterEvent`) collects registrations but storage/sync for them is not yet wired — only the built-in 5 layers are fully operational.
- The codebase was authored without being compiled in the target environment; minor API signature adjustments may be needed after changes.

## Entry Points

- `src/main/java/.../SharedJourney.java` — Common `@Mod` entry point (server + client). Registers configs, network payloads, server-side handlers.
- `src/main/java/.../SharedJourneyClient.java` — Client-only `@Mod` entry point. Registers client config, in-game config screen, client-side handlers, calls `JourneyMapBridge.init()`.
- `src/main/templates/META-INF/neoforge.mods.toml` — Mod metadata template (values expanded from `gradle.properties` at build time).