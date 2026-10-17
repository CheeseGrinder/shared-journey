# 01 — Regions, layers and storage

## 1. Region model

| ID | Requirement |
|----|-------------|
| REG-01 | A region covers 512×512 blocks = 32×32 chunks. One pixel = one block. Constants: `REGION_BLOCKS = 512`, `REGION_CHUNKS = 32`. |
| REG-02 | A region is addressed by `RegionKey(dimension, layer, caveBand, rx, rz)` with `rx = floorDiv(chunkX, 32)`, `rz = floorDiv(chunkZ, 32)` (negative coordinates floor toward −∞). |
| REG-03 | `caveBand` is meaningful only for the `cave` layer; for every other layer the key stores `0` (`RegionKey.of` normalizes it; `new RegionKey(...)` callers pass `0`). |
| REG-04 | Pixels are ARGB ints, row-major (`index = x + z * 512` inside a region, `x + z * 16` inside a chunk). Alpha `0` = never painted (transparent). Every rendered column has alpha `0xFF`. |
| REG-05 | A region image is transported and stored as a 512×512 `TYPE_INT_ARGB` PNG. |

## 2. Layers

`MapLayer` is a registry-backed class, **not an enum**; one instance per id, so identity
comparison (`layer == MapLayer.CAVE`) is valid everywhere.

| Layer | Id | Displayable | Notes |
|-------|----|-------------|-------|
| Day | `day` | yes | Built-in |
| Night | `night` | yes | Built-in |
| Topographic | `topo` | yes | Built-in |
| Biome | `biome` | yes | Built-in |
| Cave | `cave` | yes | Built-in, vertical bands |
| Info | `info` | **no** | Hover sidecar; never in layer settings, UI, cycling or admin commands |
| Custom | `[a-z0-9_]+` | yes | Registered via `LayerRegisterEvent` ([09](09-public-api.md)) |

| ID | Requirement |
|----|-------------|
| LAY-01 | Ids are lowercase `[a-z0-9_]+`; anything else is rejected with `IllegalArgumentException`. Custom ids SHOULD be prefixed with the owning mod id. |
| LAY-02 | A custom id MUST NOT collide with a built-in id (`LayerRegisterEvent.register` throws). |
| LAY-03 | `MapLayer.name()` returns the uppercase id (legacy enum name, used in `index.json` keys and config values); `valueOf(name)` is case-insensitive and throws on unknown names without registering. |
| LAY-04 | Network decoding is lenient: an unknown layer id is registered on the fly as a custom layer. Same for `index.json` keys read from disk (a client cache may contain a custom layer before the server announces it). |
| LAY-05 | Active layers of a dimension = configured built-ins (`layers.sharedLayers` entry for that dimension, else `layers.defaultLayers`) ∪ every registered custom layer. Custom layers have no per-dimension toggle. |
| LAY-06 | `INFO` is removed from every parsed layer list; it can never be enabled through config. |
| LAY-07 | Layer translation key: `sharedjourney.layer.<id>`; UI falls back to the raw id when a custom layer ships no key. |

### 2.1 CAVE bands

| ID | Requirement |
|----|-------------|
| CAVE-01 | `band = floorDiv(y, 16)`; band `b` covers `y ∈ [16b, 16b+15]` (clamped to the dimension build height at render time). |
| CAVE-02 | The bands that exist are `layers.caveBands` (default −4..7, valid −8..20), global to all dimensions. |
| CAVE-03 | Each band of a region is a separate region image, stored, versioned and synced independently. |
| CAVE-04 | Custom layers have no bands. |

## 3. INFO sidecar (hover data)

`HoverRegionData` is produced by the render engine with the chunk in hand and travels the
same pipeline as an image region (index, handshake, delta, disk cache, hashes).

| ID | Requirement |
|----|-------------|
| INFO-01 | One sidecar per region (`caveBand = 0`), file `region_X_Z.bin`. |
| INFO-02 | Per **chunk present**: 256 ground heights (`short`, index `dz*16+dx`), 256 surface block ids (palette index, `byte`), 16 biome ids (palette index, `byte`, one per 4×4 cell, index `(dz/4)*4 + dx/4`). |
| INFO-03 | Palettes are region-wide; index `0` is reserved for `""` (unknown/air). A palette overflow (> 255 entries) falls back to index `0`. |
| INFO-04 | Binary format (GZIP): format byte `1`, 16 `long` presence bitmap (bit `localCz*32 + localCx`), block palette (`short` size + UTF strings), biome palette, then for each present chunk in bit order: heights, blocks, biomes. `deserialize` returns `null` for an unreadable or unknown-format blob. |
| INFO-05 | Ground height = `SurfaceHeight.groundY`: the `WORLD_SURFACE` heightmap walked down past air and hidden blocks. A hidden block holding a fluid (waterlogged plant) stops the walk. |
| INFO-06 | The biome of a 4×4 cell is sampled at the cell center column `(bx*4+2, bz*4+2)` at that column's ground height. |
| INFO-07 | `chunkEquals` lets the engine skip a rewrite (and the version bump) when heights, blocks and biomes are identical. |

## 4. Versions

| ID | Requirement |
|----|-------------|
| VER-01 | A region version is a `long`; new regions start at `System.currentTimeMillis()`. |
| VER-02 | On an effective rewrite: `version = max(version + 1, currentTimeMillis)`. |
| VER-03 | `versionOf(key)` resolves in order: index (truth) → in-RAM version (INFO / image) → file mtime (cached into the index) → `-1`. |
| VER-04 | The file's last-modified time is set to the version when saving, so a lost index can be rebuilt from mtimes. |
| VER-05 | `-1` means "region never rendered". |

## 5. On-disk layout

### 5.1 Server — `<world>/data/sharedjourney/`

```
index.json                       {"dim|LAYER|band|rx|rz": version}  (pretty JSON)
hashes.json                      {"dim|LAYER|band|rx|rz": "version:sha256hex"}
quarantine.json                  pending quarantined chunks (see 04)
waypoints_public.json            shared waypoints (see 06)
waypoints_banners.json           banner waypoints
waypoints_auto.json              automatic bed / portal / respawn anchor waypoints (see 06 §3.4)
waypoints/<player-uuid>.json     per-player private waypoints
<dim>/<layer>/region_<rx>_<rz>.png
<dim>/cave/<band>/region_<rx>_<rz>.png
<dim>/info/region_<rx>_<rz>.bin
<dim>/.../region_<rx>_<rz>.pub.png | .pub.bin    public variants (only while quarantined)
```

- `<dim>` is the path for vanilla dimensions (`overworld`, `the_nether`, `the_end`) and
  `namespace_path` (colon replaced by underscore) for others.
- `index.json` key = `dimension|LAYER|band|rx|rz` with the **uppercase** layer name
  (`minecraft:overworld|DAY|0|-1|2`).
- Legacy `cave_<band>/` folders are migrated to `cave/<band>/` at startup, on both server
  and client (`RegionStorage.migrateLegacyCaveFolders`); an existing target is left alone.

| ID | Requirement |
|----|-------------|
| STO-01 | `index.json` and `hashes.json` are saved atomically with the regions. A corrupted index is ignored (start empty; versions are re-derived from file mtimes). A corrupted `hashes.json` is ignored (worst case one redundant re-push). |
| STO-02 | `RegionStorage.writeAtomically` writes `<file>.tmp` in the same directory then renames over the target (atomic move when supported, plain replace otherwise). |
| STO-03 | The map belongs to the world save: it is part of regular world backups. |
| STO-04 | Public-variant sidecars (`*.pub.*`) exist only while the region has pending quarantined chunks; a stale sidecar (no pending chunk) is deleted when its region loads. |

### 5.2 Client — `<gameDir>/sharedjourney_cache/<serverId>/`

```
index.json                       what the client owns: {key: version}
waypoints.json                   local waypoints (see 06)
<dim>/<layer>/region_<rx>_<rz>.png     (same layout as the server)
<dim>/info/region_<rx>_<rz>.bin
```

| ID | Requirement |
|----|-------------|
| CLI-01 | `serverId` = the server address lower-cased with every char outside `[a-z0-9._-]` replaced by `_`; in singleplayer `sp_<world FOLDER name>` (never the `level.dat` display name, which two worlds can share); `unknown` as a last resort. |
| CLI-02 | The disk cache can be disabled (`map.diskCache = false`): newly received regions are no longer written to disk (they live in RAM for the session). Files and index already on disk are still read and declared at handshake. |
| CLI-03 | Disk writes go through a single-thread writer: submission order = write order, atomic per file. |
| CLI-04 | `DiskCache.purge(layer)` accepts a layer id, `layer/band` form (`cave/2`) or `all`; it removes index entries, files and in-RAM textures. |

## Acceptance checks

1. Walk across a region boundary at negative coordinates: tiles join without a seam and
   file names carry the floor-divided indices.
2. Delete `index.json` on the server and restart: regions are still served (versions from
   file mtimes).
3. Kill the server process while saving: no region PNG is truncated (temp + rename).
4. Create two singleplayer worlds with the same display name: their client caches do not
   mix (distinct folder names).
5. Place a legacy `<dim>/cave_3` folder in a cache and start: it appears as `cave/3`.
