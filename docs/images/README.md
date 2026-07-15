# Screenshot shot list

Drop the PNGs listed below into this folder using these exact names — the main
[README](../../README.md#screenshots) already references them. Until they exist, the images
in the README will show as broken links; that's expected and fine to commit as-is.

## Capture guidelines

- **GUI scale**: `Auto` or a fixed integer scale (2 or 3) — avoid fractional scaling, it
  blurs the custom-rendered HUD elements (minimap border, pills, icons).
- **F3 debug overlay off** unless a screenshot is specifically about coordinates.
- Prefer a scene with varied terrain (biome edge, water, some elevation) over a flat plain —
  it shows the layer rendering and colors better than an empty screenshot would.
- Crop tightly around the relevant UI; don't submit a full 1920×1080 screenshot when the
  minimap alone is the subject.

## Shots needed

1. **`minimap.png`** — The round minimap: rotation off, entity radar visible with a few
   mob head icons (not just dots) and at least one other player's head, the two-tone
   border, cardinal letters, and the time/biome/coordinates pill labels all visible.
2. **`minimap-rotate.png`** — Same, with dynamic rotation enabled, mid-turn, to show the
   arrow-fixed / world-rotating behavior at a glance.
3. **`fullmap-contextmenu.png`** — Fullscreen map (`M`), right-click context menu open,
   showing the waypoint / teleport / chat-link options.
4. **`fullmap-waypoints.png`** — Fullscreen map with a few waypoint diamonds and at least
   one banner waypoint visible, ideally with names shown.
5. **`cave-layer.png`** — Fullscreen map on the CAVE layer, showing the vertical-band cutoff
   (some area painted, some still blank) — illustrates the anti-exploit unlock behavior.
6. **`waypoint-list.png`** — The waypoint list screen (`U`), a handful of entries with mixed
   colors/visibility.
7. **`journeymap-bridge.png`** *(optional, nice-to-have)* — A Waystones (or similar) waypoint
   that only exists because of the JourneyMap bridge, proving the compat story without
   JourneyMap installed. Harder to stage; skip if it takes too long to set up.
8. **`config-screen.png`** *(optional)* — The in-game config screen, any tab.

## Mod icon

See [`docs/branding/`](../branding/) for the icon concept and export instructions.
