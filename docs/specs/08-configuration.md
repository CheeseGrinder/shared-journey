# 08 — Configuration reference

Three NeoForge config tiers. All numeric values are clamped to the stated range by the
config spec; the in-game ops tab clamps again server-side.

| Tier | File | Scope |
|------|------|-------|
| `CLIENT` | `config/sharedjourney-client.toml` | Per machine |
| `COMMON` | `config/sharedjourney-common.toml` | Shared by client and server |
| `SERVER` | defaults in `defaultconfigs/sharedjourney-server.toml`, overridden per world in `<world>/serverconfig/sharedjourney-server.toml` | Per world |

Hot reload: server reload invalidates parsed caches (layers, hidden blocks, colour
overrides), clears the block-colour cache and re-broadcasts the layer settings. Client
settings edited through the in-game screen apply live and are saved on close.

## 1. SERVER

### `[layers]`

| Key | Type / default | Range | Effect |
|-----|---------------|-------|--------|
| `defaultLayers` | list of layer names, `DAY, NIGHT, TOPO, BIOME` | built-in display layers only | Layers of dimensions not listed in `sharedLayers` |
| `sharedLayers` | list `namespace:dim=L1,L2`, default `minecraft:overworld=DAY,NIGHT,TOPO,BIOME,CAVE`, `minecraft:the_nether=CAVE`, `minecraft:the_end=DAY` | valid dimension id, valid built-in layer names; empty layer list allowed (= all disabled) | Active layers per dimension |
| `caveBands` | list of int, `-4..7` | each −8..20 | Cave bands rendered (`floor(y/16)`); must cover the world height |

### `[caves]`

| Key | Default | Range | Effect |
|-----|---------|-------|--------|
| `revealRadiusChunks` | 5 | 1–16 | Radius in chunks revealed on the CAVE layer around an underground player, on the band they are in (5 = 11×11 chunks); see [04 CAV-02](04-privacy-and-anti-exploit.md) |

### `[engine]`

| Key | Default | Range | Effect |
|-----|---------|-------|--------|
| `maxWorkerThreads` | 4 | 1–32 | Pool size = `max(1, min(this, cores − 2))`; read once at engine start |
| `renderChunksPerTick` | 32 | 1–512 | Chunks submitted to the pool per server tick |
| `biomeBlendRadius` | 2 | 0–7 | Grass/foliage/water colour averaging radius; 0 disables, 2 ≈ vanilla |
| `hiddenBlocks` | flowers/saplings tags, grasses, ferns, dead bush, mushrooms, torches, cobweb | entries `namespace:block` or `#namespace:tag` | Blocks not painted (see [02 §7](02-render-engine.md)) |
| `blockColorOverrides` | `[]` | `namespace:block=#RRGGBB` | Forced colours, highest priority |

### `[sync]`

| Key | Default | Range | Effect |
|-----|---------|-------|--------|
| `pushRadiusRegions` | 2 | 0–8 | Regions around the player kept in sync (radius 2 = 5×5) |
| `maxKbPerSecondPerPlayer` | 512 | 32–8192 | Per-player bandwidth cap for region pushes |
| `syncRateTicks` | 40 | 5–1200 | Interval between delta computations per player |
| `allowOnDemandRequests` | true | — | Fullscreen map may request regions outside the push radius |
| `radarMaxRadius` | 64 | 0–128 | Max client radar radius; 0 disables the radar |

### `[waypoints]`

| Key | Default | Effect |
|-----|---------|--------|
| `deathWaypointsEnabled` | true | Admin kill switch for automatic death waypoints (announced to clients) |
| `waypointStorage` | `SERVER` | `SERVER`: user DIMENSION waypoints persisted per player on the server; `CLIENT`: local file. TEMP always local, PUBLIC always server |

### `[privacy]`

| Key | Default | Range | Effect |
|-----|---------|-------|--------|
| `hiddenAreaPolicy` | `QUARANTINE` | `OFF`, `QUARANTINE`, `EXCLUDE` | Gating of map updates near hidden players ([04](04-privacy-and-anti-exploit.md)) |
| `quarantineRadiusChunks` | 8 | 1–32 | Radius (floored to view distance + 1) |
| `quarantineDrainMinutes` | 10 | 1–120 | Mean delay before a quarantined chunk is broadcast (±25 %) |

## 2. COMMON

| Key | Default | Range | Effect |
|-----|---------|-------|--------|
| `debugLogging` | false | — | Verbose render/sync logging (e.g. each dirty marking) |
| `fragmentSize` | 28 000 | 8 000–900 000 | Max bytes of one region fragment (S2C payloads must stay < 1 MiB) |

## 3. CLIENT

### `[minimap]`

| Key | Default | Range / values |
|-----|---------|----------------|
| `enabled` | true | — |
| `size` | 128 | 64–320 px |
| `corner` | `TOP_RIGHT` | `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT` |
| `shape` | `CIRCLE` | `CIRCLE`, `SQUARE` |
| `frameTheme` | `CLASSIC` | `CLASSIC`, `STONE`, `WOOD`, `THIN` — texture frame ([07 §2.1.1](07-client-ui.md)) |
| `rotateWithPlayer` | false | — |
| `showCoordinates` | true | — |
| `zoomDefault` | 1.0 | 0.25–4.0 (px per block, session start) |

### `[radar]`

| Key | Default | Range |
|-----|---------|-------|
| `enabled` | true | — |
| `radius` | 48 | 8–128 (capped by the server) |
| `mobHeads` | true | head icons vs dots |
| `heightFade` | true | fade radar entities far above / below the player ([07 RDR-06](07-client-ui.md)) |
| `heightFadeStart` | 6 | 1–64 blocks of vertical offset before the fade starts |
| `heightFadeFull` | 24 | 2–64 blocks at which the fade is complete (opacity floor 0.25); used as start + 1 when not above `heightFadeStart` |
| `heightFadeLabel` | false | signed Y offset label next to faded entities |
| `heightFadeArrow` | true | up / down arrow next to faded entities |
| `showPlayers` | true | — |
| `hideFromMap` | false | server-enforced preference |
| `showHostile` / `showPassive` / `showPets` / `showVillagers` | true / false / true / true | Toggles of the radar categories: they gate the radar rules that name the same `category` ([12](12-radar-rules.md)); a rule without category ignores them |

### `[waypoints]`

| Key | Default | Range |
|-----|---------|-------|
| `tempWaypointRadius` | 8 | 1–128 |
| `waypointBeacons` | true | — |
| `beaconMinDistance` | 4 | 0–512 |
| `beaconMaxDistance` | 512 | 16–4096 |
| `showWaypointNames` | true | fullscreen map names (shown from zoom ≥ 0.5) and beacon labels |
| `deathWaypoints` | true | also needs the server flag |

### `[map]`

| Key | Default | Effect |
|-----|---------|--------|
| `showTrainOverlay` | true | Create trains/tracks (bridge) |
| `showDepositOverlay` | true | Create: Rock & Stone deposits (bridge) |
| `showGrid` | false | Chunk grid (minimap from zoom 1.0, fullscreen from label 1024) |
| `showWaypoints` | true | Global waypoint overlay switch |
| `showCave` | true | Allow the auto-switch to CAVE underground |
| `defaultLayer` | `"DAY"` | Layer shown by default; falls back to DAY if invalid |
| `autoLayer` | true | Auto day/night/cave on the minimap |
| `rememberLayer` | false | Fullscreen map reopens on its last layer |
| `diskCache` | true | Persist received regions under `sharedjourney_cache/` |

## 4. Non-config data files

| File | Purpose |
|------|---------|
| `config/sharedjourney/mob_icons/*.json` | User overrides of radar mob icon framing |
| `assets/<ns>/sharedjourney/mob_icons/*.json` | Mod / resource-pack framing |
| `config/sharedjourney/radar_rules/*.json` | User radar rules (categories, colors, visibility), applied before the bundled ones; `/sj radar reload`. Schema in [12](12-radar-rules.md) |
| `assets/<ns>/sharedjourney/radar_rules/*.json` | Mod / resource-pack radar rules; `assets/sharedjourney/...` ships `default.json` (the classic categories) |
| `assets/sharedjourney/palette/vanilla.json` | Bundled vanilla block palette (see [11](11-limitations-and-open-issues.md), L-01) |

## Acceptance checks

1. Delete the server config: defaults regenerate with the values above.
2. Put `minecraft:the_nether=CAVE,DAY` in `sharedLayers` and reload: clients in the Nether
   get a Day button after the next settings broadcast.
3. Set `syncRateTicks=1200`: new terrain still reaches players promptly (immediate push on
   render) while periodic catch-up becomes slow.
4. Put an invalid layer in `defaultLayers`: the config screen/loader rejects it.
