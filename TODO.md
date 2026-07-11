# TODO — Shared Journey

Priorités : **P0** (critique) → **P5** (plus tard). Valeur : ★☆☆☆☆ (faible plus-value) → ★★★★★ (forte plus-value).

Le chantier UI est **parqué** (décision : on y reviendra plus tard) — ses items sont regroupés
dans leur section et n'entrent pas dans l'ordre courant.

## Clean code du socle — fait

Étape 2 du plan d'origine (jamais faite, doublée par les features), lancée une fois l'UI
réécrite pour que la passe ne soit pas invalidée par un rework.

- [x] **P2 · ★★★★☆ — Classes dédiées traductions + constantes** — **fait**.
  `common.util.Lang` centralise toutes les clés i18n référencées en code (actions, contexte,
  légende, écrans waypoint, groupes...) — exclut volontairement les clés
  `sharedjourney.configuration.*` (dérivées automatiquement par NeoForge depuis le chemin TOML,
  jamais appelées directement). `client.gui.UiColors` centralise la palette ARGB du style
  panneau sombre partagée par `WaypointEditScreen`/`ModalScreen`/`ContextMenu` (background,
  bordure, surbrillance de ligne, texte) — élimine la duplication de couleurs entre ces trois
  écrans. `api.MapLayer#translationKey()` reste en dur (contrainte de layering : `api` ne peut
  pas dépendre de `common` ; ce n'est pas une duplication, c'est la source unique de vérité).
- [x] **P2 · ★★★★☆ — Nettoyage du socle** — **fait** : dernier commentaire français traduit
  (`FullMapScreen`), 4 Javadoc de classe manquants ajoutés (`MinimapClientConfig.Corner/Shape`,
  `ClientMapCache.Region/HoverInfo`), duplication exacte de record éliminée dans
  `RegenService.start()` (un `record RegionPos` local était identique au `MaskKey` de la
  classe). Audité et **volontairement laissés en l'état** (dans la tolérance du projet contre
  l'abstraction prématurée) : le boilerplate GZIP de `Payloads`/`HoverRegionData` (corps
  différents — string vs binaire structuré) et les helpers de réflexion des bridges JourneyMap/
  Create (peu de recouvrement réel, logiques spécifiques à chaque mod, fragiles à fusionner).
- [x] **P3 · ★★★☆☆ — Publier la façade API waypoints** — **fait** (fin de chantier, règle de
  la couture) : `api.WaypointApi` (CRUD minimal — all/forDimension/get/add/update/remove/
  isShown/groups — pas de gestion de groupes ni de session, YAGNI tant qu'un consommateur ne
  le demande pas), indirection `Hooks` statique câblée par `SharedJourneyClient` vers
  `WaypointStore` (même patron que `Payloads.Hooks`, car `api` ne peut référencer `client`).
  Les événements `WaypointEvent.Added/Updated/Removed` existaient déjà.
  _Reste à trancher séparément (non fait ici) : format NBT pour les index machine-only._

## Features (hors UI)

- [ ] **P3 · ★★★★☆ — Waypoints de bannière** : une bannière NOMMÉE (enclume) posée devient un
  marqueur partagé (minimap + plein écran, icône bannière vanilla
  `map/decorations/banner_<couleur>.png`, couleur suivie), retiré quand elle est cassée.
  _Coût en forte baisse : le pipeline serveur-autoritaire (persistance monde + broadcast +
  envoi complet au login + source client volatile) existe depuis les waypoints publics —
  détection pose/casse (`BlockEvent` + block entity `Nameable`) et une source `banner` à
  brancher dessus. Consommateur de validation prévu pour l'API `MapRenderEvent`._
- [ ] **P3 · ★★★☆☆ — Fuites d'information malgré « me cacher de la carte »** : un joueur caché
  « dessine » sa position par trois vecteurs — blocs cassés/posés (push en direct), nouveaux
  chunks générés (front d'exploration), déverrouillage des bandes CAVE (`CaveTracker`).
  _Design proposé : file de QUARANTAINE côté serveur qui gate la DIFFUSION (le rendu PNG se
  fait normalement). Attribution exacte impossible → heuristique de proximité (tout chunk
  dirty/nouveau/unlock à moins de N chunks d'un joueur caché part en quarantaine). Indexée
  par CHUNK (pas de propriétaire ; au drain on ré-évalue la proximité). Push immédiat pour
  le joueur caché et les joueurs assez proches (le jeu leur streame déjà la zone) ; différé
  pour les autres. Version « publique » par région distincte de la réelle (sinon le
  handshake de reconnexion contourne la quarantaine). Drain PROGRESSIF (5-15 min config) —
  un drain d'un coup redessinerait la trajectoire. Alternative radicale en option serveur :
  les joueurs cachés ne contribuent pas du tout à la carte._
- [ ] **P3 · ★★☆☆☆ — Suivi de train : lisser la caméra** (quick win) : le recentrage est sec
  (position recopiée chaque frame) ; interpoler la vue vers la position du train.
- [ ] **P3 · ★★★★☆ — Têtes de mobs sur le radar** : têtes à la place des points, sur la
  minimap et la carte plein écran. **Compatible mods par construction** : pas de jeu de
  sprites vanilla hardcodé — rendre la tête depuis le modèle/texture de l'entité elle-même
  (approche Xaero : le rendu marche pour n'importe quel mob moddé sans intégration), avec
  cache des icônes rendues par type d'entité et fallback point coloré si le rendu échoue.

## API publique (tranches restantes)

_Stratégie (cadrage fait) : pas d'API spéculative. Règle de la couture (les internals de
chaque chantier prévoient le point d'extension) + publication en FIN du chantier qui
stabilise le modèle._

- [x] **rendu écran (v1)** : `api.client.MapView` + `MapRenderEvent` (minimap + plein écran)
  + `FullMapScreenEvent.Opened/Closed` + `MapLayerChangedEvent`. Suite v2 : enregistrement
  d'icônes/marqueurs de haut niveau (clamping bordure fourni).
- [x] **waypoints (v1)** : `api.WaypointApi` — voir « Clean code du socle ». Suite possible :
  gestion des groupes (create/rename/delete), si un consommateur concret en a besoin.
- [ ] **rendu serveur (image de région)** : couture posée (`engine.blockColorOverrides` +
  `BlockPalette` point d'entrée unique) ; publier événement/registre quand un consommateur
  concret existe.
- [ ] **UI fullscreen** (boutons, menu contextuel, barre d'infos) — avec le chantier UI.
- [ ] **couches** : finaliser `LayerRegisterEvent` — bloqueur structurel : `RegionKey`/
  réseau/disque indexés sur l'enum `MapLayer`, passer à des ids libres est un chantier
  dédié. En attendant, documenter l'événement comme non câblé.
- [ ] **lecture/actions** (positions joueurs, état des régions, re-rendu) : YAGNI, à la
  demande d'un consommateur concret.

## Chantier UI (parqué — plus tard)

- [ ] **P3 · ★★★☆☆ — Icône in-world des waypoints (losange JM)** : losange à la position du
  waypoint, TOUJOURS visible (pas de cône de visée, contrairement au label), masqué hors des
  bornes `beaconMinDistance`/`beaconMaxDistance`. D'abord vectoriel, puis texture PNG
  (personnalisable par resource pack).
- [ ] **P3 · ★★★☆☆ — Asset dédié pour le marqueur du joueur** : remplacer le triangle
  vectoriel (`EntityDots.drawPlayerArrow`) par une texture (style JourneyMap), minimap +
  plein écran.
- [ ] **P3 · ★★★☆☆ — Bouton boussole** : basculer l'orientation de la carte (nord fixe vs
  orientée joueur).
- [ ] **P3 · ★★★☆☆ — Écran de config intégré** au plein écran (plutôt que l'écran NeoForge),
  incluant l'**éditeur des couches par dimension et des bandes CAVE** (aujourd'hui du texte
  brut ; cible : cases à cocher par dimension façon modal « Dimensions » de JM + éditeur
  visuel des plages Y). _Config SERVEUR : payload dédié réservé aux ops (niveau 2+) avec
  re-broadcast des couches actives. Dépend de la passe constantes/i18n._
- [ ] **P4 · ★★☆☆☆ — Grouper les boutons d'overlay** (toggles RNS/trains/grille...) dans un
  groupe visuel dédié de la carte plein écran.
- [ ] **P4 · ★★☆☆☆ — Passe sur les textes** : nettoyer/harmoniser les libellés.
- [ ] **P4 · ★★☆☆☆ — Inventorier les options de la carte plein écran** (vs JourneyMap) et
  **les configs** (client, common, serveur) : ce qui manque, ce qui devrait être configurable.

## Robustesse / perf (P4+)

- [ ] **P4 · ★★★☆☆ — Intégrité du cache client (images de régions)** : un PNG modifié
  localement n'est pas détecté (le handshake ne compare que les versions). _La sécurité
  vient du SERVEUR : hash SHA-256 calculé au rendu et stocké dans l'index serveur ; au
  handshake le client RECALCULE le hash de ses fichiers (pas son index, falsifiable) —
  même version + hash différent → re-push. En complément : métadonnées dans le PNG
  (chunk tEXt) pour un index client reconstructible. CRC32 insuffisant (forgeable)._
- [ ] **P4 · ★★★☆☆ — Overlay des rails Create en souterrain** (recherche) : l'overlay reste
  affiché en surface quand la voie est enterrée et n'apparaît pas sur les couches CAVE.
  Corréler les pixels du `TrainMapRenderer` avec la couche affichée et les hauteurs
  (sidecar INFO ?) pour masquer en surface et/ou afficher dans la bande CAVE.
- [ ] **P4 · ★★☆☆☆ — Optimisation** : allocations par frame, réflexion dans les chemins
  chauds des bridges, caches.
- [ ] **P5 · ★★☆☆☆ — Rendu via shader** : étudier l'approche JourneyMap (draw avec ses
  propres shaders) pour des formes plus propres (anti-aliasing).
- [ ] **P5 · ★☆☆☆☆ — Audit des traductions** : clés manquantes/mortes (sera largement
  couvert par la passe constantes/i18n).

## Ordre recommandé

1. ~~**Clean code du socle** (P2) : nettoyage + classes traductions/constantes, et en fin de
   chantier la **façade API waypoints** (P3).~~ ✔ **fait**.
2. **Waypoints de bannière** (P3 ★★★★☆) — s'appuie directement sur le pipeline des waypoints
   publics, et valide l'API de rendu écran. ← **prochain chantier**.
3. **Têtes de mobs sur le radar** (P3 ★★★★☆, compatible mods) + quick win : lissage de la
   caméra du suivi de train (P3 ★★☆☆☆).
4. **Fuites d'information** (P3) — gros morceau design (quarantaine), à lancer quand on veut
   un chantier serveur.
5. **Chantier UI** (quand déparqué) : losange in-world + marqueur joueur + boussole, puis
   écran de config intégré + éditeur couches/bandes, groupement des overlays, passe textes,
   inventaires, tranche API UI.
6. **Robustesse** : intégrité du cache (hash), rails souterrains.
7. **Optimisation** (P4), puis shaders + audit traductions (P5).

## Fait (résumé — détails dans l'historique git)

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
  préservé, contours intacts). _Limites ACCEPTÉES : la marche s'arrête à un embranchement
  sans décision, léger débordement possible aux croisements. **NE PAS retenter** le fallback
  « première option » de navigateOptions ni le filtre par distance au segment sans meilleure
  compréhension de l'orientation des TravellingPoints de Create._
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
