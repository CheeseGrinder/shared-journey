# 12 — Radar rules (JSON categories)

Client-only. Which entities the radar draws, and how, is decided by **ordered JSON rules**
instead of fixed code. The four radar toggles (`showHostile`, `showPassive`, `showPets`,
`showVillagers`, see [08](08-configuration.md)) keep working: they gate the rules that name
the matching category. The bundled default file reproduces the classic behavior exactly
([07 RDR-02](07-client-ui.md)), so nothing changes until a file is added or edited.

Code: `RadarRules` (loading, merging, per-type cache), `RadarRulesParser` (lenient reader),
`RadarRuleSettings` (compiled model), `EntityDots.styleFor` (the single entry point of the
two radar surfaces: minimap and fullscreen map).

## 1. Sources and loading

Same layering as the mob icon settings ([07 §4.1](07-client-ui.md)), with its own folder name.

| ID | Requirement |
|----|-------------|
| RRL-01 | Sources, lowest to highest priority: (1) bundled `assets/sharedjourney/sharedjourney/radar_rules/*.json` (ships `default.json`); (2) any `assets/<namespace>/sharedjourney/radar_rules/*.json` from other mods or resource packs (namespaces alphabetically, resource pack order within a file); (3) the user's `config/sharedjourney/radar_rules/*.json` (files in name order). The config folder is not created automatically. |
| RRL-02 | The files are read with the client resources (resource reload, off the render thread, swapped in on the main thread) and by `/sj radar reload` ([05](05-regen-commands-admin.md)), which reports the number of rules in force. |
| RRL-03 | Merge, source by source: the rules of a later source are placed **before** those of the earlier ones (a user rule beats a bundled one; within one file the order is kept). A rule whose `id` equals an existing rule's `id` replaces it **in place**, or removes it when it carries `"enabled": false`. A file with `"replace": true` first discards every rule of lower priority. |
| RRL-04 | Robustness: an unreadable file or a root that is not an object is logged and ignored; `rules` that is not an array is logged and ignored; a rule that is not an object, has an unknown key (outside `_`-prefixed comment keys) or an invalid value is logged with its file and index and **skipped**, the rest of the file still applies. A mistyped matcher key therefore cannot turn a rule into a catch-all. Nothing in this path throws to the caller. Comments and trailing commas are tolerated (Gson). |
| RRL-05 | With no rule in force (for example a pack that replaces everything with nothing) the radar draws no entity, and a warning is logged on each load. |

## 2. File format

```json
{
  "replace": false,
  "rules": [
    { "id": "...", "<matcher fields>": "...", "<effect fields>": "..." }
  ]
}
```

`replace` is optional (default `false`). Keys of the root other than `replace` and `rules`
are ignored, so `_readme` style comments are allowed.

### 2.1 Matcher fields

A rule matches an entity when **every** matcher field it specifies matches (AND). A field
holding a list matches when **any** value matches (OR). A field that is absent is not tested;
a rule with no matcher field matches every candidate entity. A list may not be empty.

| Field | Type | Meaning |
|-------|------|---------|
| `type` | string or list | Entity type id (`minecraft:zombie`) or a whole namespace (`create:*`). An id that is not registered (mod absent) simply matches nothing. |
| `tag` | string or list | Entity type tag id (`minecraft:skeletons`; a leading `#` is accepted). Resolved from the tags the server synced. |
| `kind` | string or list | Class-based family: `living` (any living entity, armor stands included), `mob`, `enemy` (the `Enemy` marker interface: hostile mobs, slimes, phantoms...), `animal`, `water_animal`, `villager` (villagers and wandering traders), `tamable` (wolves, cats, parrots, tamed or not). |
| `name` | string or list | Glob (`*` any run, `?` any character, case-insensitive, whole-name match) tested against the entity's **custom name** (name tag). An entity without a custom name never matches; `"*"` therefore means "any named entity". |
| `tamed` | boolean | `true`: a tamable animal that is tamed. `false`: anything else (including non-tamable entities). Only `TamableAnimal` can be tamed for this field (not horses or llamas). |
| `owner` | `self` or `other` | A tamed animal owned by the local player (`self`) or by someone else (`other`). An animal without an owner never matches. |

### 2.2 Effect fields

| Field | Type | Default | Meaning |
|-------|------|---------|---------|
| `id` | string | none | Handle for later amendment (RRL-03). |
| `enabled` | boolean | `true` | `false` is only valid with an `id`: it removes the rule of that id from a lower-priority source. |
| `category` | `hostile`, `passive`, `pets`, `villagers` | none | Ties the rule to the toggle of the same name (`showHostile`, `showPassive`, `showPets`, `showVillagers`). Absent: no toggle controls the rule. |
| `visible` | boolean | `true` | `false` hides what the rule matches, whatever the toggles say (a way to *exclude* an entity before a broader rule). |
| `color` | string | the category color, or the passive gray | `"#RRGGBB"` (opaque) or `"#AARRGGBB"`; the dot color, also the fallback of a head icon. Category colors: hostile `#FF4040`, passive `#C8C8C8`, pets `#3B82F6`, villagers `#7CD97C`. |
| `style` | `auto`, `dot` | `auto` | `dot` draws the colored dot even when `radar.mobHeads` is on. |
| `iconScale` | number | `1` | Multiplier of the head icon size, clamped to 0.25–4. No effect on the dot. |

## 3. Semantics

| ID | Requirement |
|----|-------------|
| RRL-10 | Candidates: only living entities that are not players. Players are never rules' business (they are skin heads, [07 RDR-05](07-client-ui.md), gated by `showPlayers`); items, projectiles, vehicles and other non-living entities are never drawn. |
| RRL-11 | **First match wins.** The first rule, in merged order, whose matcher accepts the entity decides, even when that rule ends up hiding it: a category whose toggle is off does not fall through to a later rule. |
| RRL-12 | After the match the entity is drawn iff the rule is `visible` and its `category` (if any) has its toggle on. Toggles are read live, so the settings screen and the fullscreen toolbar apply at once. A rule without `category` ignores every toggle. |
| RRL-13 | Entities matched by no rule are not drawn. The bundled rules end with a catch-all on `mob`, so every mob is covered by default; a custom rule set that drops it hides unmatched mobs. |
| RRL-14 | The drawing style of the matching rule (color, `style`, `iconScale`) applies to both the minimap and the fullscreen map, to the head icon and to the dot fallback alike. Height fade, radius cap and the vertical window ([07 RDR-06..08](07-client-ui.md)) are unchanged. |
| RRL-15 | Cost: the type-level conditions (`type`, `tag`, `kind`) are resolved **once per entity type** into a short candidate list, cached until the rules reload or the entity type tags are updated (`TagsUpdatedEvent`). The list is cut after the first rule that needs no per-entity check. Per entity and per frame only a map lookup and the instance-level conditions (`name`, `tamed`, `owner`) of the remaining candidates are evaluated; no allocation. The `kind` of a type is judged from the first entity of that type seen. |

## 4. Bundled default

`default.json` is equivalent to the former hardcoded classification, in this order:

```json
{
  "rules": [
    { "id": "pets", "kind": "tamable", "tamed": true, "category": "pets", "color": "#3B82F6" },
    { "id": "villagers", "kind": "villager", "category": "villagers", "color": "#7CD97C" },
    { "id": "hostile", "kind": "enemy", "category": "hostile", "color": "#FF4040" },
    { "id": "passive", "kind": "mob", "category": "passive", "color": "#C8C8C8" }
  ]
}
```

## 5. Examples

A user file `config/sharedjourney/radar_rules/custom.json` (placed before the bundled rules):

```json
{
  "rules": [
    // Never show bats or armor stands, whatever the toggles.
    { "type": ["minecraft:bat", "minecraft:armor_stand"], "visible": false },

    // Other players' tamed animals: shown as orange dots, not as pets.
    { "kind": "tamable", "owner": "other", "color": "#FFA500", "style": "dot" },

    // Bosses always visible (no toggle), large icon.
    { "tag": "c:bosses", "color": "#FF00FF", "iconScale": 1.5 },

    // Named mobs (name tag) always visible, in gold.
    { "name": "*", "kind": "mob", "color": "#FFD700" },

    // Every mob of a mod follows the villagers toggle.
    { "type": "mymod:*", "category": "villagers" },

    // Take a bundled rule out, or change it in place.
    { "id": "passive", "kind": "animal", "category": "passive", "color": "#A0E0A0" }
  ]
}
```

A resource pack that starts from scratch: `{ "replace": true, "rules": [ ... ] }`.

## 6. Open points

Limitations and suspected defects are listed in [11](11-limitations-and-open-issues.md)
(B-17, L-12).

## Acceptance checks

1. Fresh install, no user file: the radar looks exactly as before (zombies red, wolves of
   untamed wolves gray only if `showPassive`, tamed wolves blue, villagers green, passive off
   by default).
   With `showPets` off a tamed wolf disappears (it does not turn gray).
2. Create `config/sharedjourney/radar_rules/custom.json` with
   `{ "rules": [ { "type": "minecraft:cow", "color": "#FF00FF", "style": "dot" } ] }`, run
   `/sj radar reload`: cows show as magenta dots even with `showPassive` off; sheep still
   follow `showPassive`.
3. Add `"category": "passive"` to that rule: cows hide again with `showPassive` off.
4. Break the file (`"tpye": "minecraft:cow"`, or a trailing garbage token): the log names the
   file and rule index, the other rules and the bundled ones still apply, no crash.
5. `{ "id": "hostile", "enabled": false }` then reload: hostile mobs fall to the `passive`
   catch-all (gray, `showPassive`).
6. Name a sheep with a name tag, rule `{ "name": "Dolly", "color": "#FFD700" }`: only that
   sheep is gold; renaming it updates the radar at once.
7. A user file with `"replace": true` and no rules: nothing is drawn and a warning is logged.
8. Join a server whose entity type tags differ (data pack): a `tag` rule follows the
   server's tags, not the client's.
