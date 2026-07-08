# Shared Journey

Mod de cartographie **serveur-autoritaire** pour Minecraft **1.21.1** / NeoForge **21.1.x** (Java 21).
Le serveur calcule et détient la carte (source de vérité) ; les clients la téléchargent et l'affichent.
Tous les joueurs partagent donc exactement la même carte, y compris ce qui a été exploré par les autres.

> ⚠️ **Note de version** : le template fourni ciblait Minecraft 26.1.1 (Java 25). Conformément à la
> spécification — et parce que l'écosystème JourneyMap/Waystones vit sur 1.21.1 — ce projet cible
> **1.21.1**. Les conventions du template (package `fr.cheesegrinder.sharedjourney`, modid
> `sharedjourney`, métadonnées via `src/main/templates`) sont conservées. Pour re-cibler plus tard :
> `gradle.properties` (3 valeurs) + adaptation des APIs.

## Build

```bash
./gradlew :mod:build          # jar final : mod/build/libs/sharedjourney-1.0.0.jar
./gradlew :mod:runClient      # client de dev
./gradlew :mod:runServer      # serveur dédié de dev
./gradlew :jmshim:build       # (optionnel) shim "journeymap" — voir §Bridge
```

> Le code est livré tel quel, sans avoir pu être compilé ici (pas d'accès à maven.neoforged.net
> depuis cet environnement). Attendez-vous à quelques ajustements mineurs de signatures au premier
> `gradlew build`.

## Architecture (spec §2) — 4 modules Gradle + assemblage

| Module   | Contenu                                                                                                                                                                                                 |
|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `api`    | Interfaces publiques : `Waypoint`, `MapLayer`, `ChunkLayerRenderer`, événements `WaypointEvent` (annulable) et `LayerRegisterEvent`. Aucune logique métier.                                             |
| `common` | `RegionKey`, `RegionIndex` (index.json), `Payloads` (paquets réseau + handshake), configs SERVER/COMMON. Ne dépend ni du client ni du serveur : les handlers réseau sont injectés via `Payloads.Hooks`. |
| `server` | `ChunkColorizer` (rendu des 5 couches), `MapManager` (moteur asynchrone + disque + index), `SyncService` (files d'envoi par joueur), `ServerEvents`, `MapCommands`.                                     |
| `client` | `DiskCache` (cache local par serveur), `ClientMapCache` (textures), minimap (rotation + radar), carte plein écran (waypoints), `WaypointStore`, commandes client, **bridge JourneyMap**.                |
| `mod`    | Assemblage : classes `@Mod`, métadonnées, runs de dev ; le jar final fusionne les sorties des 4 modules.                                                                                                |
| `jmshim` | (Optionnel) mod `lowcodefml` déclarant le modId `journeymap` — voir §Bridge.                                                                                                                            |

## Stockage (spec §3)

**Serveur** (dans la sauvegarde du monde, donc inclus dans les backups) :
```
world/data/sharedjourney/
├── index.json                      # Map<RegionKey, Timestamp> (registre RAM sérialisé)
└── overworld/
    ├── day/  night/  topo/  biome/  cave_0/ cave_1/ ...
    │   └── region_X_Z.png          # 512x512 px, 1 px = 1 bloc
```

**Client** :
```
.minecraft/sharedjourney_cache/
└── [ip_serveur | sp_nom_du_monde]/
    ├── index.json                  # ce que le client possède
    ├── waypoints.json              # waypoints locaux (spec §6.2)
    └── [dimension]/[layer]/region_X_Z.png
```

## Moteur asynchrone (spec §4)

- Pool `ExecutorService` dimensionné `max(1, min(cœurs - 2, maxWorkerThreads))`.
- Le tick serveur ne fait que **résoudre les chunks** (obligatoirement sur le main thread via
  `getChunkNow`) puis soumettre les tâches ; pixels, encodage PNG et écritures disque se font
  sur les workers. Plafond de tâches en vol pour ne pas inonder le pool.
- **Dirty marking** : pose/casse de bloc → le chunk 16×16 est remis en file (dédupliquée),
  seule la zone du chunk est réécrite dans la région RAM, le timestamp de la région est mis à
  jour dans l'index. Le PNG disque est flushé de façon asynchrone (sauvegarde du monde, arrêt,
  `/map admin save`).
- Limite connue : la lecture du chunk par les workers est en lecture seule mais non verrouillée ;
  en cas de course extrême le chunk sera re-rendu au prochain marquage dirty.

## Protocole réseau (spec §5)

1. **Handshake** : à la connexion, le client envoie son `index.json` local (lignes
   `clé=timestamp`, GZIP) via `ClientIndexPayload`. Le serveur en déduit ce que le client possède.
2. **Delta** : périodiquement (`syncRateTicks`, déphasé par joueur), les régions du rayon
   `pushRadiusRegions` dont la version serveur est plus récente sont ajoutées à une
   `ConcurrentLinkedQueue` par joueur.
3. **Batching + bande passante** : chaque tick, la file est dépilée dans la limite de
   `maxKbPerSecondPerPlayer` (converti en octets/tick), en fragments de `fragmentSize` octets
   (PNG = déjà compressé). Le client assemble, upload la texture, et écrit dans son cache disque.
4. La carte plein écran peut demander des régions hors rayon (`RegionRequestPayload`,
   désactivable côté serveur, throttlé et borné).

## Interface (spec §6)

- **Minimap** : overlay NeoForge (`RegisterGuiLayersEvent`), position/taille configurables,
  **rotation dynamique** optionnelle (rendu via matrices de pose), **radar** d'entités filtrable
  (joueurs / hostiles / passifs) dont le rayon est **plafonné par le serveur**
  (`radarMaxRadius`, 64 par défaut — anti-triche), waypoints, libellé de couche + coordonnées.
- **Plein écran** (touche `M`) : pan à la souris, zoom molette, changement de couche et de
  bande CAVE, **clic droit = créer un waypoint**, clic sur un marqueur = éditer
  (nom, couleur, visibilité, suppression). Waypoints sauvegardés en JSON local.
- Touches : `M` carte, `N` minimap on/off, `,` couche suivante (configurables).

## Commandes (spec §7) — racine unique `/map`

| Commande                                       | Côté       | Permission                                                          |
|------------------------------------------------|------------|---------------------------------------------------------------------|
| `/map stats`                                   | serveur    | niveau 0 : ses stats ; niveau 2 : moteur + tous les joueurs         |
| `/map stats <joueur>`                          | serveur    | OP                                                                  |
| `/map purge <layer\|all>`                      | **client** | — (supprime le cache local du calque)                               |
| `/map cache`                                   | **client** | — (état du cache local)                                             |
| `/map admin sync force <joueurs\|all> [rx rz]` | serveur    | OP — renvoi forcé, ignore l'index ; filtre optionnel sur une région |
| `/map admin rerender <rayonChunks>`            | serveur    | OP                                                                  |
| `/map admin layer <dim> <couche> <bool>`       | serveur    | OP                                                                  |
| `/map admin save`                              | serveur    | OP                                                                  |

Le dispatcher client intercepte `purge`/`cache` ; tout autre `/map …` part au serveur.

## Configuration (spec §8)

La hiérarchie demandée (globale puis écrasée par le monde) est celle de NeoForge :
- **Globale** : `defaultconfigs/sharedjourney-server.toml` (copiée dans chaque nouveau monde)
- **Par monde** : `world/serverconfig/sharedjourney-server.toml` (écrase la globale)
- Client : `config/sharedjourney-client.toml` — Common : `config/sharedjourney-common.toml`
- Écran de config en jeu (NeoForge `ConfigurationScreen`), rechargement à chaud pris en compte
  (les couches actives sont re-broadcastées aux clients).

## Bridge JourneyMap (spec §9) — Waystones & co sans modification

Fichier : `client/.../compat/JourneyMapBridge.java`.

- **Si le vrai JourneyMap est installé** : le bridge se désactive (détection par classes
  `journeymap.common.Journeymap` / `journeymap.client.JourneymapClient`, pas seulement le modId).
- Sinon, SharedJourney **fait le travail de JourneyMap** : scan des données d'annotations FML de
  tous les mods à la recherche de `@journeymap.api.v2.client.JourneyMapPlugin` (et de l'annotation
  legacy v1), instanciation de chaque plugin, puis appel de `initialize(IClientAPI)` avec un
  **proxy dynamique** (`java.lang.reflect.Proxy`). Aucune dépendance de compilation vers l'API :
  le bridge tolère les variations de signatures entre versions.
- Traduction : `addWaypoint`/`show(waypoint)` → `WaypointStore` (source = modId du plugin,
  suppression fiable via GUID → UUID stable) ; `playerAccepts` → `true` ;
  overlays/événements (`show(MarkerOverlay)`, `subscribe`, …) → ignorés proprement avec un log
  unique par méthode.

**Deux conditions pour une transition "painless" :**

1. **Les classes de l'API JourneyMap doivent exister au runtime** (les classes plugin des mods
   tiers y font référence). Deux options :
    - déposer le jar `journeymap-api-neoforge` dans `mods/` ;
    - ou l'embarquer en jarJar dans `:client` (dépendance `info.journeymap:journeymap-api-neoforge:2.0.0-1.21.1-SNAPSHOT`,
      dépôts déjà déclarés dans le build). **Vérifiez la licence du dépôt TeamJM/journeymap-api
      avant de redistribuer** ; la dépendance est laissée commentée dans `client/build.gradle`.
2. Certains mods ne chargent leur intégration que si `ModList.isLoaded("journeymap")` est vrai.
   Pour eux, installez le **shim** `sharedjourney-jmshim.jar` (module `jmshim`) : un mod
   `lowcodefml` vide qui déclare le modId `journeymap`. **Ne jamais l'installer avec le vrai
   JourneyMap** (conflit de modId, c'est voulu).

Limites actuelles du bridge : waypoints ✔ ; overlays (marqueurs/polygones/images), événements de
mapping et thèmes ✘ (journalisés). Waystones utilise principalement les waypoints → OK.

## API pour les autres mods (module `api`)

- `WaypointEvent.Added` (annulable) / `Removed` / `Updated` sur `NeoForge.EVENT_BUS` (client).
- `LayerRegisterEvent` sur le bus MOD au démarrage serveur pour déclarer des couches custom
  (`ChunkLayerRenderer`). **Limite** : l'événement est collecté mais le pipeline de
  stockage/synchronisation des couches custom n'est pas encore branché (les couches intégrées
  DAY/NIGHT/TOPO/BIOME/CAVE sont, elles, complètes).

## Tests de compatibilité (dev)

`mod/build.gradle` déclare en `localRuntime` (runs de dev uniquement, jamais publiés) :
**Waystones 21.1.30** + **Balm 21.0.59** (waypoints via l'API JourneyMap → teste la voie
"waypoints" du bridge) et **Create: Rock & Stone 1.0.2** (overlay de gisements → teste la voie
"overlays", actuellement journalisée mais non rendue). La chaîne complète de Create 6.0.10 (jar slim + Ponder + Flywheel + Registrate) est
déclarée depuis le Maven officiel de Create, conformément au wiki
(https://wiki.createmod.net/developers/depend-on-create/neoforge-1.21.1) ; les versions
sont centralisées dans `gradle.properties`.

Résultat attendu dans les logs au lancement du client de dev SANS le vrai JourneyMap :
`[Bridge JM] N plugin(s) JourneyMap initialisé(s)...` puis, en jeu, les waystones activées
apparaissent comme waypoints (source `waystones`). Pour le scénario A/B AVEC le vrai
JourneyMap, décommenter la ligne correspondante : le bridge doit logger qu'il se désactive.

## Autres limites connues

- Modifications du monde par pistons/fluides/explosions : partiellement couvertes (le rendu se
  rafraîchit au prochain chargement/marquage du chunk).
- La minimap en rotation reste carrée (pas de masque circulaire).
- `/map stats` niveau 0 nécessite d'être un joueur (pas la console).