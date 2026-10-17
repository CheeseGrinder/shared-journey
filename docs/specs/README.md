# Shared Journey — Specifications

Behavioral specification of **Shared Journey** (`sharedjourney`), a server-authoritative
mapping mod for Minecraft 1.21.1 / NeoForge 21.1.x / Java 21.

These specs describe the mod **as it is implemented today** (reverse-engineered from the
code base, cross-checked against `../../README.md`, `CLAUDE.md` and `TODO.md`). They are the
reference for behavior; where the code and an older document disagree, the code wins and
the discrepancy is listed in [11-limitations-and-open-issues.md](11-limitations-and-open-issues.md).

## Index

| # | Document | Scope |
|---|----------|-------|
| 00 | [Overview](00-overview.md) | Vision, goals, non-goals, glossary, architecture, invariants |
| 01 | [Regions, layers and storage](01-regions-layers-storage.md) | Region model, layers, INFO sidecar, on-disk formats, versions |
| 02 | [Render engine](02-render-engine.md) | Triggers, async pipeline, per-layer rendering, block colors, persistence |
| 03 | [Synchronization protocol](03-sync-protocol.md) | Handshake, delta push, on-demand requests, bandwidth, payload catalogue |
| 04 | [Privacy and anti-exploit](04-privacy-and-anti-exploit.md) | Radar cap, cave unlocking, hidden players, quarantine, integrity |
| 05 | [Regeneration, commands and admin](05-regen-commands-admin.md) | `/sj` commands, regen modes, in-game ops config |
| 06 | [Waypoints](06-waypoints.md) | Model, types, groups, storage, banners, death, beacons |
| 07 | [Client experience](07-client-ui.md) | Minimap, fullscreen map, radar, screens, keys, client cache |
| 08 | [Configuration reference](08-configuration.md) | Every config key: default, range, effect |
| 09 | [Public API](09-public-api.md) | Facades, events, custom layers, markers, UI extension points |
| 10 | [Compatibility bridges](10-compat-bridges.md) | JourneyMap bridge, fullscreen bridge, Create trains, `jmshim` |
| 11 | [Limitations and open issues](11-limitations-and-open-issues.md) | Accepted limits, suspected defects, doc/code gaps |
| 12 | [Radar rules](12-radar-rules.md) | JSON radar categories: matchers, effects, merging, defaults |
| 13 | [Chunk overlays](13-chunk-overlays.md) | Force-loaded and slime chunks: server-computed bitmaps, sync, privacy, toggles |

## Conventions

- **Requirement IDs** (`REG-03`, `SYNC-12`...) are stable handles for review, issues and
  future tests. A requirement is a statement that is true of the current implementation.
  New behavior gets a new ID; never renumber.
- **MUST / MUST NOT** state invariants the design depends on. **SHOULD** states intended
  behavior with accepted exceptions. **MAY** states optional behavior.
- Each document ends with an **Acceptance checks** list: manual scenarios that verify the
  document (the project has no automated tests).
- "Server" means the logical server (dedicated or integrated). "Client" means a game
  client with the mod installed. Joining without the mod is governed by NeoForge's
  payload negotiation, which this mod does not customize (payloads are registered
  versioned, not optional).
- Everything in the code base and in this folder is written in **English**.
- Ids are lowercase (`day`, `cave`); legacy uppercase names (`DAY`) are used in config
  files and `index.json` for compatibility.

## Status

| Area | State |
|------|-------|
| Server render engine, sync, privacy | Implemented, validated in game |
| Waypoints (private/public/temp/banner/death) | Implemented |
| Fullscreen map, minimap, radar | Implemented |
| Radar rules in JSON ([12](12-radar-rules.md)) | Implemented, not yet checked in game |
| Chunk overlays: force-loaded and slime ([13](13-chunk-overlays.md)) | Implemented, not yet checked in game |
| Public API (waypoints, map, layers, markers, UI events) | Implemented; the toolbar, render and info-bar events are consumed internally by the chunk overlays ([09 §8](09-public-api.md)) |
| Player marker texture | Planned (TODO P3, waiting for the asset) |
| Create rail flicker at low zoom (minification) | Known, accepted (TODO P5) |
