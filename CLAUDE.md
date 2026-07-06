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

Single Gradle module at the root (`src/main/java`, `src/main/resources`, `src/main/templates`), plus one subproject:

| Location  | Role                                                                             |
|-----------|----------------------------------------------------------------------------------|
| `...sharedjourney.api`    | Public interfaces: `Waypoint`, `MapLayer`, `ChunkLayerRenderer`, NeoForge events |
| `...sharedjourney.common` | Shared data: `RegionKey`, `RegionIndex`, `Payloads` (network packets), configs   |
| `...sharedjourney.server` | Async rendering engine: `ChunkColorizer`, `MapManager`, `SyncService`            |
| `...sharedjourney.client` | Client cache, minimap/fullmap GUI, waypoint store, JourneyMap bridge             |
| `...sharedjourney`        | `@Mod` entry points (`SharedJourney`, `SharedJourneyClient`)                     |
| `jmshim/` (subproject)    | Declares modId `journeymap` (empty mod) so JourneyMap integrations activate — MUST stay a separate JAR |

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
- `RegionIndex` is a `ConcurrentHashMap<RegionKey, Long>` (timestamp registry) serialized to `index.json`.

## Code Conventions

- All source code comments, log messages, and the README are written in **French**. Follow this convention when adding internal documentation.
- The custom layer pipeline (`LayerRegisterEvent`) collects registrations but storage/sync for them is not yet wired — only the built-in 5 layers are fully operational.
- The codebase was authored without being compiled in the target environment; minor API signature adjustments may be needed after changes.

## Entry Points

- `src/main/java/.../SharedJourney.java` — Common `@Mod` entry point (server + client). Registers configs, network payloads, server-side handlers.
- `src/main/java/.../SharedJourneyClient.java` — Client-only `@Mod` entry point. Registers client config, in-game config screen, client-side handlers, calls `JourneyMapBridge.init()`.
- `src/main/templates/META-INF/neoforge.mods.toml` — Mod metadata template (values expanded from `gradle.properties` at build time).