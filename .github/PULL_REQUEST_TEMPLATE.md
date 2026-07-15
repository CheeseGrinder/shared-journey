<!--
Read CONTRIBUTING.md first if you haven't. Discussed the approach in an issue already?
Link it below — it saves everyone time on anything larger than a focused fix.
-->

## What does this change, and why?

## Related issue(s)

## Testing performed

<!-- There's no automated test suite: describe what you ran and what you observed.
     For anything touching sync/waypoints/radar, this means runServer + two clients
     (runClient_1 + runClient_2), not just single-player. -->

- [ ] `./gradlew spotlessApply` run, no leftover formatting diff
- [ ] Tested with `runClient_1` / `runServer` as needed
- [ ] Tested multiplayer behavior (`runClient_1` + `runClient_2` + `runServer`) — required for anything touching network sync, waypoints, or the radar
- [ ] Added/updated `en_us.json` **and** `fr_fr.json` for any new user-facing string

## Scope check

- [ ] This PR does ONE thing (no drive-by refactors or formatting-only changes bundled in)
- [ ] **Breaking change** — network protocol (`Payloads`), on-disk format (region/index/waypoint
      files), config keys, or the public `api` package changed in a way that isn't backward
      compatible. If checked, explain what breaks and any migration needed above.
