# Feature ideas

Ideas collected while comparing Shared Journey with JourneyMap and Xaero's
Minimap (decompiled for study, techniques only). Nothing here is planned or
validated: each idea needs a short design discussion before any code.

Effort: **S** = a few hours, **M** = a day or two, **L** = a week or more.
Items marked *to check* depend on a feature whose current state in the
project has not been audited.

## Already analyzed, not done yet

1. **Smooth layer changes (S/M)**: keep drawing the previous layer's texture
   until the new one is loaded (async load, small bounded queue, as
   JourneyMap's `RegionTile` does), hysteresis on the automatic
   day / night / underground switch instead of re-evaluating every frame,
   post `MapLayerChangedEvent` for automatic switches too, and show the layer
   name for about a second with a fade. Fixes the grey flash and the possible
   CAVE/DAY flapping under overhangs.
2. **Texture-based minimap frame (M)**: pre-drawn round and square frames,
   antialiased at the exact size, with themes. Today's round border is
   procedural.
3. **Mob icons, render layers (L)**: sheep wool, saddles, villager clothes,
   horse markings, armor, through a recording render tracer like Xaero's
   `EntityRenderTracer`. Risky for modded mobs.

## Radar (inspired by Xaero)

4. **Height fade (S)**: mobs far above or below the player fade out, with an
   optional Y offset label and an up / down arrow. Useful in caves.
5. **Radar categories in JSON (M)**: rules by entity type, tag, name or tamed
   state instead of the fixed toggles (hostile, passive, pets, villagers,
   players). Reuses the mob icon settings loader.
6. **Dropped items and hover names (S/M)**: item icons for `ItemEntity`, and a
   mob's name when hovering its icon.

## Sharing and server

7. **Discovered structures (M)**: villages, bastions, temples... marked when a
   player enters their bounding box, shared with everyone. Fits the shared map
   principle; the server already knows the structures.
8. **PNG export (S)**: `/sj admin export` for a region or the whole world, since
   region PNGs already exist on disk. A real web map (tiles over HTTP) is a
   step above (L).
9. **Freshness overlay (S/M)**: tint regions by last update time to spot areas
   worth re-exploring. The region index already keeps timestamps.
10. **Exploration statistics (M)**: percentage of the map discovered, per
    player.
11. **External overlays (M)**: force-loaded chunks and claims (FTB Chunks, Open
    Parties) through the marker API, and slime chunks.

## Waypoints

12. **Import / export (M)**: JourneyMap and Xaero formats, sharing through a
    command or chat.
13. **Automatic waypoints (S)**: bed, nether portal, respawn anchor, like the
    existing banner waypoints.
14. **Overworld / Nether conversion (S/M)**: show a waypoint of another
    dimension with converted coordinates (x8 / ÷8). *To check.*

## Comfort and quality

15. **Minimap presets (S/M)**: two or three switchable configurations (size,
    shape, radar), like JourneyMap's presets.
16. **Level of detail when zoomed far out (M/L)**: reduced region versions
    (JourneyMap's `LodChainRegistry`) to avoid loading dozens of textures at
    low zoom. *To check whether it is a problem here.*
17. **A few tests (S)**: the project has none. `MobIconSettingsParser`,
    `RegionHashes` and the ground height rule (`SurfaceHeight`) are easy to
    cover.

## Suggested first picks

- **1**: fixes a defect that is already visible.
- **7**: adds the most value to a shared map.
- **4 and 5**: low effort, build on the mob icon JSON loader.
