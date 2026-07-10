# TODO — Shared Journey

Priorités : **P0** (critique) → **P5** (plus tard). Valeur : ★☆☆☆☆ (faible plus-value) → ★★★★★ (forte plus-value).

## Bugs

- [x] **P0 · ★★★★★ — Marqueur du joueur local absent** : ~~afficher un marqueur pour le joueur
  dont c'est la session (minimap + carte plein écran)~~ — **corrigé** : les triangles de
  `EntityDots.drawPlayerArrow` étaient émis avec un winding inversé par rapport aux quads GUI
  vanilla → éliminés par le back-face culling. Winding corrigé + `disableCull` +
  `setShaderColor(1,1,1,1)` contre l'état GL laissé par les overlays des plugins.
  → Suite : asset dédié pour le marqueur (voir UI / UX).
- [x] **P1 · ★★★★☆ — Couleur des beacons de waypoints instable** — **corrigé** : la
  WaypointFactory du bridge tirait une couleur aléatoire à chaque création (et le guid étant
  lui-même aléatoire par session, le hasher n'aurait rien réglé). La couleur est maintenant
  dérivée de l'identité stable du waypoint (modId + dimension + position) via une teinte HSV
  (`stableColor`) — identique entre sessions et entre joueurs ; `setColor` d'un mod garde la
  priorité.
- [x] **P1 · ★★★★★ — Couleurs de blocs incomplètes** — **corrigé** : rendu par palette de
  textures à la JourneyMap, côté serveur. `TextureColorExtractor` (Java pur + Gson : blockstate
  → modèle (chaîne de parents) → texture → moyenne des pixels opaques, lecture raster directe
  pour les PNG grayscale que `getRGB` éclaircissait) ; palette vanilla embarquée
  (`assets/sharedjourney/palette/vanilla.json`, 1059 blocs, générée offline depuis le jar
  client — les serveurs dédiés n'ont pas les textures client, et le rendu solo/dédié reste
  identique) ; `BlockPalette` chaîne overrides config (`engine.blockColorOverrides`, couture
  API) → palette embarquée → extraction runtime (jars de mods) → fallback `MapColor`.
  Les feuilles de cerisier sont roses (#E5ACC2). Notes d'implémentation :
  - accès ressources : les mods étant des modules Java nommés, `ClassLoader.getResourceAsStream`
    est bloqué par l'encapsulation de modules → lookup même-module via `Class#getResourceAsStream`
    + jars des autres mods via `ModList.getModFileById(namespace).getFile().findResource(...)` ;
  - feuilles/vignes : règle générique, AUCUNE espèce hardcodée — texture colorée (cherry,
    azalea, mods) = sa couleur ; texture grayscale (`ColorUtil.isGrayscale`) = teintée au
    runtime en jeu → teinte foliage du biome. Les teintes fixes vanilla (birch, spruce,
    mangrove, lily_pad — constantes client inaccessibles côté serveur) sont cuites en DONNÉES
    dans la palette générée (`BAKED_TINTS` du générateur) ; pour un mod, l'échappatoire est
    `engine.blockColorOverrides` ;
  - VRAIE cause racine du gris cherry : `#minecraft:flowers` (défaut de `hiddenBlocks`)
    contient `cherry_leaves`/`flowering_azalea_leaves` depuis la 1.20 (pollinisation des
    abeilles) → feuilles « cachées », la descente s'arrêtait sur l'AIR sous la canopée →
    `MapColor.NONE` → STONE gris. Double fix : un match par TAG ne cache jamais des feuilles
    (les entrées explicites restent honorées — pas de migration de config nécessaire), et la
    descente traverse l'air (un bloc caché au-dessus du vide, ex. propagule de mangrove, ne
    peint plus de gris).
- [x] **Overlay de progression du regen** — **fait** : pendant un `/sj admin regen [full]`,
  les chunks pas encore re-rendus sont voilés en violet (`STALE_OVERLAY`) sur la minimap et
  la carte plein écran, à la granularité CHUNK. Le serveur pousse ~1×/s des masques de
  1024 bits par région touchée (`RegenChunksPayload`) + un état start/stop
  (`RegenStatePayload`, envoyé aussi aux joueurs qui rejoignent en cours) ; le client dessine
  les chunks manquants en runs horizontaux fusionnés (`MinimapRenderer.drawRegenVeil`).
- [ ] **P2 · ★★★☆☆ — Labels de waypoints : lisibilité** : partiellement grisés selon l'angle de
  regard (malgré la passe SEE_THROUGH unique) et illisibles de loin — traiter les deux dans la
  même passe sur le rendu des étiquettes.
- [ ] **P2 · ★★★☆☆ — Noms des gares et des trains toujours absents** sur la carte (le tooltip
  au survol via `renderAndPick` de Create ne s'affiche pas — instrumenter le `Rect2i` de bornes
  et le pick).
- [x] **P2 · ★★☆☆☆ — Téléportation en vol** — **corrigé** : le Y d'arrivée est maintenant
  calculé côté serveur via la nouvelle commande `/sj tp <x> <z>` (heightmap MOTION_BLOCKING+1,
  scan sous le plafond dans le Nether) au lieu du `/tp` vanilla avec Y client ou `~`.
- [ ] **P2 · ★★★★☆ — Infos de survol (bloc/biome) : perf et tenue en charge** : plus la zone
  survolée est loin, plus la réponse est lente — déjà sensible à 1 serveur / 2 joueurs. Doit
  être « charge-proof ». Investiguer l'approche JourneyMap (instantané car 100 % local).
  _Goulot identifié : pour un chunk non chargé, le serveur fait un `level.getChunk()` SYNCHRONE
  sur le main thread (chargement complet du chunk). Pistes, par ordre de préférence :_
  1. _sidecar de survol par région : quand le moteur rend un chunk (il l'a déjà en main),
     produire aussi les données de survol (hauteurs/bloc/biome palettisés) et les stocker à
     côté du PNG ; les pousser avec la sync de région → le client a tout en local, zéro
     requête, instantané comme JM ;_
  2. _à défaut : parser le NBT du chunk directement depuis `chunkMap.read()` (heightmaps,
     palettes de sections, biomes) sur un thread IO, sans jamais charger le chunk ;_
  3. _cache LRU serveur des réponses + budget global (pas seulement par joueur)._
  _**Contrainte anti-exploit** : la latence de réponse ne doit PAS révéler si un chunk est
  chargé — un chunk chargé loin de tout = un joueur à proximité, même caché de la carte
  (attaque par timing). La piste 1 la satisfait par construction (même chemin de réponse
  partout) ; toute solution à la demande doit uniformiser le chemin (ex. toujours passer par
  les données rendues sur disque, jamais par le chunk vivant)._
- [ ] **P3 · ★★★☆☆ — Fuites d'information malgré « me cacher de la carte »** : au-delà du
  timing des infos de survol (voir ci-dessus), un joueur caché « dessine » sa position sur la
  carte partagée par trois vecteurs :
  1. les blocs qu'il casse/pose (re-rendu + push en direct des régions) ;
  2. les nouveaux chunks qu'il GÉNÈRE en explorant (le front d'exploration trace sa
     trajectoire en temps réel) ;
  3. le déverrouillage des bandes CAVE autour de lui (`CaveTracker`) quand il est sous terre.
  _Design proposé : file de QUARANTAINE côté serveur, qui gate la DIFFUSION (le rendu du PNG,
  lui, se fait normalement). L'attribution exacte d'un changement étant impossible
  (NeighborNotify n'a pas d'entité), heuristique de proximité : tout chunk dirty / nouveau
  chunk / unlock cave situé à moins de N chunks d'un joueur caché part en quarantaine._
  - _Indexée par CHUNK, pas par joueur (pas de propriétaire) : deux joueurs cachés proches
    partagent les mêmes entrées, un seul timer ; au drain, on ré-évalue — si un joueur caché
    (n'importe lequel) est encore à proximité, on prolonge._
  - _Destinataires : push immédiat pour le joueur caché lui-même et pour les joueurs assez
    proches pour que le jeu leur streame déjà la zone (ils voient les changements en jeu, la
    carte ne leur apprend rien) ; différé pour tous les autres._
  - _Version « publique » par région distincte de la version réelle : sinon le handshake de
    reconnexion contourne la quarantaine._
  - _Drain PROGRESSIF après le délai configurable (ex. 5-15 min) : un drain d'un coup
    redessinerait la trajectoire entière._
  _Alternative radicale en option serveur : les joueurs cachés ne contribuent pas du tout à
  la carte tant qu'un joueur visible ne repasse pas par la zone (carte moins complète, zéro
  fuite)._

- [ ] **P4 · ★★★☆☆ — Intégrité et robustesse du cache client (images de régions)** : si un
  client modifie un PNG de région dans son cache (Photoshop...), rien ne le détecte — le
  handshake ne compare que les VERSIONS (timestamps) de l'index, pas le contenu : l'image
  trafiquée reste affichée tant que le serveur ne re-rend pas la région.
  _Principe : la sécurité vient du SERVEUR, pas du format — tout fichier client (JSON, NBT,
  métadonnées PNG) est modifiable par le joueur. Trois volets :_
  1. _**vérification serveur** (la vraie protection) : hash du PNG calculé au rendu et stocké
     dans l'index serveur. Au handshake, le client RECALCULE le hash de ses fichiers locaux
     (pas celui de son index, falsifiable aussi) et l'envoie avec la version ; même version +
     hash différent = trafiqué/corrompu → le serveur re-pousse la région. Le hash EST le
     "diff" serveur/local, en quelques octets par région. Utiliser SHA-256 : CRC32 ne détecte
     que la corruption accidentelle (forger un fichier à CRC identique est trivial).
     Fallback manuel existant : `/sj purge` ;_
  2. _**métadonnées embarquées dans le PNG** (chunk tEXt : clé de région, version, hash) :
     l'index client devient un cache RECONSTRUCTIBLE — auto-réparation en cas de
     corruption/désync, sans scanner tous les PNG à chaque session (reconstruction à la
     demande seulement) ;_
  3. _**format des fichiers machine-only** : envisager NBT (binaire, compact, `NbtIo`) pour
     les index à la place du JSON ; GARDER le JSON pour `waypoints.json` (lisibilité/debug/
     backup = feature). À trancher pendant le chantier clean code._

- [ ] **P3 · ★★★★☆ — API publique enrichie** : même si le mod reste privé, exposer un maximum
  dans le package `api` pour permettre à d'autres mods d'interagir.
  _**Stratégie retenue (cadrage fait)** : pas de grande API spéculative. Deux règles :_
  1. _**règle de la couture** : chaque chantier conçoit ses internals AVEC le point
     d'extension (pipeline, listes d'entrées enregistrables), même non publié ;_
  2. _**publication en fin de chantier** : chaque tranche d'API est publiée à la fin du
     chantier qui stabilise son modèle — jamais avant (modèle instable), jamais longtemps
     après (couture fraîche). Le mod étant privé, casser une tranche publiée trop tôt reste
     borné à une session de refactor._
  - [x] **rendu écran (v1 faite)** : `api.client.MapView` (géométrie, conversions
    monde↔écran, couche) + `api.client.event.MapRenderEvent` (posté chaque frame pour la
    minimap ET le plein écran, sous les marqueurs joueurs) + `FullMapScreenEvent.Opened/
    Closed` + `MapLayerChangedEvent`. Le `BridgedMapView` du bridge JM étend `MapView`.
    **Consommateur de validation prévu : les waypoints de bannière** (icônes sur la carte).
    Suite v2 : enregistrement d'icônes/marqueurs de haut niveau (clamping bordure fourni).
  - [ ] **rendu serveur (image de région)** : surcharge de couleur par bloc, post-traitement
    de région, blocs masqués — **couture posée pendant le chantier palette** :
    `engine.blockColorOverrides` (config serveur, prioritaire sur toute la chaîne) et
    `BlockPalette` comme point d'entrée unique de résolution ; reste à publier (événement ou
    registre `api`) quand un consommateur concret existe ;
  - [ ] **UI fullscreen** (boutons, menu contextuel, barre d'infos) — **après le rework UI**
    (étape 5), les écrans actuels vont être réécrits ;
  - [ ] **waypoints** : façade CRUD — **après stabilisation du modèle** (groupes, death
    waypoints de l'écran de gestion) ; le besoin est couvert d'ici là par le bridge JM ;
  - [ ] **couches** : finaliser le pipeline `LayerRegisterEvent` — bloqueur structurel :
    `RegionKey`/réseau/disque sont indexés sur l'enum `MapLayer`, passer à des ids libres est
    un chantier dédié. En attendant, documenter l'événement comme non câblé ;
  - [ ] **lecture/actions** (positions joueurs, état des régions, re-rendu) : à la demande,
    quand un consommateur concret existe (YAGNI).
- [ ] **P2 · ★★★★☆ — Écran de gestion des waypoints** : groupes, ajout/édition/suppression,
  filtre par dimension, waypoints globaux, groupe « morts » (death waypoints), etc.
- [ ] **P3 · ★★★☆☆ — Waypoints de bannière** : reprendre le comportement vanilla
  bannière + carte — une bannière NOMMÉE (renommée à l'enclume) posée dans le monde devient un
  marqueur partagé sur la carte (minimap + plein écran), affiché avec l'icône de bannière
  vanilla (textures `map/decorations/banner_<couleur>.png`, la couleur suit la bannière),
  retiré quand la bannière est cassée.
  _Design : contrairement aux waypoints actuels (100 % client, `WaypointStore`), c'est du
  SERVEUR-autoritaire, cohérent avec la philosophie du mod : détection pose/casse côté serveur
  (`BlockEvent` + block entity `Nameable`), persistance dans le dossier monde, nouveau payload
  S2C (liste complète au login + broadcast à chaque changement), côté client une source
  volatile type `source = "banner"` resynchronisée à chaque session (le store ne persiste déjà
  que la source "user"). Rendu : icône bannière au lieu du losange `drawWaypointDiamond`._
- [ ] **P3 · ★★★☆☆ — Bouton boussole** sur l'interface : basculer l'orientation de la carte
  (nord fixe vs orientée joueur).
- [ ] **P3 · ★★★☆☆ — Écran de config intégré** au plein écran (plutôt que l'écran NeoForge)
  pour modifier rapidement les options.
- [ ] **P3 · ★★★☆☆ — Éditeur dédié pour les couches par dimension et les bandes CAVE** :
  aujourd'hui c'est du texte brut (`namespace:dimension=DAY,NIGHT,...`, liste d'entiers pour
  les bandes) dans l'écran NeoForge — compliqué à utiliser. Cible : un écran avec la liste des
  dimensions connues et des cases à cocher par couche (comme la modal « Dimensions » de JM),
  et un éditeur visuel des bandes CAVE (plages Y, ajout/suppression).
  _C'est de la config SERVEUR : l'édition depuis le client demande un payload dédié réservé
  aux ops (permission niveau 2+), avec re-broadcast des couches actives comme au reload.
  À intégrer au chantier « écran de config intégré »._
- [ ] **P4 · ★★☆☆☆ — Inventorier les options de la carte plein écran** : lister l'existant,
  identifier ce qui manque (vs JourneyMap) et l'ajouter.
- [ ] **P4 · ★★☆☆☆ — Inventorier les configs** (client, common, serveur) : voir ce qui
  mériterait d'être configurable et ne l'est pas.

## UI / UX

- [ ] **P3 · ★★★☆☆ — Asset dédié pour le marqueur du joueur** : remplacer le triangle vectoriel
  (`EntityDots.drawPlayerArrow`) par une image/texture dédiée (reprendre le style des assets
  JourneyMap ou en créer), sur la minimap et la carte plein écran.
- [ ] **P2 · ★★★★☆ — Rework de l'écran de création/édition de waypoint** : en faire un vrai
  screen structuré style JourneyMap (l'actuel est brouillon).
- [ ] **P3 · ★★★☆☆ — Rework du menu clic droit** : rendu propre style JourneyMap (panneau
  custom) au lieu de boutons Minecraft empilés.
- [ ] **P4 · ★★☆☆☆ — Grouper les boutons d'overlay** ensemble sur la carte plein écran
  (toggles RNS/trains/grille... dans un groupe visuel dédié).
- [ ] **P4 · ★★☆☆☆ — Passe sur les textes** : nettoyer/harmoniser les libellés existants.

## Qualité de code

- [ ] **P3 · ★★★☆☆ — Nettoyage** : responsabilité unique par fichier, documentation
  systématique, passer la doc et les commentaires de config en anglais, créer des
  utilitaires/globals, remplacer les chaînes répétées par des constantes statiques.
- [x] **P4 · ★★☆☆☆ — Configs client par section** — **fait** : `ClientConfig` est maintenant
  une façade qui assemble `MinimapClientConfig`, `RadarClientConfig`, `WaypointClientConfig`
  et `MapClientConfig` (clés TOML inchangées, pas de reset de config).
- [ ] **P4 · ★★☆☆☆ — Optimisation** : passe de perf générale (allocations par frame, réflexion
  dans les chemins chauds du bridge, caches).
- [ ] **P5 · ★★☆☆☆ — Rendu via shader** : JourneyMap dessine avec ses propres shaders (au sens
  draw) — étudier la même approche pour des rendus plus propres (formes, anti-aliasing).
- [ ] **P5 · ★☆☆☆☆ — Traductions** : vérifier les clés de config/UI manquantes et supprimer
  celles devenues inutiles.

## Ordre recommandé

Principe : quelques micro-fixes pour garder le jeu testable, puis le **clean code en premier
grand chantier** pour repartir d'une base saine — mais ciblé sur les parties STABLES : l'UI
(écrans, menus) sera nettoyée par son propre rework, inutile de la documenter avant de la
réécrire.

1. ~~**Micro-fixes testabilité** : marqueur du joueur local (P0 ★★★★★), couleur des beacons
   stable (P1 ★★★★☆), téléportation en vol (P2 ★★☆☆☆).~~ ✔ **fait** (l'asset dédié du
   marqueur suivra avec le chantier UI). On peut tester proprement pendant tout le refactor.
2. **Clean code — le socle** (P3 ★★★☆☆) : `common`, `server` (moteur, services, réseau),
   `client.service`, bridge JourneyMap. Responsabilité unique, utils, constantes, docs et
   commentaires de config en anglais, **configs par section** (P4). L'UI est EXCLUE de cette
   passe (voir 5). C'est aussi ici qu'on **cadre l'API publique** (P3 ★★★★☆) : frontière
   api/interne, façade waypoints, événements — les hooks de draw suivront avec les chantiers
   rendu/UI.
3. ~~**Couleurs de blocs (palette textures)** (P1 ★★★★★) — premier gros chantier feature, sur
   les renderers fraîchement nettoyés.~~ ✔ **fait** (`BlockPalette` + palette vanilla embarquée
   + `engine.blockColorOverrides` ; regénérer la palette via le générateur offline à chaque
   montée de version Minecraft).
4. **Infos de survol charge-proof** (P2 ★★★★☆) — le sidecar par région s'appuie sur le moteur
   nettoyé en 2 ; **lisibilité des labels de waypoints** (P2 ★★★☆☆) + **noms des gares/trains**
   (P2 ★★★☆☆).
5. **Chantier UI** : rework écran waypoint + écran de gestion des waypoints (P2 ★★★★☆), puis
   menu clic droit style JM (P3 ★★★☆☆) — les nouveaux écrans appliquent directement les
   conventions posées en 2, et remplacent l'ancien code UI non nettoyé.
6. **Petites features indépendantes** : bouton boussole (P3), têtes des mobs (P3),
   waypoints de bannière (P3 — premier vrai cas de waypoints poussés par le serveur, peut
   servir de brouillon à la sync waypoints).
7. **Écran de config intégré** (P3 ★★★☆☆) — s'appuie sur le chantier UI ; inclut l'éditeur
   des couches par dimension et des bandes CAVE (P3 ★★★☆☆, config serveur → payload op).
8. **Groupement des boutons d'overlay + passe textes + inventaires options/configs +
   intégrité du cache client (hash des régions)** (P4).
9. **Optimisation** (P4) — sur la base nettoyée et les nouveaux écrans.
10. **Shaders de rendu + audit des traductions** (P5).
