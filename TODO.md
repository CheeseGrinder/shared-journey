# TODO — Shared Journey

Priorités : **P0** (critique) → **P5** (plus tard). Valeur : ★☆☆☆☆ (faible plus-value) → ★★★★★ (forte plus-value).

## À faire

### Chantier UI — partie hors-icônes (reste)

- [ ] **P4 · ★★☆☆☆ — Passe sur les textes** : nettoyer/harmoniser les libellés (dont les
  unités des sliders de l'écran de config, aujourd'hui en anglais brut).

### Chantier UI — partie icônes (en attente des assets, pas encore fournis)

- [ ] **P3 · ★★★☆☆ — Icône in-world des waypoints (losange JM)** : losange à la position du
  waypoint, TOUJOURS visible (pas de cône de visée, contrairement au label), masqué hors des
  bornes `beaconMinDistance`/`beaconMaxDistance`. D'abord vectoriel, puis texture PNG
  (personnalisable par resource pack).
- [ ] **P3 · ★★★☆☆ — Asset dédié pour le marqueur du joueur** : remplacer le triangle
  vectoriel (`EntityDots.drawPlayerArrow`) par une texture (style JourneyMap), minimap +
  plein écran.

### API publique (tranches restantes)

_Stratégie (cadrage fait) : pas d'API spéculative. Règle de la couture (les internals de
chaque chantier prévoient le point d'extension) + publication en FIN du chantier qui
stabilise le modèle. Déjà publié : rendu écran v1 (`MapView`, `MapRenderEvent`,
`FullMapScreenEvent`, `MapLayerChangedEvent`) et waypoints v1 (`WaypointApi`)._

- [ ] **UI fullscreen** (boutons, menu contextuel, barre d'infos) — dernière tranche du
  chantier UI ; inclut la suite v2 du rendu écran (enregistrement d'icônes/marqueurs de
  haut niveau, clamping bordure fourni).
- [ ] **rendu serveur (image de région)** : couture posée (`engine.blockColorOverrides` +
  `BlockPalette` point d'entrée unique) ; publier événement/registre quand un consommateur
  concret existe.
- [ ] **couches** : finaliser `LayerRegisterEvent` — bloqueur structurel : `RegionKey`/
  réseau/disque indexés sur l'enum `MapLayer`, passer à des ids libres est un chantier
  dédié. En attendant, documenter l'événement comme non câblé.
- [ ] **lecture/actions** (positions joueurs, état des régions, re-rendu) : YAGNI, à la
  demande d'un consommateur concret.
- [ ] **waypoints v2** (YAGNI) : gestion des groupes (create/rename/delete) si un
  consommateur concret en a besoin ; format NBT pour les index machine-only à trancher.

### Robustesse / perf (P4+)

- [ ] **P4 · ★★★☆☆ — Overlay des rails Create en souterrain** (recherche) : l'overlay reste
  affiché en surface quand la voie est enterrée et n'apparaît pas sur les couches CAVE.
  Corréler les pixels du `TrainMapRenderer` avec la couche affichée et les hauteurs
  (sidecar INFO ?) pour masquer en surface et/ou afficher dans la bande CAVE.
- [ ] **P4 · ★★☆☆☆ — Optimisation** : allocations par frame, réflexion dans les chemins
  chauds des bridges, caches.
- [ ] **P5 · ★★☆☆☆ — Rendu via shader** : étudier l'approche JourneyMap (draw avec ses
  propres shaders) pour des formes plus propres (anti-aliasing).
- [ ] **P5 · ★☆☆☆☆ — Audit des traductions** : clés manquantes/mortes (sera largement
  couvert par la passe textes).

## Ordre recommandé

1. **Chantier UI hors-icônes** : passe textes, puis tranche **API UI**.
2. **Chantier UI icônes** dès que les assets sont fournis (losange in-world, marqueur joueur).
3. **Robustesse** : rails Create souterrains.
4. **Optimisation** (P4), puis shaders + audit traductions (P5).

## Fait (résumé — détails dans l'historique git)

### Chantiers récents (2026-07)

- **Chantier UI hors-icônes (2026-07-15)** :
  - **Boussole** : toggle de la barre du haut du plein écran, pilote
    `minimap.rotateWithPlayer`. Le plein écran lui-même reste nord fixe par construction
    (conversions écran↔monde et overlays bridgés le supposent) — le faire tourner serait
    un chantier dédié, non demandé.
  - **Groupe overlays** : 3e cluster de la barre du haut — grille, waypoints
    (`map.showWaypoints`, quick win §3.1 de l'inventaire), joueurs (`radar.showPlayers`,
    §3.2). Les overlays bridgés (trains Create, gisements RNS) gardent leurs propres
    toggles sur la carte ; leurs configs vivent dans l'onglet Addons de l'écran (retour
    utilisateur : pas de boutons doublons dans la barre). Le widget RNS, ancré par RNS au
    centre du bord gauche (en collision avec notre barre de gauche), est relogé en haut à
    côté du widget de Create : réflexion sur la constante `ToggleLocation.JOURNEY` de RNS
    (`JourneyMapFullscreenBridge.relocateRnsToggle`) — rendu ET hitbox de clic passent par
    la même constante, donc restent synchrones ; en cas d'échec le widget reste où RNS le
    met.
  - **Inventaire options/configs** : `docs/UI_OPTIONS_INVENTORY.md` (état des lieux, manques
    vs JourneyMap, décisions §5 tranchées le 2026-07-15).
  - **Écran de config intégré** : `MapSettingsScreen` (bouton engrenage, barre de gauche).
    Onglets client (Minimap/Radar/Carte/Waypoints + **Addons** — une section par mod
    bridgé : Create, RNS — visible seulement si un mod l'est) appliqués à chaud, fichier
    sauvé à la fermeture ; onglet **Serveur** réservé aux ops (niveau 2+) : éditeur des
    couches par dimension (cases façon modal « Dimensions » de JM), bandes CAVE (plage
    min/max), scalaires sync/waypoints/privacy — l'intervalle de sync est affiché en
    secondes (stocké en ticks). Tooltips descriptifs sur chaque réglage (réutilisent les
    clés `configuration.*.tooltip` de l'écran NeoForge ; les manquantes — sync, nouvelles
    configs — ajoutées en/fr, profitent aussi à l'écran NeoForge). Payloads
    `OpsConfigRequestPayload` (C2S) + `OpsConfigPayload` (S2C snapshot / C2S apply assaini
    côté serveur : permission re-vérifiée, valeurs bornées, persisté, re-broadcast des
    couches, écho autoritaire). Protocole réseau v8.
  - **Nouvelles configs** : `minimap.zoomDefault` (zoom minimap de début de session),
    `map.rememberLayer` (le plein écran rouvre sur sa dernière couche),
    `map.showWaypoints`. Reportées à la partie icônes/thème : gridColor/gridOpacity,
    opacité de la minimap.
- **Intégrité du cache client (2026-07-15)** : SHA-256 des octets
  exactement servis enregistré côté serveur (`hashes.json`, alimenté aux points
  d'encodage, jamais paresseux) ; hash RECALCULÉ côté client au handshake depuis les
  fichiers (jamais lu d'un index falsifiable), format `clé=version:hash`, protocole v7 ;
  même version + hash différent = fichier trafiqué → non seedé → re-push + warn. La
  variante publique (quarantaine) n'est pas suivie : fallback comparaison de versions.
  _Non fait (YAGNI) : métadonnées tEXt dans le PNG pour un index reconstructible._
- **Fuites d'information — quarantaine à la diffusion (2026-07-15)** :
  gate à la DIFFUSION (pas au rendu) au point unique de résolution de chunk
  (`MapManager.tick`/`renderNow` → `QuarantineService.evaluate` : RENDER/QUARANTINE/DROP).
  Proximité ≤ `quarantineRadiusChunks` d'un joueur caché = quarantaine quel qu'en soit
  l'auteur ; deadline `quarantineDrainMinutes` avec jitter ±25 % (un drain ordonné
  rejouerait la trajectoire), réarmée si activité/joueur caché encore proche ; persistance
  `quarantine.json`. Variante « publique » par région (pixels + sidecar INFO figés
  pré-quarantaine, version publique DISTINCTE — sinon le handshake contourne), sidecars
  `.pub.png`/`.pub.bin` ; joueurs proches (`isTrusted`) reçoivent le réel, les autres la
  publique — client inchangé. Config `privacy` : OFF / **QUARANTINE (défaut)** / EXCLUDE,
  rayon **toujours planché à la view distance serveur + 1** (sinon anneau peint avec trou
  révélateur). _Limites acceptées documentées dans le Javadoc de `QuarantineService`
  (trust à la région entière ; crash = sidecars .pub perdus → révélation anticipée ;
  bannières d'un joueur caché broadcast normalement)._
- **Têtes de mobs sur le radar (v3, validé en jeu le 2026-07-15)** : render-to-texture +
  atlas (`MobIconCreator` : FBO 256² supersamplé 8×, vrai depth test, contour par
  dilatation de silhouette ; `MobIconAtlas` : cellules 32 px ; dessin par frame = un quad),
  cache par `(EntityType, texture)` (variantes chevaux/chats gratuites), budget 1 icône/
  15 ms, zéro mixin, fallback corps entier puis point coloré. Heuristiques de résolution/
  cadrage de tête compatibles mods par construction (`HeadedModel`/`headParts`/scan
  réflexif, union des gros cubes du tiers avant). _Limites acceptées : couches de rendu
  (laine du mouton...) non dessinées, bounds cachés par TYPE, pose de référence fixe._
  _Historique : V1 via `InventoryScreen` revertée le 2026-07-11 (bobbing, crop fixe) ;
  v2 rendu direct 9 passes/entité/frame remplacée par la v3 ; détails des rounds dans
  l'historique git._
- **Waypoints de bannière** : une bannière NOMMÉE posée par un joueur devient un marqueur
  partagé, retiré quand elle est cassée. Détection serveur (`BannerWaypointEvents`),
  persistance world-shared + broadcast **S2C uniquement** (`BannerWaypointService`, id
  déterministe dim+pos, protocole v6). Côté client : `SOURCE_BANNER`, groupe réservé
  `GROUP_BANNERS`, type PUBLIC — pipeline waypoint existant. Icône = forme exacte des
  décorations de carte vanilla (5 `gg.fill`), couleurs = table exacte des 16 textures (pas
  `DyeColor#getTextureDiffuseColor`, formule du cuir). Lecture seule (ni Edit ni Delete ;
  TP + visibilité locale OK). _Fixes connexes : routes serveur gatées sur `SOURCE_USER`
  (les waypoints bridgés type Waystones ne partent plus vers `PlayerWaypointService`),
  `renameGroup` repasse par `update()`, visibilité bannière via `HIDDEN_IDS`
  (`WaypointStore.updateBanner`), double-clic bannière n'ouvre plus l'édition._
  _Limite YAGNI : bannière nommée placée par worldgen/commande/mod (hors
  `EntityPlaceEvent`) non détectée._
- **Config — sections + waypoints serveur-autoritaires** : configs client `map`/`waypoints`
  passées en sections `push()`/`pop()` (alignées sur minimap/radar ; anciennes valeurs
  top-level perdues, accepté). Section serveur `waypoints` : `deathWaypointsEnabled`
  (coupe-circuit admin, poussé via `LayerSettingsPayload`) et `waypointStorage`
  (SERVER/CLIENT, **défaut SERVER**) — les waypoints DIMENSION du joueur sont persistés
  côté serveur, un fichier par joueur, jamais partagés, sync point-à-point
  (`PlayerWaypointService`, protocole v5). Portée limitée aux DIMENSION (décision
  utilisateur) : TEMP toujours locaux, PUBLIC toujours serveur. Pas de migration des
  anciens `waypoints.json` locaux. Transitions de type DIMENSION↔PUBLIC↔TEMP nettoient la
  copie serveur orpheline dans les deux sens.
- **Clean code du socle** : `common.util.Lang` (toutes les clés i18n référencées en code ;
  les clés `sharedjourney.configuration.*` de NeoForge exclues volontairement),
  `client.gui.UiColors` (palette du style panneau sombre), dernier français traduit,
  Javadoc manquants, record dupliqué éliminé (`RegenService`). Audités et laissés en
  l'état (anti-abstraction prématurée) : boilerplate GZIP `Payloads`/`HoverRegionData`,
  helpers de réflexion des bridges. Façade **`api.WaypointApi`** publiée (CRUD minimal,
  indirection `Hooks` câblée par `SharedJourneyClient`, même patron que `Payloads.Hooks`).

### Bugs marquants

- **Éclairage non propagé aux chunks voisins (2026-07-16)** : poser une
  lumière près d'une frontière de chunk ne rafraîchissait que le chunk modifié — la
  lumière porte jusqu'à 15 blocs et traverse les frontières (ombrage NIGHT/CAVE), les
  voisins restaient sombres. `ChunkEvents.markDirty` (break/place/neighborNotify) et les
  explosions enfilent maintenant le voisinage 3×3. Pour que ce ×9 ne spamme pas le réseau
  (NeighborNotify part en rafale — horloges redstone), garde « inchangé » posée au write :
  `writeChunk` compare les pixels entrants au contenu de la région et
  `writeHoverChunk`/`HoverRegionData.chunkEquals` comparent le sidecar INFO — identique =
  pas de bump de version, pas de re-push (bénéficie aussi au regen et aux re-rendus
  quelconques qui ne changent rien).
- **Lignes de pixels décalées aux frontières de régions (2026-07-15)** :
  problème de RENDU, pas de données — wrap GL par défaut GL_REPEAT sur les
  `DynamicTexture` de régions : en bord de quad sous transform fractionnaire,
  l'échantillonnage tombe parfois pile sur u/v = 1.0 et wrappe sur la rangée/colonne
  opposée. Corrigé : `CLAMP_TO_EDGE` à l'upload (`ClientMapCache.uploadTexture`).
  _Durcissement connexe : écritures disque atomiques partout
  (`RegionStorage.writeAtomically`) + writer client MONO-thread dans `DiskCache` (avant :
  multi-thread sans ordre + write non atomique = corruption masquée à jamais par l'index)._
- **BUG P0 — mélange de maps entre mondes solo homonymes** : la clé du cache client était
  le nom `level.dat` (identique entre « New World » et « New World (1) ») → régions/index
  mélangés et handshake mensonger. Corrigé : clé = nom du DOSSIER du monde
  (`getWorldPath(LevelResource.ROOT)`), unique par construction. Pas de migration : le
  serveur est autoritaire, le cache se re-télécharge.

### Socle (plus ancien)

- **API rendu écran (v1)** : `api.client.MapView` + `MapRenderEvent` (minimap + plein
  écran) + `FullMapScreenEvent.Opened/Closed` + `MapLayerChangedEvent`.
- **Marqueur du joueur local** : winding des triangles corrigé + `disableCull` (l'asset
  texture suivra avec le chantier UI).
- **Couleur des beacons bridgés stable** : dérivée de l'identité du waypoint (modId +
  dimension + position), plus de couleur aléatoire par session.
- **Couleurs de blocs (palette textures)** : `BlockPalette` — overrides config → palette
  vanilla embarquée (générée offline par `tools/PaletteGenerator`, **à régénérer à chaque
  montée de version Minecraft**) → extraction runtime des jars de mods → fallback
  `MapColor`. Feuilles : règle générique grayscale = teinte biome, jamais d'espèce
  hardcodée ; un match par TAG de `hiddenBlocks` ne cache jamais des feuilles ; la descente
  traverse l'air.
- **Téléportation depuis la carte** : `/sj tp <x> <z>`, Y calculé serveur (heightmap, scan
  sous plafond Nether).
- **Infos de survol charge-proof** : sidecar `HoverRegionData` porté par la pseudo-couche
  `MapLayer.INFO` (réutilise tout le pipeline région : index, handshake, delta sync, cache
  disque). Survol 100 % local, **anti-exploit timing par construction** (plus de requête
  dépendante du chargement d'un chunk). INFO est verrouillé hors affichage (config,
  commandes, cycle client). Protocole réseau v2.
- **Labels de waypoints lisibles** : fond émis dans un buffer séparé
  (`textBackgroundSeeThrough`) — le tri par distance de `drawInBatch` grisait la moitié des
  glyphes ; label centré, affiché dans un cône de visée ~12°, clampé + boost de taille à
  distance.
- **Noms des gares/trains** : bug DANS Create (pick relatif au centre sans « + mapCenter ») ;
  rendu rejoué par le bridge avec pick corrigé + tooltips.
- **Path du train survolé + suivi au clic** : route simulée côté serveur
  (`CreateTrainPathService.walkRoute` — `currentPath` de Create ne contient que les
  décisions d'embranchement), repeinte en doré sur les pixels de rail de Create (relief
  préservé, contours intacts). Suivi caméra lissé (exponentiel, 0.15/frame). _Limites
  ACCEPTÉES : la marche s'arrête à un embranchement sans décision, léger débordement
  possible aux croisements. **NE PAS retenter** le fallback « première option » de
  navigateOptions ni le filtre par distance au segment sans meilleure compréhension de
  l'orientation des TravellingPoints de Create._
- **Overlay de progression du regen** : chunks pas encore re-rendus voilés en violet,
  granularité chunk (masques 1024 bits par région, ~1×/s).
- **Écran de gestion des waypoints** (design v3) : deux panneaux façon JM (groupes à gauche
  avec « Tous », sélection dorée, checkbox de visibilité, compteurs live ; waypoints à
  droite avec « + Nouveau waypoint » en dernière ligne), groupes gérés (créer/renommer/
  supprimer, réservés protégés), modals stylés (`ModalScreen`), raccourcis U (manager) et
  B (créer à la position du joueur).
- **Écran de création/édition** : formulaire structuré (nom, X/Y/Z, groupe avec
  autocomplétion — un nouveau nom crée le groupe, couleur hex + palette + picker HSB +
  aléatoire, visibilité, type), Entrée = sauvegarder.
- **Menu clic droit custom** (`ContextMenu`) : panneau plat sombre, survol, en-tête
  coordonnées, sous-menu ancré ; Échap ferme ; release du clic consommé avalé.
- **Waypoints publics** (ex-GLOBAL) : partagés via le serveur (`PublicWaypointService`,
  persistés dans le monde, broadcast + envoi complet au login, protocole v3) ; `visible`
  reste un choix local (`hiddenIds`) ; migration des anciens GLOBAL en DIMENSION ; death
  waypoints automatiques (groupe `deaths`, config `deathWaypoints`).
- **Configs client par section** : `ClientConfig` façade de `MinimapClientConfig`,
  `RadarClientConfig`, `WaypointClientConfig`, `MapClientConfig` (clés TOML inchangées).
