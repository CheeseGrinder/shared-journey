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

## Config — organisation + waypoints serveur-autoritaires — fait

- [x] **P3 · ★★★☆☆ — Sections de la config client** — **fait** : `MapClientConfig` et
  `WaypointClientConfig` étaient en clés top-level (« gardées ainsi pour compatibilité ») alors
  que `MinimapClientConfig`/`RadarClientConfig` utilisaient déjà des sections `push()`/`pop()` —
  incohérence source de la confusion. Les deux passent maintenant en sections `map`/`waypoints`
  (nouvelles clés de lang `sharedjourney.configuration.map`/`.waypoints` + `.tooltip`/`.button`),
  alignées sur le patron existant. Écran de config généré par NeoForge maintenant groupé de
  façon cohérente. _Casse potentielle : les valeurs déjà sauvegardées sous les anciennes clés
  top-level de ces deux sections reviennent à leur défaut (chemin TOML changé) — accepté, le
  mod n'est pas encore utilisé._
- [x] **P3 · ★★★★☆ — Waypoints des joueurs : stockage local ou serveur (config serveur)** —
  **fait**. Nouvelle section serveur `WaypointServerConfig` (« waypoints ») :
  - `deathWaypointsEnabled` (def. true) : coupe-circuit admin pour les waypoints de mort,
    en plus du réglage client existant (les deux doivent être vrais). Poussé au client via
    `LayerSettingsPayload` (même canal que `radarMaxRadius`, envoyé au login et à chaque reload).
  - `waypointStorage` (enum SERVER/CLIENT, **défaut SERVER**) : les waypoints DIMENSION
    (persistants, privés) du joueur sont stockés côté serveur — un fichier par joueur
    (`<monde>/data/sharedjourney/waypoints/<uuid>.json`, jamais partagé, jamais broadcast),
    synchronisé uniquement à son propriétaire au login et à chaque édition
    (`PlayerWaypointService`, payloads bidirectionnels `PlayerWaypointPayload`/`Remove`,
    même patron que les waypoints publics mais point-à-point). CLIENT = comportement
    précédent (100 % `WaypointStore` local). Portée du toggle **volontairement limitée aux
    DIMENSION** (décision utilisateur) : les TEMP restent toujours locaux (éphémères,
    détection « atteint » déjà client-side à chaque tick) ; les PUBLIC passent toujours par
    le serveur quel que soit ce réglage. Pas de migration automatique des anciens
    `waypoints.json` locaux (décision utilisateur : mod pas encore utilisé).
  - Câblage : `PlayerWaypointService` (init/shutdown sur le cycle de vie serveur, `sendAllTo`
    au login), `ClientMapCache.serverManagesWaypoints` (reçu via `LayerSettingsPayload`),
    `WaypointStore` route `add`/`update`/`remove` selon `isServerManaged(wp)`
    (`type == DIMENSION && serverManagesWaypoints`), avec gestion complète des transitions de
    TYPE via le bouton cycle de l'écran d'édition (DIMENSION↔PUBLIC↔TEMP) : promotion/
    démotion nettoient bien la copie serveur orpheline dans les deux sens (bug détecté et
    corrigé en cours de dev — la première version laissait des copies fantômes). `visible`
    reste un choix 100 % local (`HIDDEN_IDS`, réutilisé de PUBLIC) ; le `source` des waypoints
    gérés serveur reste `SOURCE_USER` (pas de nouvelle constante) pour que leurs groupes
    restent éditables par l'utilisateur (`isEditableGroup`). Fix connexe :
    `WaypointStore.renameGroup` faisait un `WAYPOINTS.put` direct au lieu de passer par
    `update()`, ce qui aurait laissé le serveur avec l'ancien nom de groupe pour les waypoints
    gérés serveur — corrigé pour repasser par `update()`. Protocole réseau v5.

## Features (hors UI)

- [x] **P3 · ★★★★☆ — Waypoints de bannière** — **fait**. Une bannière NOMMÉE (renommée à
  l'enclume AVANT d'être posée — un bloc déjà posé ne peut pas être renommé) devient un
  marqueur partagé, retiré quand elle est cassée. Détection serveur (`BannerWaypointEvents`) :
  `BlockEvent.EntityPlaceEvent` + `AbstractBannerBlock` + block entity `Nameable#hasCustomName`
  pour la pose, `BlockEvent.BreakEvent` pour la casse. Persistance + broadcast :
  `BannerWaypointService`, world-shared (`<monde>/data/sharedjourney/waypoints_banners.json`),
  id déterministe `UUID.nameUUIDFromBytes(dim+pos)` (stable aux redémarrages, insensible aux
  renommages en amont). **S2C uniquement** (pas de C2S) : contrairement aux waypoints publics/
  joueurs, aucun client ne demande de upsert/suppression — le serveur est seul détecteur.
  Nouveaux payloads `BannerWaypointPayload`/`Remove` (protocole v6). Côté client : source
  réservée `SOURCE_BANNER`, groupe réservé `GROUP_BANNERS`, type `PUBLIC` (sémantiquement
  correct : partagé, visibilité par client) — routage/persistance 100 % gratuits via le
  pipeline waypoint existant (minimap, plein écran, gestionnaire).
  _Icône et couleur (retour utilisateur post-V1) : le losange générique ne correspondait pas
  à l'icône bannière de Minecraft sur une carte tenue en main. Forme exacte tracée pixel par
  pixel depuis `assets/minecraft/textures/map/decorations/<couleur>_banner.png` (extrait du
  jar client via le cache Gradle NeoFormRuntime) — identique pour toutes les couleurs (barre
  noire du haut, bordures, tige du bas), seul le remplissage change — reproduite en 5 appels
  `gg.fill()` dans `EntityDots#drawBannerIcon`, utilisée à la place de `drawWaypointDiamond`
  pour les waypoints `SOURCE_BANNER` (minimap + plein écran). Couleur : `DyeColor#
  getTextureDiffuseColor()` est la formule de teinture du CUIR, pas du tissu de bannière —
  proche mais pas identique (ex. rouge `0xB3312C` vs `0xB02E26` réel) ; remplacé par une table
  de correspondance exacte des 16 couleurs extraite des mêmes textures
  (`BannerWaypointEvents#mapIconColor`).
  _Lecture seule (retour utilisateur post-V1) : ni Edit ni Delete dans le gestionnaire pour
  un waypoint `SOURCE_BANNER` — position/nom/couleur/groupe sont dérivés de la bannière
  physique, seule la casser dans le monde le retire. Le bouton TP et le toggle de visibilité
  (afficher/masquer, choix 100 % local) restent disponibles. Le groupe « Banners » lui-même
  n'est déjà pas supprimable/renommable (`isEditableGroup`). La ligne affiche un tag « Banner »
  (`Lang.WAYPOINT_TYPE_BANNER`) en plus du groupe, puisque le type interne reste `PUBLIC`
  (routage) mais la nature affichée à l'utilisateur doit être « Banner ». Bug de persistance
  corrigé au passage : le toggle de visibilité passait par le chemin `WAYPOINTS.put` direct
  au lieu de `HIDDEN_IDS` — fonctionnait dans la session mais revenait visible à la
  reconnexion suivante (`acceptBannerUpsert` recalcule `visible` depuis `HIDDEN_IDS`, jamais
  mis à jour par ce chemin) ; nouveau `WaypointStore.updateBanner` route bien via `HIDDEN_IDS`.
  Le double-clic sur un waypoint bannière dans la carte plein écran (`FullMapScreen`)
  ouvrait aussi le formulaire d'édition, même chemin bloqué désormais.
  _Bug corrigé au passage (détecté en concevant le routage, pas encore en prod) :
  `WaypointStore.add/update/remove` routait vers les canaux serveur PUBLIC/joueur en ne
  vérifiant que `type()`, pas `source()` — un waypoint BRIDGÉ (ex. Waystones via
  `JourneyMapBridge`, qui appelle `WaypointStore.add/update` directement avec `source=modId`,
  `type=DIMENSION`) aurait été poussé à tort vers `PlayerWaypointService` dès que
  `waypointStorage=SERVER` (le défaut). Toutes les routes serveur sont maintenant gatées sur
  `SOURCE_USER` (`isUserPublic`/`isServerManaged`)._
  _Limite connue (YAGNI, non gérée) : une bannière nommée placée par la worldgen (structure de
  village de pillards, manoir des bois...) ou par une commande/un autre mod sans passer par
  `EntityPlaceEvent` n'est pas détectée — seule la pose PAR UN JOUEUR l'est._
- [x] **P3 · ★★★☆☆ — Fuites d'information malgré « me cacher de la carte »** — **fait
  (2026-07-15 — à valider en jeu)**, design de quarantaine du cadrage implémenté tel quel.
  Un joueur caché « dessinait » sa position par trois vecteurs — blocs cassés/posés (push en
  direct), nouveaux chunks générés (front d'exploration), déverrouillage des bandes CAVE
  (`CaveTracker`). Le gate s'applique à la DIFFUSION, pas au rendu, en un point unique :
  tous les chemins de rendu (exploration, dirty, unlock CAVE, regen) convergent vers la
  résolution de chunk sur le main thread (`MapManager.tick`/`renderNow`) → verdict
  `QuarantineService.evaluate` (RENDER/QUARANTINE/DROP), positions joueurs lisibles.
  - **`QuarantineService`** (server.service) : `PENDING` chunk → deadline + index secondaire
    par région. Heuristique de proximité (attribution impossible) : tout chunk résolu à
    ≤ `quarantineRadiusChunks` d'un joueur caché part en quarantaine, quel qu'en soit
    l'auteur. Drain par chunk toutes les 5 s (cap 64/scan), deadline
    `quarantineDrainMinutes` **avec jitter ±25 %** (un drain ordonné rejouerait la
    trajectoire, juste différée) ; au drain, proximité ré-évaluée (joueur caché encore
    proche → réarmé) ; nouvelle activité dans un chunk pending réarme aussi. Persistance
    `quarantine.json` (à côté d'index.json), restaurée au démarrage.
  - **Variante « publique » par région** (`MapManager`) : au premier write quarantainé,
    clone des pixels (et du sidecar hover INFO — les hauteurs/blocs fuient aussi) figé
    pré-quarantaine, avec **version publique distincte** (sinon le handshake de reconnexion
    contourne la quarantaine). Writes normaux dans la même région : appliqués aux deux
    (miroir). Drain d'un chunk : copie rect réel→public toutes couches + INFO
    (`HoverRegionData.copyChunkTo`) + bump version publique ; dernier chunk de la région :
    variante droppée (public == réel) + **bump de la version réelle** (des versions
    réelle/publique nées la même milliseconde empêcheraient le re-push). Persistance
    sidecars `region_X_Z.pub.png`/`.pub.bin` (mtime = version publique), rechargés au
    démarrage, supprimés si orphelins.
  - **Service par joueur** (`SyncService`) : `isTrusted` (proximité d'un chunk pending de la
    région) décide la variante dans `maybeQueue`/`drainQueue`/`handleRegionRequest` — le
    joueur caché et les joueurs proches reçoivent le réel immédiatement (le jeu leur streame
    déjà la zone), les autres la publique. Trust ré-évalué à l'envoi ; un désaccord queue/
    envoi s'auto-corrige au delta suivant. Client inchangé (aucun nouveau payload, pas de
    bump protocole).
  - **Config serveur** section `privacy` (`PrivacyServerConfig`) : `hiddenAreaPolicy`
    OFF / **QUARANTINE (défaut)** / EXCLUDE (radical : chunks près d'un joueur caché pas
    rendus du tout — la zone se met à jour plus tard via un joueur visible),
    `quarantineRadiusChunks` (déf. 8, **toujours planché à la view distance serveur + 1** —
    détecté au premier test in-game : le front d'exploration génère et diffuse jusqu'à la
    view distance, un rayon plus petit diffusait un anneau peint avec un trou révélateur
    pile sur le joueur caché), `quarantineDrainMinutes` (déf. 10). Compteur dans
    `/sj stats` (ligne engine). Clés lang en/fr.
  _Limites ACCEPTÉES (documentées dans le Javadoc de `QuarantineService`) : (1) trust à la
  région entière dès qu'on est proche d'UN chunk pending — deux spots quarantainés distincts
  dans la même région se cross-révèlent aux joueurs collés à l'un des deux (tout est à
  < 512 blocs de toute façon ; exiger la proximité de TOUS priverait le joueur caché des
  mises à jour de sa propre traînée) ; (2) crash avant une sauvegarde monde = sidecars .pub
  perdus → fallback réel (révélation anticipée, pas de casse) ; (3) les waypoints de
  bannière posés par un joueur caché sont broadcast normalement (hors scope)._
- [x] **P3 · ★★☆☆☆ — Suivi de train : lisser la caméra** — **fait** (quick win) : lissage
  exponentiel (`centerX/Z += (train - center) * 0.15` par frame) au lieu de la recopie
  directe, dans `FullMapScreen.render`.
- [x] **P3 · ★★★★☆ — Têtes de mobs sur le radar** — **fait (v3, 2026-07-15 — validé en
  jeu)** : têtes à la place des points, minimap + carte plein écran, **compatible mods par
  construction** (aucun jeu de sprites hardcodé). `client.render.MobHeadIcons` (+
  `MobIconCreator`, `MobIconAtlas`) :
  - **Tête isolée, pas de crop** : on rend uniquement la `ModelPart` tête du modèle de
    l'entité, localisée via `HeadedModel#getHead` (humanoïdes, villageois...), puis
    `AgeableListModel#headParts` (quadrupèdes — méthode protected, accès par réflexion,
    mappings officiels donc stable en prod), puis
    `HierarchicalModel#getAnyDescendantWithName("head")` (creeper, warden...). Cadrage
    automatique depuis les bounds réels des cubes de la part (`ModelPart#visit`, coins
    transformés par la pose) — règle le « crop fixe qui ne colle pas aux proportions » de
    la V1.
  - **Pas de bobbing** : pose neutre re-figée avant chaque draw (`setupAnim` avec tous les
    paramètres à zéro sur le modèle partagé, `young`/`riding`/`attackTime` remis à zéro).
  - **Rendu direct en espace GUI** (même principe que `InventoryScreen.
    renderEntityInInventory` : pas de framebuffer, pas de capture/readback), vue de face,
    Z écrasé (×0.001) pour que la géométrie écrive la profondeur du plan GUI (pas de trous
    dans les draws suivants) — la visibilité des faces vient du back-face culling de
    `RenderType.entityCutout` (X inversé avec Z pour garder le winding correct), pas du
    depth buffer. Résultat : icône plate face caméra, style Xaero.
  - **Fallbacks** : mob sans tête identifiable (abeille : `headParts()` vide — c'était le
    cas « méconnaissable » de la V1) → rendu corps entier de face auto-cadré ; résolution/
    rendu en échec → point coloré existant (`EntityDots.draw`), type empoisonné en cache
    pour la session. Cache par `EntityType`, invalidé si l'instance de modèle change
    (resource reload).
  - Sur la minimap, contre-transformation locale (anti-rotation + 1/zoom) : icône droite et
    à taille d'écran constante (8 px) quel que soit le zoom/mode rotation. Config client
    `radar.mobHeads` (défaut true) + clés lang en/fr.
  _Limites acceptées : couches de rendu non dessinées (laine du mouton, collier...) — base
  model + texture de base seulement ; variantes par texture (chat, cheval) correctes car la
  texture est résolue par entité à chaque frame, mais les bounds sont cachés par TYPE (première
  entité vue) ; icône = pose de référence, pas d'orientation live (voulu)._
  _Round 2 (retours in-game du 2026-07-15) : mobs invisibles (golems, poissons, squid,
  tadpole) = trou de classification dans `EntityDots.colorFor` (ni `Animal` ni `Enemy`) —
  dernière branche passée en attrape-tout `Mob`. Cou rendu avec la tête (chevaux, camel...) :
  les parts « head » vanilla contiennent le cou dans leurs propres cubes — cadrage remplacé
  par l'union des GROS cubes du TIERS AVANT de la part (le visage est devant), cubes plats
  (ailes) et petits (cornes, antennes) exclus du cadre. Débordements (piques du guardian,
  cou résiduel, oreilles) coupés par un scissor à la boîte de l'icône. Lama (`EntityModel`
  brut) : stratégie supplémentaire de scan réflexif des champs `ModelPart` nommés « head » ;
  wither (`center_head`) : re-scan des noms de parts contenant « head ». Tête minuscule
  (parrot, frog, poissons) → rendu corps entier ; forme bien plus profonde que large
  (poissons, tadpole) → vue de PROFIL (rotation 90° avant l'écrasement Z). Bee agrandie
  gratuitement (les ailes plates ne comptent plus dans le cadre). Taille d'icône 8 → 10 px._
  _Round 3 (analyse comparative du code décompilé de Xaero, 2026-07-15) : adopté — contour
  noir 1 px par dilatation de silhouette (8 passes géométrie décalées d'1 px teintées en noir
  via le paramètre `color` de `ModelPart.render`, puis passe normale par-dessus ; équivalent
  de la dilatation alpha de Xaero mais sans framebuffer, le scissor est élargi d'1 px) et
  `HumanoidModel` → head + hat (la couche « hat » des zombies/squelettes était perdue via
  `HeadedModel#getHead` seul). Rejeté sciemment : render-to-texture + atlas + budget
  1 icône/frame (nécessaire chez Xaero car leur génération est chère ; notre rendu live est
  trivial et résout la texture PAR ENTITÉ à chaque frame → variantes gratuites, sans clés de
  cache par variante/armure), tracing mixin des RenderTypes/layers réels (lourd ; laine du
  mouton = limite acceptée), table de tweaks par mob codés en dur + overrides JSON (contre la
  philosophie « compatible par construction » ; nos heuristiques couvrent ces cas)._
  _Round 4 (v3, 2026-07-15) : migration vers le **render-to-texture + atlas** finalement
  (décision revenant sur le rejet du round 3, sur la base du rapport
  `assets/RAPPORT_RENDU_TETES_MOBS.md`) : le rendu v2 coûtait 9 passes de géométrie + un
  `flush`/scissor **par entité et par frame**. Désormais chaque icône est rendue UNE fois
  off-screen (`MobIconCreator` : FBO 256² supersamplé 8×, projection ortho, vrai depth test,
  mipmaps pour le downscale, contour par dilatation de silhouette composé directement dans la
  cellule) puis copiée dans un atlas GL (`MobIconAtlas`, cellules 32 px, pages ≤ 1024²) ; le
  dessin par frame = un quad texturé. Cache par `(EntityType, texture)` — les variantes par
  texture (chevaux, chats) restent gratuites —, budget de création 1 icône / 15 ms (point
  coloré en attendant), sentinelle FAILED, reset complet (cache + atlas) au premier modèle
  périmé après un resource reload. Heuristiques de cadrage/résolution de tête de la v2
  conservées telles quelles (validées en jeu) ; toujours **zéro mixin** : pas de tracing des
  layers, fidélité inchangée (texture de base via `RenderType.entityCutout`). Restauration
  scrupuleuse de l'état GL (projection + vertex sorting, modelview, framebuffer, viewport,
  scissor, blend, depth, lights) — checklist §1.6 du rapport._
  _Historique (V1 abandonnée le 2026-07-11) : implémentation via `InventoryScreen`
  (`renderEntityInInventoryFollowsAngle` puis `renderEntityInInventory` directement, avec un
  crop/zoom sur le top ~32% de la bounding box). Illisible en jeu — bobbing de l'animation de
  marche, épaules visibles (crop fixe), mobs non-humanoïdes méconnaissables — entièrement
  revertée. La V2 ci-dessus adresse ces trois causes une par une._

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

- [x] **P4 · ★★★☆☆ — Intégrité du cache client (images de régions)** — **fait
  (2026-07-15, à valider en jeu)**, design implémenté tel que cadré (hash SHA-256, la
  sécurité vient du serveur ; CRC32 rejeté car forgeable) :
  - **Serveur** : registre `RegionHashes` (`hashes.json` à côté d'`index.json`), paires
    `(version, sha256)` des octets EXACTEMENT servis (PNG des couches + blobs INFO —
    même pipeline, même vérif), alimenté aux points d'encodage (`dataOf`/`hoverBlobOf`/
    `saveAll`) — jamais de calcul paresseux au handshake (chargerait toutes les régions
    en RAM). Hash toujours apparié à SA version : pas de comparaison périmée ; une
    région pré-feature sans hash enregistré retombe sur la comparaison de versions et
    se fait couvrir au prochain push/save.
  - **Client** : au handshake, le hash est RECALCULÉ depuis les fichiers du cache
    (jamais lu d'un index, falsifiable avec le fichier), en asynchrone sur le thread
    writer du `DiskCache` (ordre garanti vis-à-vis des `store()`) ; fichier manquant/
    illisible = entrée non déclarée → re-push (couvre aussi la suppression manuelle).
    Format handshake `clé=version:hash`, protocole réseau v7.
  - **Serveur, à la réception** : même version déclarée + hash différent = fichier
    trafiqué → entrée non seedée dans `sentVersions` → le delta re-pousse les octets
    autoritaires. Log warn avec le compte par joueur. Variante publique (quarantaine)
    non suivie par le registre : un joueur non-trusted retombe sur la comparaison de
    versions (la quarantaine est temporaire, la vérif reprend au drain).
  _Non fait (complément optionnel du cadrage) : métadonnées tEXt dans le PNG pour un
  index client reconstructible — YAGNI tant que la perte d'index reste un simple
  re-téléchargement ; le serveur reste autoritaire._
  _NB : la corruption ACCIDENTELLE du cache est prévenue depuis 2026-07-15 (écritures
  atomiques + writer mono-thread, voir le résumé Fait) ; cette passe couvre la
  falsification volontaire._
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
2. ~~**Waypoints de bannière** (P3 ★★★★☆) — s'appuie directement sur le pipeline des waypoints
   publics/joueurs.~~ ✔ **fait**.
3. ~~**Têtes de mobs sur le radar** (P3 ★★★★☆, compatible mods)~~ ✔ **fait en v3**
   (render-to-texture + atlas, validé en jeu le 2026-07-15 ; V1 revertée le 2026-07-11) +
   ~~quick win : lissage de la caméra du suivi de train (P3 ★★☆☆☆).~~ ✔ **fait**.
4. ~~**Fuites d'information** (P3) — quarantaine à la diffusion~~ ✔ **fait** (2026-07-15,
   à valider en jeu — scénario : deux clients dev, l'un caché qui casse des blocs/explore,
   vérifier que l'autre ne voit rien avant le délai puis voit tout après).
5. **Chantier UI** (quand déparqué) : losange in-world + marqueur joueur + boussole, puis
   écran de config intégré + éditeur couches/bandes, groupement des overlays, passe textes,
   inventaires, tranche API UI.
6. **Robustesse** : ~~intégrité du cache (hash)~~ ✔ **fait** (2026-07-15, à valider en
   jeu — scénario : éditer un PNG du cache client, se reconnecter, vérifier le warn
   serveur et le re-push), rails souterrains.
7. **Optimisation** (P4), puis shaders + audit traductions (P5).

## Fait (résumé — détails dans l'historique git)

- **BUG — lignes de pixels décalées aux frontières de régions (2026-07-15, à valider en
  jeu)** : problème de RENDU (diagnostic utilisateur — les PNG du cache étaient sains), pas
  de données. Les textures de régions (`DynamicTexture`) gardaient le wrap GL par défaut
  **GL_REPEAT** ; les quads sont dessinés bord à bord sous un transform zoom/pan
  fractionnaire → en bord de quad, la précision flottante fait parfois échantillonner pile
  u/v = 1.0, qui wrappe sur la rangée/colonne OPPOSÉE de la même région → ligne d'1 px de
  pixels « décalés » le long des frontières de régions, dépendante du zoom/pan (« parfois »).
  Corrigé : `CLAMP_TO_EDGE` posé à l'upload (`ClientMapCache.uploadTexture`) — même
  protection que `MobIconAtlas` avait déjà. _Durcissement connexe fait en enquêtant (pas la
  cause, mais réel) : écritures disque atomiques partout (`RegionStorage.writeAtomically` :
  tmp + rename) + writer client MONO-thread dans `DiskCache` (avant : `Util.ioPool()`
  multi-thread sans ordre garanti + `Files.write` non atomique = deux pushes rapprochés de
  la même région pouvaient entrelacer le fichier, et l'index à jour aurait masqué la
  corruption à jamais)._

- **BUG P0 — mélange de maps entre mondes solo homonymes** : la clé du cache client
  (`DiskCache.openSession`) était le nom `level.dat` du monde — identique entre « New World »
  et « New World (1) » (Minecraft ne renomme que le DOSSIER lors d'une collision de noms).
  Les deux mondes partageaient `sharedjourney_cache/sp_new_world/` : régions et index
  mélangés, et le handshake déclarait au serveur des versions issues de l'index mélangé,
  donc pas de re-push correctif. Corrigé : la clé est maintenant le nom du DOSSIER du monde
  (`getWorldPath(LevelResource.ROOT)`), unique par construction. Pas de migration des
  anciens dossiers `sp_<nom>` : le serveur est autoritaire, le cache se re-télécharge.

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
