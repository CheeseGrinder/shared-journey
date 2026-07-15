# Contributing to Shared Journey

Thanks for your interest. Before anything else, please read the
[support policy](README.md#%EF%B8%8F-support-policy--read-before-opening-an-issue) in the
README: this is a hobby project, reviewed and maintained on free time, with no obligation
toward users or contributors. Contributions are welcome under those terms — a well-scoped
pull request is, by far, the most effective way to get something changed.

## Reporting a bug

Search existing issues first. A useful report contains:

- Mod version, Minecraft version (1.21.1), NeoForge version, and whether the server is a
  dedicated server or a LAN/single-player world.
- The relevant part of the **log** (`logs/latest.log`, client and/or server) — crash
  reports in full.
- Minimal **reproduction steps**, and whether it happens with only Shared Journey
  installed. If another mod is involved, name it with its exact version.
- Screenshots for anything visual (map rendering, minimap, GUI).

Reports without logs or reproduction steps may simply be closed.

## Requesting a feature

Feature requests are read but triaged aggressively. Shared Journey has a design compass:
**the server owns the map, and the map never shows a player more than they are entitled to
see** (radius caps, cave unlocking, client-local hover data). Requests that fight that
model — client-side rendering, unrestricted radar, seeing through unexplored terrain —
will be declined. Requests that fit it may still wait indefinitely for time and interest;
implementing it yourself is always the faster path.

## Pull requests

- **Discuss before building big.** For anything larger than a focused fix, open an issue
  first and describe the approach — it avoids writing code that gets declined on design
  grounds.
- **Keep the scope tight.** One concern per PR. Refactors, formatting sweeps, and drive-by
  changes belong in their own PRs (and are the most likely to be declined — see the
  architecture notes below).
- **Describe what you tested in-game.** There is no automated test suite; the two dev
  clients + dev server (below) are the test bench. Say what you ran and what you observed.

### Dev environment

JDK 21, nothing else to install:

```bash
./gradlew build               # full build + format check
./gradlew runClient_1         # dev client (username: Dev)
./gradlew runClient_2         # second client, for multiplayer/sync testing
./gradlew runServer           # dedicated dev server (--nogui)
```

Multiplayer features (delta sync, shared waypoints, radar caps) must be tested with
`runServer` + both clients, not only in single-player.

### Code style — enforced

- **Formatting is not negotiable**: Spotless with Palantir Java Format (4 spaces, 120
  columns). Run `./gradlew spotlessApply` before committing; `build` runs `spotlessCheck`
  and will fail otherwise.
- **Everything in English**: identifiers, Javadoc, comments, log messages, command
  feedback, config comments. If you touch a file that still contains legacy French text,
  translate what you touch.
- **No inline blocks.** Every `if` / `else` / loop / `try` body gets braces on their own
  lines, followed by a blank line (unless the next line is a closing brace, `else`,
  `catch`/`finally`, or already blank). No `if (x) return;` one-liners. Methods span at
  least three lines (empty private constructors excepted).
- **No inline fully-qualified names.** Import at the top of the file and use the short
  name; never `new java.util.zip.GZIPOutputStream(...)` in a method body.
- **Comments explain constraints, not narration.** Match the density and tone of the
  surrounding code; comments that restate the code or justify the diff get flagged.
- **User-facing strings are localized**: add keys to *both* `en_us.json` and
  `fr_fr.json`, with the key constant in `common.util.Lang`.

### Architecture — read before moving code around

The package layering is deliberate and kept strict (see [CLAUDE.md](CLAUDE.md) for the
full map):

- `api` has no dependencies; `common` depends only on `api`; `server` and `client` depend
  on `common` and **never on each other**.
- `Payloads.Hooks` (static function-field indirection wired at startup) exists precisely
  to keep `common` free of server/client dependencies. It looks unusual; **do not
  refactor it away**.
- Chunk access happens **on the server main thread only** (`getChunkNow`); pixel work,
  PNG encoding, and disk I/O stay on the worker pool. Don't move work across that line.
- The mod is currently **mixin-free** (the JourneyMap bridge is a reflection proxy, mob
  radar icons render from the entity models directly). Introducing a mixin needs prior
  discussion in an issue.
- `jmshim` must remain a separate jar — never merge it into the main mod.
- Anti-cheat invariants (server radar cap, cave unlocking, precomputed hover data) are
  design decisions, not oversights. PRs weakening them will be declined.

### Commits

Follow the existing style: a conventional-commit-ish prefix (`feat:`, `fix:`,
`refactor:`, `docs:`…) and an imperative summary in English. Squash fixup noise before
opening the PR.

## Translations

`fr_fr.json` is maintained by the author. New language files are welcome as PRs; they
must cover every key of `en_us.json` at the time of the PR.
