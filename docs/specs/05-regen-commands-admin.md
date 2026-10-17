# 05 — Regeneration, commands and administration

## 1. Commands

Roots: `/sharedjourney` and `/sj` (identical trees). Commands are split between the server
dispatcher (`MapCommands`) and the client dispatcher (`ClientCommands`); the client
dispatcher intercepts its own subcommands and everything else goes to the server.

### 1.1 Server commands

| Command | Permission | Effect |
|---------|-----------|--------|
| `/sj stats` | everyone | Non-OP: the caller's own sync stats. OP: engine stats line + stats of every online player. |
| `/sj stats <player>` | OP (≥ 2) | That player's sync stats. |
| `/sj tp <x> <z>` | OP | Teleport the caller to the surface at `(x, z)`; Y computed server-side (see §1.3). Feedback is translatable. |
| `/sj admin sync force all [rx rz]` | OP | Force-resend to every player; ignores the index. |
| `/sj admin sync force <players> [rx rz]` | OP | Same for the selected players. |
| `/sj admin rerender <chunkRadius>` | OP | Local re-render around the caller (radius 0–32). |
| `/sj admin layer <dimension> <layer> <true\|false>` | OP | Enable/disable a **built-in** display layer for a dimension; persisted to the server config and broadcast to all players. |
| `/sj admin regen` | OP | Re-render already painted chunks. |
| `/sj admin regen full` | OP | Scan the world's region files and render every generated chunk. |
| `/sj admin regen cancel` | OP | Stop a running regen. |
| `/sj admin save` | OP | Async flush of regions, index and hashes. |

Admin feedback strings are English (INV-9).

`/sj stats` line per player: `regions sent, KB sent, queue, handshake entries, on-demand
requests, forced syncs, time since last region sent`.

### 1.2 Client commands

| Command | Effect |
|---------|--------|
| `/sj purge <layer\|all>` | Delete the local cache of a layer (`day`, `night`, `topo`, `biome`, `cave`, `cave/<band>`, any custom id) or of everything; reports how many files were deleted. |
| `/sj cache` | Local cache status: indexed regions, regions in RAM, pending assemblies. |
| `/sj goto <x> <z>` | Open the fullscreen map centred on the block (`+0.5`), with that block selected. Used by the clickable chat coordinates. |
| `/sj icons reload` | Re-read the mob-icon framing settings files and rebuild the icons. |
| `/sj icons dump` | Write the framing resolved for every living entity type to `config/sharedjourney/`. |
| `/sj radar reload` | Re-read the radar rule files ([12](12-radar-rules.md)); reports the number of rules in force. |

### 1.3 Teleport

| ID | Requirement |
|----|-------------|
| TP-01 | The client never decides the arrival altitude; it sends `/sj tp x z` and the server resolves Y (the client may not have the chunk, and `~` would keep a flight altitude). |
| TP-02 | The target chunk is loaded synchronously (one-off command). |
| TP-03 | Y = `MOTION_BLOCKING` heightmap + 1. In dimensions with a ceiling (Nether), scan down from the logical ceiling for the first non-fluid floor topped by ≥ 2 air blocks; fall back to the heightmap value if none. |
| TP-04 | Teleport entry points in the UI (context menu, waypoint list) are shown to OPs only; the map stays open after a teleport. |

## 2. Regeneration (`RegenService`)

Throttled, main-thread state machine with a boss bar and client progress.

| Mode | Source of the chunk set | Intended use |
|------|------------------------|--------------|
| `regen` | Chunks already painted (from the region index; `isChunkRendered` on the first active layer) | Re-render after a palette/engine change |
| `regen full` | Presence of each chunk in the dimension's `region/r.X.Z.mca` headers (async scan of the 4 KB offset tables; zero offset = absent) | Worlds pregenerated with Chunky; map never seen |
| `rerender <r>` | Painted chunks in a square of radius `r` chunks around the caller | Local fix; progress private to the caller |

| ID | Requirement |
|----|-------------|
| RG-01 | Only one regen runs at a time (`isRunning`); a second start fails with an explanatory message. |
| RG-02 | The set is stored as per-region 1 024-bit presence masks (~140 B per region) so pregenerated worlds with millions of chunks fit in RAM. |
| RG-03 | Per tick at most **4** chunks are loaded; loading pauses while the render queue holds ≥ **256** chunks. |
| RG-04 | **Never any terrain generation**: before loading, the chunk's NBT status is read through the vanilla IO worker; only a status ending in `full` is loaded (`level.getChunk`) and enqueued. Proto-chunks at the edge of the explored area are skipped. |
| RG-05 | In `regen full`, dimensions with no active layer (built-in or custom) are not scanned. |
| RG-06 | The scan is async; the boss bar shows "scanning". Epochs invalidate a scan that finished after a cancel or a restart. |
| RG-07 | A chunk counts as done as soon as it is processed (even if not renderable) so the client veil lifts everywhere. |
| RG-08 | All renders go through the normal queue, hence the privacy gate and the cave anti-exploit apply. |
| RG-09 | End of run: wait until the render queue is empty and no task is in flight, then cancel/cleanup (bar removed, `RegenProgressPayload(active=false)`, `RegenStatePayload(false)` for full mode). |
| RG-10 | A player joining mid-regen receives `RegenStatePayload(true)` and the current done-masks. Local re-renders are private and never synced to joiners. |

### 2.1 Client feedback

- Boss bar (`Lang.REGEN_BOSSBAR` with `done`/`total`), plus an own slim progress bar under
  the top info bar of the fullscreen map (the vanilla boss bar is hidden behind a screen).
- A violet veil (`0x66C05AE0`) over chunks that are painted but **not yet re-rendered**.
  Never-explored (transparent) chunks are not veiled. Local re-renders raise no veil.

## 3. In-game server configuration (ops)

The settings screen exposes a **Server** tab to players with permission level ≥ 2
([07 §5](07-client-ui.md)).

| ID | Requirement |
|----|-------------|
| OPS-01 | Opening the tab sends `OpsConfigRequestPayload`; a non-OP gets no answer. |
| OPS-02 | The editable snapshot (`OpsConfigPayload`): default layers, shared layers per dimension, cave bands, cave reveal radius (chunks), push radius, max KB/s, sync rate (ticks), on-demand flag, radar cap, death-waypoints flag, waypoint storage (server/client), bed / nether portal / respawn anchor automatic-waypoint flags, converted-waypoints flag ([14](14-converted-waypoints.md)), privacy policy, quarantine radius, quarantine drain minutes. |
| OPS-03 | **Apply** sends the working copy (C2S `OpsConfigPayload`). The server re-checks permission ≥ 2, drops invalid layer entries, clamps every number to the config bounds (caveBands to −8..20), keeps the current policy on an unknown name, saves the spec, invalidates caches, re-broadcasts `LayerSettingsPayload` to everyone and echoes the authoritative snapshot to the editor. |
| OPS-04 | The cave-band editor uses a min and a max slider and sends the full inclusive range. |
| OPS-05 | Not editable in game (file only): `engine.*` (threads, per-tick budget, blend radius, hidden blocks, colour overrides) and `common.*`. Worker count is fixed at engine start. |
| OPS-06 | Disabling a layer stops rendering and syncing it; existing region files and RAM data are kept. |

## 4. Config reload

`ModConfigEvent.Reloading` on the SERVER spec invalidates the layer/engine parsing caches
and the block-colour cache, and broadcasts `LayerSettingsPayload` to every player on the
main thread. The CLIENT spec reload re-sends the hide-from-map preference.

## Acceptance checks

1. `/sj admin regen full` on a Chunky-pregenerated world: boss bar scans then fills; no
   chunk is generated; map fills; cave bands remain gray except where players walked.
2. `/sj admin regen` then a second call: refused while running; `cancel` removes the bar
   and the veil on all clients.
3. `/sj admin rerender 4` only shows progress to the caller.
4. As OP, change the cave band range and Apply: all clients get the new bands without
   reconnecting.
5. As non-OP, `/sj admin save` is rejected by the permission requirement.
6. `/sj tp` in the Nether lands on a floor under the ceiling with two air blocks above.
