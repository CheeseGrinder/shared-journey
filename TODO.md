# TODO — Shared Journey

Priorités : **P0** (critique) → **P5** (plus tard). Valeur : ★☆☆☆☆ (faible plus-value) → ★★★★★ (forte plus-value).

## Bugs

- [x] **P0 · ★★★★★ — Marqueur du joueur local absent** : ~~afficher un marqueur pour le joueur
  dont c'est la session (minimap + carte plein écran)~~ — **corrigé** : les triangles de
  `EntityDots.drawPlayerArrow` étaient émis avec un winding inversé par rapport aux quads GUI
  vanilla → éliminés par le back-face culling. Winding corrigé + `disableCull` +
  `setShaderColor(1,1,1,1)` contre l'état GL laissé par les overlays des plugins.
  → Suite : asset dédié pour le marqueur (voir UI / UX).
- [ ] **P1 · ★★★★☆ — Couleur des beacons de waypoints instable** : les waypoints globaux (et
  ceux des waystones) changent de couleur entre les sessions et ne correspondent pas entre
  joueurs.
  _Cause probable : couleur aléatoire attribuée à chaque création par la WaypointFactory du
  bridge (non persistée). Fix : dériver une couleur stable d'un hash de l'id/guid._
- [ ] **P1 · ★★★★★ — Couleurs de blocs incomplètes** : les feuilles de cerisier sont grises ;
  pas compatible vanilla ni mods (Biomes O' Plenty...). Faire comme JourneyMap : extraire une
  palette de couleurs depuis les textures des blocs plutôt que se limiter aux `MapColor`.
- [ ] **P2 · ★★★☆☆ — Labels de waypoints : lisibilité** : partiellement grisés selon l'angle de
  regard (malgré la passe SEE_THROUGH unique) et illisibles de loin — traiter les deux dans la
  même passe sur le rendu des étiquettes.
- [ ] **P2 · ★★★☆☆ — Noms des gares et des trains toujours absents** sur la carte (le tooltip
  au survol via `renderAndPick` de Create ne s'affiche pas — instrumenter le `Rect2i` de bornes
  et le pick).
- [ ] **P2 · ★★☆☆☆ — Téléportation en vol** : le `/tp` ne change pas la hauteur quand on vole
  (le `~` ou le Y de surface est ignoré/inadapté) — corriger le calcul du Y à l'arrivée.
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
  dans le package `api` pour permettre à d'autres mods d'interagir :
  - **rendu écran** : hooks de draw sur la minimap et la carte plein écran (overlays custom
    natifs, sans passer par le bridge JourneyMap), enregistrement d'icônes/marqueurs ;
  - **rendu serveur (image de région)** : hooks dans le pipeline de rendu des chunks — pixels
    custom par couche (s'appuyer sur `ChunkLayerRenderer` qui existe déjà dans l'api),
    post-traitement de l'image de région avant sauvegarde, surcharge de couleur par bloc
    (rejoint le chantier palette), ajout de blocs masqués ;
  - **UI fullscreen** : enregistrement de boutons dans les barres d'actions, d'entrées dans le
    menu contextuel, de lignes dans la barre d'infos ;
  - **waypoints** : façade CRUD publique au-dessus de `WaypointStore` (en plus des
    `WaypointEvent` existants), fournisseurs d'icônes/de rendu de beacon custom ;
  - **couches** : finaliser le pipeline `LayerRegisterEvent` (les couches custom sont
    collectées mais stockage/sync ne sont pas câblés) ;
  - **événements** : ouverture/fermeture de la carte, clic sur la carte, changement de couche ;
  - **lecture/actions** : positions des joueurs, infos de survol, état des régions, demander le
    re-rendu d'une zone.
  _À cadrer pendant le chantier clean code (c'est lui qui fixe la frontière api/interne) ;
  les hooks de draw atterrissent avec les chantiers rendu/UI._
- [ ] **P2 · ★★★★☆ — Écran de gestion des waypoints** : groupes, ajout/édition/suppression,
  filtre par dimension, waypoints globaux, groupe « morts » (death waypoints), etc.
- [ ] **P3 · ★★★☆☆ — Tête des mobs sur la carte** : afficher la tête/l'icône de l'entité
  (comme les têtes de joueurs) à la place des points colorés du radar.
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
- [ ] **P4 · ★★☆☆☆ — Configs client par section** : éclater `ClientConfig` en un fichier par
  section, comme ce qui a été fait côté serveur (`LayersServerConfig`, `EngineServerConfig`...).
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

1. **Micro-fixes testabilité** : ~~marqueur du joueur local (P0 ★★★★★)~~ ✔ fait (l'asset
   dédié suivra avec le chantier UI), couleur des beacons stable (P1 ★★★★☆, hash du guid),
   téléportation en vol (P2 ★★☆☆☆). Quelques heures en tout, et on peut tester proprement
   pendant tout le refactor.
2. **Clean code — le socle** (P3 ★★★☆☆) : `common`, `server` (moteur, services, réseau),
   `client.service`, bridge JourneyMap. Responsabilité unique, utils, constantes, docs et
   commentaires de config en anglais, **configs par section** (P4). L'UI est EXCLUE de cette
   passe (voir 5). C'est aussi ici qu'on **cadre l'API publique** (P3 ★★★★☆) : frontière
   api/interne, façade waypoints, événements — les hooks de draw suivront avec les chantiers
   rendu/UI.
3. **Couleurs de blocs (palette textures)** (P1 ★★★★★) — premier gros chantier feature, sur
   les renderers fraîchement nettoyés.
4. **Infos de survol charge-proof** (P2 ★★★★☆) — le sidecar par région s'appuie sur le moteur
   nettoyé en 2 ; **lisibilité des labels de waypoints** (P2 ★★★☆☆) + **noms des gares/trains**
   (P2 ★★★☆☆).
5. **Chantier UI** : rework écran waypoint + écran de gestion des waypoints (P2 ★★★★☆), puis
   menu clic droit style JM (P3 ★★★☆☆) — les nouveaux écrans appliquent directement les
   conventions posées en 2, et remplacent l'ancien code UI non nettoyé.
6. **Petites features indépendantes** : bouton boussole (P3), têtes des mobs (P3).
7. **Écran de config intégré** (P3 ★★★☆☆) — s'appuie sur le chantier UI ; inclut l'éditeur
   des couches par dimension et des bandes CAVE (P3 ★★★☆☆, config serveur → payload op).
8. **Groupement des boutons d'overlay + passe textes + inventaires options/configs +
   intégrité du cache client (hash des régions)** (P4).
9. **Optimisation** (P4) — sur la base nettoyée et les nouveaux écrans.
10. **Shaders de rendu + audit des traductions** (P5).
