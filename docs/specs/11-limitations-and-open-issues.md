# 11 — Limitations and open issues

Three kinds of entries, kept separate:

- **A. Suspected defects** found while reading the code. Not reproduced in game; each says
  how to confirm it.
- **B. Accepted limitations** documented by the authors (code comments, `../../README.md`,
  `TODO.md`).
- **C. Documentation gaps** between the repository documents and the code.

## A. Suspected defects

| ID | Area | Finding | Why it matters | How to confirm |
|----|------|---------|----------------|----------------|
| L-01 | Block colours | **Fixed (uncommitted).** `assets/sharedjourney/palette/vanilla.json` was missing from the repository (no file, no history; an empty `palette/` folder existed), although `BlockPalette` and `../../CLAUDE.md` rely on it; a dedicated server has no vanilla client textures, so vanilla blocks would have degraded to `MapColor`. Regenerated with `tools/PaletteGenerator.java` against the Minecraft 1.21.1 client jar: 1059 of 1062 blockstates extracted; `processResources` packages it. | Colours of the whole map | Start a dedicated server and look for "Loaded bundled block palette: N blocks" (and no "not found" warning); regenerate on every Minecraft version bump |
| L-02 | Sync | The periodic delta fires when `gameTime % syncRateTicks == (entityId & 15)`. With `syncRateTicks < 16` (allowed range starts at 5) a player whose `entityId & 15 ≥ syncRateTicks` never matches: they only get the join delta, the handshake delta and immediate render pushes. | Stale far regions for those players | Set `syncRateTicks=5` and check `/sj stats` of players with different entity ids |
| L-03 | Banner waypoints | `BannerWaypointPayload` encodes the name with `writeUtf(name, 48)`, but `BannerWaypointService.onPlace` does not truncate; the anvil allows 50-character item names. Encoding a 49–50 character name throws, and the entry is persisted *before* the send, so the same failure can repeat at each login (`sendAllTo`). Public and private waypoints are truncated; banners are not. | Possible network error/disconnect for a name that is legal in vanilla | Rename a banner to 50 characters, place it |
| L-04 | Sync | `onPlayerJoin` queues a delta immediately, i.e. before the handshake can arrive (the client hashes its cache asynchronously first). The queue is not pruned when the handshake later seeds `sentVersions`, so already-owned regions of that first window are sent anyway. The comment in `onPlayerJoin` claims pushing starts after the handshake. | Wasted bandwidth at each login (bounded by the push window) | Watch `regionsSent` in `/sj stats` right after a reconnect with a full cache |
| L-05 | Waypoints | Public waypoints have **no ownership or permission check**: any player can overwrite or delete any public waypoint. | Griefing on open servers | Delete another player's public waypoint with a second account |
| L-06 | Persistence | Waypoint JSON files (`waypoints_public.json`, `waypoints_banners.json`, `waypoints/<uuid>.json`, client `waypoints.json`) are written with a plain `Files.writeString` (truncate then write), unlike regions and indexes (INV-6). A crash mid-write can leave a truncated file; the loaders log the error and start empty. | Waypoint loss after a crash | Kill the process during a write |
| L-07 | Memory | Region images, public variants, cached PNGs and hover regions loaded by the server are **never evicted**. A region image costs ≈ 1 MiB of ints plus its cached PNG, per layer and per cave band; a large world with 5 layers and 12 bands can keep a large number of MiB resident. | Server memory growth on big explored worlds | `/sj stats` shows RAM regions and an estimate |
| L-08 | Sync / I/O | `SyncService.drainQueue` runs on the server main thread and calls `MapManager.dataOf`, which lazily loads and decodes the region file (`ImageIO.read`) and encodes the PNG on first access: disk and CPU work on the tick thread, against the engine's own rule that the main thread only resolves chunks (G-2). | Occasional tick hitch when a player first needs a cold region | Profile the first sync of a large, never-loaded region |
| L-09 | Radar | The radar cap (`radarMaxRadius`) is announced to clients and honoured by the client's drawing code; the radar only draws entities the client already tracks. A modified client can ignore the cap, though it cannot see entities the server never sent. | The cap is advisory, not an enforcement | — |
| L-10 | Layer transition | The previous-layer stand-in ([07 LT-01](07-client-ui.md)) cannot tell "still loading" from "nothing exists on the new layer": for 1.5 s after a switch, a region that has no texture on the new layer (e.g. unexplored cave band) shows the previous layer's image before turning to plain background. Chained switches (A→B→C inside the window) stand in with B only, which may itself be missing. | Brief wrong-layer imagery after a switch | Switch DAY→CAVE over a region never explored underground |
| L-11 | Client cache | Disk loads are asynchronous ([03 ASM-05](03-sync-protocol.md)), so right after login or a fresh map open the first frames can show unloaded regions as background (at most 4 uploads per frame, 8 loads in flight) where the old synchronous load drew them at once; `isChunkPainted` (fullscreen CAVE hover) answers false until the band image is in RAM. | Cosmetic, first frames only | Open the fullscreen map zoomed far out on a large cached world |

## B. Accepted limitations (documented upstream)

| ID | Limitation | Source |
|----|------------|--------|
| B-01 | World edits by pistons, fluids and explosions are partially covered; chunks changed while unloaded refresh at their next dirty mark or load. | README |
| B-02 | Custom layers: no per-dimension config/ops toggle (active everywhere; the renderer may return null), no vertical bands, generic icon; the ops layer editor and `/sj admin layer` handle built-ins only. | README, `LayerRegisterEvent` |
| B-03 | JourneyMap bridge covers waypoints and the overlay types used by common integrations; themes, mapping events (beyond start/stop) and other API surfaces are acknowledged and ignored. | README |
| B-04 | Quarantine: trust at region granularity (two quarantined spots in a region cross-reveal to players near either), crash loses `.pub` sidecars (early reveal), banners of hidden players broadcast normally, witnesses recorded before the witness feature are lost. | `QuarantineService` |
| B-05 | Named banners placed by world generation, commands or non-entity placement are not detected. | `BannerWaypointEvents` |
| B-06 | Create train route simulation stops at branches without a recorded decision and may slightly overshoot at crossings. | `CreateTrainPathService` |
| B-07 | Mob icons: render layers (sheep wool, armor, profession) not drawn; bounds cached per entity type; fixed reference pose. | `MobHeadIcons` |
| B-08 | Minification flicker of thin Create rail lines at low zoom (rail buffer sampled without mipmaps). Fixing it needs mipmaps on Create's texture or a custom texel-AA shader (TODO P5). Underground rail overlay was implemented and reverted (poor result, high cost). | `../../TODO.md` |
| B-09 | The local player marker is a vector triangle until a texture asset is supplied (TODO P3). | `../../TODO.md` |
| B-10 | The API toolbar buttons have no overflow handling, a fixed ~64 px reservation per bridged widget, ItemStack icons only, and toggle outlines refresh only on press/rebuild. | `FullMapToolbarEvent` |
| B-11 | The fullscreen map is always north-up; rotating it would be a dedicated project. | `../../TODO.md` |
| B-12 | Cache integrity detects accidental or casual tampering of a client's own cache files; it is not authentication against a malicious client (which could compute any hash). It protects the client's display consistency, not the server. | design |
| B-13 | Player positions (dimension, x, z) of every non-hidden player are broadcast to everyone without distance limit — by design, that is the shared-map feature. Hiding is opt-in. | design |
| B-14 | Server engine settings (`engine.*`) and the worker count are file-only and read at start; there is no in-game editor for them. | `OpsConfigService` |
| B-15 | Minimap frame textures ([07 §2.1.1](07-client-ui.md)): straight square edges are stretched along their side (themes are uniform along the edge, no repeating pattern or per-edge ornaments); the round strip has finite angular resolution (512 texels), fine for smooth materials but it blurs sharp angular detail on very large minimaps; themes are bundled only (a resource pack can replace the PNGs but not the per-theme band geometry, which is fixed in code); the visual match of `CLASSIC` with the former procedural border was checked on a simulation of the sampling, not in game. | `MinimapFrame`, `tools/MinimapFrameGenerator.java` |
| B-16 | Radar height fade ([07 RDR-06..08](07-client-ui.md)): entities never fade out completely (opacity floor 0.25); the fade distances are not coupled to the fixed ±32 block query window, so a full-fade distance above ~32 is never reached by a drawn entity; the arrow and label placement (right of the icon, fixed offset) was not checked against every icon size in game; fullscreen labels do not avoid overlapping neighbouring icons. | `RadarHeightFade` |

## C. Documentation gaps

| ID | Gap |
|----|-----|
| C-01 | `../../CLAUDE.md` states NeoForge 21.1.77; `gradle.properties` uses `neo_version=21.1.219`. |
| C-02 | `../../README.md` command table omits `/sj icons reload|dump` and the stats sub-forms; its key table shows `+`/`-` while the default key bindings are `=` and `-`. |
| C-03 | `../../CLAUDE.md` package table omits classes added since: `QuarantineService`, `OpsConfigService`, `server.compat.CreateTrainPathService`, `PrivacyServerConfig`, the six payload-group classes, `api.client.MapMarker*` and the mob-icon / waypoint-icon renderers. |
| C-04 | Many source comments and `../../gradle.properties` cite "spec §N" for an original specification that is not in the repository; this `` folder is a fresh description and does not reproduce that numbering. |
| C-05 | `../../TODO.md`, `docs/UI_OPTIONS_INVENTORY.md`, the mods.toml description and several build comments are still in French, against the English-only convention. `fr_fr` translations are intentional. |
| C-06 | `README` describes hover info as "fully client-local"; precisely, it prefers the client's own loaded chunk and otherwise the server-pushed sidecar (never a server request). |

## Acceptance checks

For each A-entry, the "How to confirm" column is the acceptance test of the fix. B-entries
are not defects: they are checked only to make sure a change does not silently remove the
documented mitigation (e.g. the 250 ms path throttle, the 128-key request cap).
