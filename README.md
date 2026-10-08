# Media3Sample

Un player vidéo **Media3 / ExoPlayer** pour mobile, écrit pour **apprendre** et préparer un
entretien « équipe Player ». Ce n'est pas un projet de production : le code est très commenté
en français, avec des liens vers la documentation officielle.

Il reproduit en miniature un vrai player de streaming :

| Sujet | Ce que fait le sample | Où lire |
|---|---|---|
| Lecture | MPEG-DASH, ABR, buffer réglé (`DefaultLoadControl`) | `GUIDE_TECHNIQUE.md` §4.4 |
| DRM | Widevine (licence de test Google), erreurs DRM traduites | `GUIDE_TECHNIQUE.md` §1.4, §4.10 |
| Pubs | `AdsLoader` maison : VMAP → VAST (Wrappers), pre/mid/post-rolls, skip, clic | `GUIDE_TECHNIQUE.md` §4.7 |
| Tracking pub | Pixels VAST (impression, quartiles…), macros VAST 4.1 | `GUIDE_MACROS_VAST.md` |
| Mesure d'audience | Nielsen DCR (SDK simulé) + outil maison, configurés par marque | `GUIDE_ENTRETIEN.md` §7 |
| Google Cast | `CastPlayer`, transfert téléphone ↔ TV qui respecte les pubs | `GUIDE_ENTRETIEN.md` §8 |
| QoE | TTFF, rebuffering, qualité, frames perdues | `GUIDE_TECHNIQUE.md` §4.11 |
| Tests | JVM pur + Robolectric, sans émulateur, en CI | `GUIDE_TECHNIQUE.md` partie 5 |

## Lancer

```bash
./gradlew test                # tous les tests (JVM + Robolectric), sans émulateur
./gradlew :app:assembleDebug  # l'APK ; ou lancer :app depuis Android Studio
```

Logcat utile : `PlaybackQoE`, `AdTracker`, `EventLogger`, `PlaybackTracking`, `Nielsen`, `Cast`.

Le bouton Cast ne trouve un appareil que sur un téléphone avec Google Play Services, sur le
même Wi-Fi qu'un Chromecast. Le récepteur par défaut de Google ne lit que le contenu
« DASH en clair » du catalogue (pas de Widevine ni de pubs sur la TV).

## Architecture

```
:app ──► :feature:player ──► :player:engine ──► :core:domain ◄── :core:data
  └──────────────── câblage DI (AppContainer) ───────────────────────┘
```

- `:core:domain` et `:core:data` sont en **Kotlin pur** : aucun Android, tests en millisecondes.
- **Tout Media3** est dans `:player:engine` (player, pubs, analytics, tracking, Cast).
- `:feature:player` : ViewModel et écrans Compose. `:app` : composition root (DI manuelle).

Le détail, fichier par fichier : `GUIDE_ENTRETIEN.md` §2.

## Les documents

À lire dans cet ordre :

1. **`GUIDE_TECHNIQUE.md`** : le parcours complet, sans prérequis. Le streaming sans code,
   puis ExoPlayer, puis le code dans l'ordre où il s'exécute, les tests, des exercices et la
   préparation d'entretien. Glossaire à la fin.
2. **`GUIDE_ENTRETIEN.md`** : les fiches de révision. Rôle de chaque fichier, classes Media3,
   questions probables avec réponses, tracking d'audience (§7), Google Cast (§8).
3. **`GUIDE_MACROS_VAST.md`** : les macros VAST confrontées à la spec, et le récit d'une
   suggestion d'IA convaincante mais fausse.
4. **`PRESENTATION_ORALE.md`** : le pitch du parcours professionnel (trous `______` à remplir).
5. **`CONTEXTE_LLM.md`** : le contexte du projet, à coller dans un assistant IA.

## Historique

| PR | Contenu |
|---|---|
| [#1](https://github.com/devsparkles/Media3Sample/pull/1) | Le sample : modules, DASH, Widevine, `VmapAdsLoader`, macros VAST, mesure d'audience multi-outils (Nielsen + maison), QoE, CI, guides |
| [#2](https://github.com/devsparkles/Media3Sample/pull/2) | Nielsen : playhead final envoyé à chaque transition ; trackers configurés par marque ; correction du « bug du one pixel » (pas de session de mesure sur un item technique) |
| [#3](https://github.com/devsparkles/Media3Sample/pull/3) | Google Cast : `CastPlayer`, bouton Cast, transfert qui envoie la position du contenu pendant une pub et retrouve les pubs au retour sur le téléphone |

## Limites connues

- Mobile seulement (pas de module TV), player gardé par le ViewModel : pas encore de
  `MediaSessionService` (notification, lecture en arrière-plan, Picture-in-Picture).
- SDK Nielsen simulé (`LoggingNielsenSdkGateway`) : le vrai SDK est propriétaire.
- Cast jamais essayé sur un vrai Chromecast ; pas de récepteur personnalisé.
- Pas de pause ads, de pubs non linéaires ni d'OM SDK.
