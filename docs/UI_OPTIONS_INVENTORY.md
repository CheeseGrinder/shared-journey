# Inventaire — options de la carte plein écran et configs

Document de travail du chantier UI (partie hors-icônes), 2026-07-15. Il nourrit
l'**écran de config intégré** : ce qui existe, ce qui est exposé où, ce qui manque
face à JourneyMap, et ce qui devrait devenir configurable.

## 1. Carte plein écran — état des lieux

### Barre du haut
| Groupe | Boutons |
|---|---|
| Couches | DAY, NIGHT, BIOME, TOPO, CAVE (désactivés si la dimension ne les sert pas) |
| Affichage | grottes auto, mobs hostiles, animaux, apprivoisés, villageois, boussole (rotation minimap), me cacher de la carte, légende |
| Overlays (groupe dédié) | grille de chunks, waypoints, joueurs — les overlays bridgés (trains Create, gisements RNS) gardent leurs propres toggles sur la carte, configs dans l'onglet Addons de l'écran de réglages |
| — | Fermer (à droite) |

### Barre de gauche
Aller aux coordonnées (X/Z), centrer sur le joueur, zoom +/−, gestionnaire de waypoints.

### Autres interactions
- Slider de bande CAVE (bas de l'écran, couche CAVE seule).
- Menu contextuel clic droit : TP, création waypoint (normal/temp/public), tout afficher/masquer.
- Double-clic : créer/éditer un waypoint ; clic sur un train : suivi caméra (lissé).
- Survol : barre d'infos (coordonnées, biome, hauteur, bloc — sidecar INFO), tooltips gares/trains.
- Raccourcis : U (gestionnaire), B (waypoint à la position du joueur).

## 2. Configs — inventaire et exposition

« UI » = togglable en jeu (barre du plein écran) ; « config » = uniquement l'écran
NeoForge aujourd'hui.

### Client
| Section | Clé | Exposé | Candidat écran intégré |
|---|---|---|---|
| minimap | enabled | config (+ raccourci toggle) | oui |
| minimap | size / corner / shape | config | oui (aperçu live serait un plus) |
| minimap | rotateWithPlayer | **UI** (boussole) + config | oui |
| minimap | showCoordinates | config | oui |
| radar | enabled | config | oui |
| radar | radius | config | oui (slider, cap serveur affiché) |
| radar | mobHeads | config | oui |
| radar | showPlayers | config | oui |
| radar | hideFromMap | **UI** + config | oui |
| radar | showHostile/Passive/Pets/Villagers | **UI** + config | oui |
| map | showTrainOverlay / showDepositOverlay | **UI** + config | oui |
| map | showGrid | **UI** + config | oui |
| map | showCave | **UI** + config | oui |
| map | defaultLayer / autoLayer | config | oui |
| map | diskCache | config | oui (technique, onglet avancé) |
| waypoints | tempWaypointRadius | config | oui |
| waypoints | waypointBeacons / beaconMin/MaxDistance | config | oui |
| waypoints | showWaypointNames | config | oui |
| waypoints | deathWaypoints | config | oui |

### Common
| Clé | Exposé | Note |
|---|---|---|
| debugLogging | config | onglet avancé, pas prioritaire |
| fragmentSize | config | technique réseau — laisser hors écran intégré |

### Serveur (écran intégré : onglet réservé aux ops niveau 2+, payload dédié)
| Section | Clé | Cible écran intégré |
|---|---|---|
| layers | defaultLayers / sharedLayers (par dimension) / caveBands | **l'éditeur visuel** (cases par dimension + plages Y) — cœur de l'item TODO |
| engine | maxWorkerThreads / renderChunksPerTick / biomeBlendRadius | onglet avancé ops |
| engine | hiddenBlocks / blockColorOverrides | listes — édition texte assistée, pas prioritaire |
| sync | pushRadiusRegions / maxKbPerSecond / syncRateTicks / allowOnDemandRequests / radarMaxRadius | onglet ops |
| waypoints | deathWaypointsEnabled / waypointStorage | onglet ops |
| privacy | hiddenAreaPolicy / quarantineRadiusChunks / quarantineDrainMinutes | onglet ops |

## 3. Face à JourneyMap — ce qui manque

Fonctions JM absentes chez nous, par intérêt décroissant (jugement — à trancher) :

1. **Toggle « afficher les waypoints » global sur la carte** : on n'a que la visibilité
   par waypoint/groupe. Un bouton d'overlay « waypoints on/off » (et un pour les labels,
   `showWaypointNames` existe déjà) rentrerait dans le groupe overlays. *Peu coûteux.*
2. **Toggle « afficher les joueurs »** sur la carte plein écran : `radar.showPlayers`
   existe mais n'est pas dans la barre (les autres filtres radar y sont). *Trivial.*
3. **Auto jour/nuit sur le plein écran** : notre `autoLayer` ne concerne que la minimap ;
   le plein écran ouvre sur la couche de la minimap puis reste manuel. JM suit l'heure.
   *À décider : bouton « AUTO » comme 6e pseudo-couche ?*
4. **Opacité / thème** : JM offre thèmes et opacité de la minimap. Chez nous rien.
   *Reporté — rejoint la partie icônes/assets.*
5. **Waypoint « suivi » (flèche vers la sélection)** : JM ne l'a pas non plus vraiment ;
   Xaero oui. *Hors scope.*
6. **Export/sauvegarde d'images de la carte** (JM « Save map ») : possible côté client
   (les PNG sont déjà sur disque) — *idée, non planifié.*
7. **Carte web** (JM webmap) : hors scope assumé (serveur-autoritaire, autre chantier).

Non repris volontairement : édition des raccourcis (écran Contrôles vanilla suffit),
presets minimap 1/2 (une seule minimap chez nous), fog-of-war par joueur (contraire au
modèle « carte partagée »).

## 4. Nouvelles configs candidates (à créer, pas seulement exposer)

- `minimap.zoomDefault` (le zoom minimap est aujourd'hui de session, remis à 1 au login).
- `map.gridColor` / `map.gridOpacity` (grille fixe actuellement).
- `fullmap.rememberLayer` : rouvrir le plein écran sur sa dernière couche plutôt que
  celle de la minimap.
- Opacité de la minimap (voir §3.4).

## 5. Décisions prises avant l'écran intégré (tranchées le 2026-07-15)

1. Périmètre v1 : **client + onglet ops serveur d'emblée** — fait dans `MapSettingsScreen`
   (onglet Serveur visible niveau 2+, payloads `OpsConfigRequestPayload`/`OpsConfigPayload`).
2. Quick wins §3.1–.2 : **intégrés au chantier de l'écran** — toggles waypoints/joueurs
   ajoutés au groupe overlays de la barre du haut, et exposés dans l'écran.
3. Nouvelles configs §4 retenues : **`minimap.zoomDefault`**, **`fullmap.rememberLayer`**
   (portée `map.rememberLayer`) et **`map.showWaypoints`** (support du quick win §3.1).
   `map.gridColor`/`gridOpacity` et l'opacité de la minimap sont **reportées** à la partie
   icônes/thème.
