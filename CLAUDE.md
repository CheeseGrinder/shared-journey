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

| Package          | Contents                                                                                                                                                         |
|------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| *(root)*         | `@Mod` entry points: `SharedJourney`, `SharedJourneyClient`                                                                                                      |
| `api`            | Public interfaces: `Waypoint`, `WaypointApi` (CRUD + groups facade, `Hooks` indirection), `MapApi` (server-side read/actions facade), `MapLayer` (registry), `ChunkLayerRenderer`, `SharedJourneyConstants` |
| `api.event`      | Custom NeoForge events: `LayerRegisterEvent`, `BlockColorRegisterEvent`, `WaypointEvent`                                                                         |
| `api.client`     | Client-only public API: `MapView` (map geometry/conversions)                                                                                                     |
| `api.client.event` | Client-only events: `MapRenderEvent` (overlay draw hook, minimap + fullscreen), `FullMapScreenEvent`, `MapLayerChangedEvent`                                   |
| `common.config`  | `CommonConfig`, `ServerConfig` (facade) + per-section `LayersServerConfig`, `EngineServerConfig`, `SyncServerConfig`, `WaypointServerConfig`                     |
| `common.network` | `Payloads` (network packets + `Hooks` indirection)                                                                                                               |
| `common.region`  | `RegionKey`, `RegionIndex`, `RegionStorage` (disk layout + legacy migration), `HoverRegionData` (hover sidecar format)                                           |
| `common.util`    | `UndergroundCheck` (shared client/server "is underground" rule), `Lang` (i18n key constants)                                                                     |
| `server.command` | `MapCommands` (`/sj`, `/sharedjourney`)                                                                                                                          |
| `server.event`   | `ServerLifecycleEvents`, `PlayerEvents`, `ChunkEvents`, `ConfigEvents`, `BannerWaypointEvents` (named banner placed/broken)                                     |
| `server.render`  | `ChunkColorizer` (facade) + per-layer renderers (`SurfaceRenderer`, `TopoRenderer`, `BiomeRenderer`, `CaveRenderer`), `BlockPalette`, `TextureColorExtractor`, `BiomeTints`, `RenderContext`, `ColorUtil` |
| `server.service` | `MapManager` (async engine), `SyncService` (delta sync), `RegenService` (full regen), `CaveTracker` (cave anti-exploit), `PublicWaypointService` (shared waypoints), `PlayerWaypointService` (per-player private waypoints), `BannerWaypointService` (named banner markers), `MapApiService` (null-safe backing of `api.MapApi`) |
| `client.command` | `ClientCommands` (`/sj purge`, `/sj cache`, `/sj goto`)                                                                                                          |
| `client.config`  | `ClientConfig` (facade) + per-section `MinimapClientConfig`, `RadarClientConfig`, `WaypointClientConfig`, `MapClientConfig`                                     |
| `client.event`   | `ClientSetupEvents` (keys, HUD layer), `ClientInputEvents`, `ClientSessionEvents`, `DeathWaypointEvents`                                                         |
| `client.gui.screen` | Full `Screen`s: `FullMapScreen`, `MapSettingsScreen`, `WaypointListScreen`, `WaypointEditScreen`                                                              |
| `client.gui.modal`  | `ModalScreen` (base for the small centered dark-panel modals)                                                                                                   |
| `client.gui.util`   | Reusable UI building blocks: `UiColors`, `IconButton`, `ContextMenu` (a component, not a screen/modal), `OptionList` (+ generic rows), `SettingsControls` (row factories) |
| `client.net`     | `ClientNetHandler` (payload handlers)                                                                                                                            |
| `client.render`  | `MinimapRenderer` (HUD minimap)                                                                                                                                  |
| `client.service` | `ClientMapCache`, `DiskCache`, `WaypointStore`                                                                                                                   |
| `client.compat`  | `JourneyMapBridge` (reflection proxy)                                                                                                                            |

The packages keep the layering discipline of the former multi-module split: `api` has no dependencies, `common` depends only on `api`, `server` and `client` depend on `common` and never on each other.

## Key Architectural Patterns

**Server-authoritative rendering**: `ChunkColorizer` runs server-side. Block colors come from `BlockPalette` (texture-derived, JourneyMap-like), resolved in order: config overrides (`engine.blockColorOverrides`) → API registrations (`BlockColorRegisterEvent`, mod bus, posted at server start) → bundled vanilla palette (`assets/sharedjourney/palette/vanilla.json`, generated offline by `tools/PaletteGenerator.java` — regenerate on Minecraft version bumps) → runtime texture extraction from mod jars (`TextureColorExtractor`, pure Java+Gson) → `MapColor` fallback. Clients receive pre-rendered PNGs and never compute pixels themselves.

**Async engine with main-thread constraint**: Chunk access (`getChunkNow`) must happen on the server main thread. Only chunk resolution happens on tick; all pixel computation, PNG encoding, and disk I/O are offloaded to worker threads. A `tasksInFlight` cap (`workerCount * 8`) prevents flooding the pool. The dirty chunk queue uses both `ArrayDeque` and `LinkedHashSet` for deduplication.

**Network delta sync**: Each player has a `sentVersions: Map<RegionKey, Long>`. The handshake seeds this from the client's local disk index. Periodic pushes only send regions whose server version exceeds the player's known version. Per-player `ConcurrentLinkedQueue` drains at a configurable bandwidth cap (KB/s → bytes/tick).

**`Payloads.Hooks` indirection**: `common` defines the network packets but cannot reference `server` or `client` (circular deps). Instead, `Payloads` exposes static function-fields (`Hooks`) that `SharedJourney` and `SharedJourneyClient` wire at startup. This is a deliberate design choice — do not refactor it away.

**JourneyMap bridge via reflection proxy**: Zero compile-time dependency on JourneyMap. `JourneyMapBridge` uses `java.lang.reflect.Proxy` to implement `IClientAPI` at runtime, translating waypoint calls to `WaypointStore`. Detection checks for JourneyMap's main classes, not just modId, because `jmshim` also declares the `journeymap` modId.

**Three-tier NeoForge config**: `CLIENT` (per-machine), `COMMON` (shared), `SERVER` (defaults in `defaultconfigs/`, overridden per-world in `world/serverconfig/`).

## Region and Layer Model

- A **region** = 512×512 blocks = 32×32 chunks, addressed by `RegionKey` (dimension + layer + caveBand + rx + rz).
- **Layers**: `DAY`, `NIGHT`, `TOPO`, `BIOME`, `CAVE`. `CAVE` has vertical bands (floor(y/16)). `MapLayer` is a registry-backed class (NOT an enum, instances unique per id — identity comparison valid): mods register custom layers via `LayerRegisterEvent` (free `[a-z0-9_]+` ids), which ride the full region pipeline (render via their `ChunkLayerRenderer`, storage, delta sync, client cycling/buttons) but have no per-dimension config/ops-UI toggle, no bands, and a generic icon. Layer ids travel as strings on the network (lenient decode: unknown ids register on the fly, so clients work without the registering mod). `INFO` is NOT a display layer: it is the hover-data sidecar (per-region heights/surface blocks/biomes, `HoverRegionData` .bin blobs) riding the same region pipeline (index, delta sync, disk cache); it is produced by the render engine, excluded from layer settings/UI/commands, and makes fullscreen hover info fully client-local (no on-demand chunk loading — anti timing-attack).
- On-disk layout: `<dim>/<layer>/region_X_Z.png`, with CAVE bands grouped under a parent folder: `<dim>/cave/<band>/` (`RegionStorage.migrateLegacyCaveFolders` migrates the old `cave_<band>` layout on startup, server and client).
- **Cave anti-exploit**: CAVE bands are only painted where a player actually went underground (`CaveTracker` scans players each second and unlocks a radius around them via `MapManager.unlockCave`; `renderChunk` skips un-unlocked, never-painted cave chunks — including during `regen full`).
- `RegionIndex` is a `ConcurrentHashMap<RegionKey, Long>` (timestamp registry) serialized to `index.json`.

## Code Conventions

- Everything is written in **English**: source code documentation (Javadoc, internal comments, config comments) and runtime user-facing strings (log messages, command feedback). The README is also being translated to English. Legacy French text is being translated progressively during the clean-code effort — translate it to English whenever you touch a file.
- The codebase was authored without being compiled in the target environment; minor API signature adjustments may be needed after changes.

## Entry Points

- `src/main/java/.../SharedJourney.java` — Common `@Mod` entry point (server + client). Registers configs, network payloads, server-side handlers.
- `src/main/java/.../SharedJourneyClient.java` — Client-only `@Mod` entry point. Registers client config, in-game config screen, client-side handlers, calls `JourneyMapBridge.init()`.
- `src/main/templates/META-INF/neoforge.mods.toml` — Mod metadata template (values expanded from `gradle.properties` at build time).