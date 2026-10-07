# CONTEXTE — Projet « Media3Sample » (préparation d'entretien Android Player)

## 1. Qui je suis et pourquoi ce projet existe
- Je suis développeur Android senior (14 ans dans l'IT, Android depuis 2016), consultant placé par **Ekkiden** (une ESN).
- Je prépare une **rencontre client le vendredi 9 octobre 2026 à 11 h** pour un poste de **développeur Android dans une équipe Player**. Ce n'est pas un entretien de recrutement classique, c'est une présentation au client par l'ESN.
- La fiche de poste demande : **Media3/ExoPlayer, MPEG-DASH, DRM Widevine, publicité VAST/VMAP (pre/mid/post-rolls, pause ads), tracking pub, OMID/SIMID, Clean Architecture, coroutines, utilisation d'outils d'IA**. Le poste couvre aussi TV et tablette, mais j'ai volontairement limité ce projet au **mobile**.
- Mon expérience la plus pertinente : **Bedrock Streaming** (juil. 2025 – avr. 2026), équipe Player & Ads, sur un player en marque blanche multi-modules (6play, RTL+, Videoland). J'y ai fait la migration ExoPlayer2 → Media3, Google Cast, MediaSession pour Fire TV (bug de commande vocale), l'AdsLoader pre/mid/post-rolls, les macros VAST `[CACHEBUSTING]`/`[TIMESTAMP]` (remplacement refactoré avec le **pattern Strategy**), du debug pub avec Charles Proxy et la migration de l'endpoint de licence Widevine derrière des feature flags.
- Avant ça : **Warner Bros. Discovery / Discovery+** (2023-2024), avec une UI live sport en Compose par-dessus le player, une timeline interactive, MVI + Flow, plus de 80 % de couverture, sur TV/mobile/tablette. Plus tôt : Lofti (ExoPlayer, offline-first), Skiddle, BBOXX, support Google Android Pay à Londres, formateur Android, Java/JEE en ESN entre 2010 et 2015.
- **Ce qui n'est pas sur mon CV** et que ce projet doit m'aider à maîtriser : OMID/SIMID, MPEG-DASH de manière explicite, les pause ads.
- **Ma façon d'apprendre** : je suis francophone. Je veux du code **très commenté en français**, avec des **liens vers la doc officielle**. Le code me sert d'outil d'apprentissage autant que de démo : je dois pouvoir expliquer chaque fichier à l'oral.

## 2. Le projet technique
Un player vidéo **Media3/ExoPlayer mobile** qui reproduit en miniature un vrai player de streaming : lecture DASH, DRM Widevine, insertion de pubs côté client (CSAI) avec un **AdsLoader maison** (pas l'IMA SDK), tracking VAST et observabilité QoE.

- Branche : `interview-prep/player-sample` (main = `master`)
- Stack : Kotlin 2.2.10, AGP 9.4.1, **Media3 1.11.1** (exoplayer, exoplayer-dash, ui, ui-compose-material3), Compose BOM 2026.02.01, coroutines 1.10.2, Java 11, minSdk 24 / targetSdk 37. Versions centralisées dans `gradle/libs.versions.toml`.
- **DI manuelle** (pas de Hilt), **navigation maison** à deux écrans (pas de Navigation Compose).
- Vérifié sur l'émulateur Pixel 10 Pro : pre-roll et mid-roll, licence Widevine, tracking pub, erreur DRM 401. **Pas encore testés à la main** : le bouton « Passer » (skip) et « Réessayer » (retry).

### Architecture (Clean Architecture multi-modules)
```
:app ──► :feature:player ──► :player:engine ──► :core:domain ◄── :core:data
  └──────────────── câblage DI (AppContainer) ───────────────────────┘
```
| Module | Type | Rôle |
|---|---|---|
| `:core:domain` | Kotlin/JVM pur, aucun Android | Modèles (`VideoContent`, `DrmConfig`, `AdSchedule`/`AdBreak`/`LinearAd`/`MediaFile`/`AdTrackingEvent`, `PlayerError`, `TrackingContext`), interfaces `ContentRepository`/`AdRepository`/`AdTracker`, use cases `LoadAdScheduleUseCase` (ignore les breaks vides, trie par séquence) et `MediaFileSelector` (choisit le fichier pub selon le bitrate) |
| `:core:data` | Kotlin/JVM pur | `HttpClient` (HttpURLConnection sur `Dispatchers.IO`), parsing XML sécurisé contre XXE (`XmlSupport`, `TimeParser`, `VmapParser`, `VastParser`), `AdRepositoryImpl` (VMAP → VAST résolus en parallèle avec `async`, suivi des Wrappers jusqu'à 5 niveaux, fusion du tracking de chaque niveau, timeouts, codes d'erreur VAST), `HttpAdTracker` (pixels en « fire and forget »), moteur de macros `ads/macro/*`, `FakeContentRepository` (catalogue de démo) |
| `:player:engine` | Android lib, **tout Media3 est ici** | `PlayerFactory` (DataSource, DRM, MediaSourceFactory, `DefaultLoadControl`, `DefaultTrackSelector`/ABR, focus audio, analytics, `PlayerSession.release()`), `MediaItemMapper` (VideoContent → MediaItem avec mimeType DASH, `DrmConfiguration`, `AdsConfiguration`), `ads/VmapAdsLoader` (**implémente `AdsLoader`** : charge le VMAP, publie l'`AdPlaybackState`, tracking impression/quartiles/complete/pause/mute, skip, clic, erreurs pub), `AdPlaybackStateMapper` (temps en µs, `C.TIME_END_OF_SOURCE` pour le post-roll), `AdProgressTracker` (logique pure des quartiles, chaque événement une seule fois), `DeferredAdViewProvider` (pour OMID), `PlaybackErrorMapper` (familles de codes 2xxx réseau, 3xxx parsing, 4xxx décodeur, 6xxx DRM → `PlayerError`), `PlaybackAnalyticsLogger` (TTFF, rebuffering, changements de qualité, frames perdues, durée de la licence DRM), `WidevineCapabilities` (niveau L1/L3, HDCP) |
| `:feature:player` | Android lib + Compose | `PlayerViewModel` (possède le player pour survivre à la rotation, état UI unique, retry, recalage `BEHIND_LIVE_WINDOW`, skip/clic pub, `release()` dans `onCleared`), `PlayerScreen` (`PlayerView` dans `AndroidView`, pause sur `ON_STOP`, overlay pub avec « Passer dans X s », overlay d'erreur), `CatalogScreen` |
| `:app` | Application | `AppContainer` (composition root), `ScreenStores` (un `ViewModelStore` par écran pour que le retour arrière libère vraiment le player), `MainActivity` |

### Catalogue de démo (`FakeContentRepository`)
1. Tears of Steel, DASH + Widevine (proxy UAT Google) + VMAP pre/mid/post (tags d'exemple IMA DoubleClick)
2. Tears of Steel, DASH en clair + pre-roll VAST skippable
3. DASH + Widevine sans pub
4. Démo d'erreur : licence Widevine invalide, qui doit afficher l'overlay d'erreur DRM

### Flux de bout en bout
`setMediaItem` → `prepare()` → `DefaultMediaSourceFactory` enveloppe la `DashMediaSource` dans une `AdsMediaSource` → `VmapAdsLoader.start()` lance une coroutine (VMAP → VAST/Wrappers en parallèle → `AdPlaybackState`). **Le contenu attend cette publication**, d'où un timeout de 10 s : sans réponse, on lit le contenu sans pub. En parallèle, le `.mpd` est téléchargé, `<ContentProtection>` déclenche le `DrmSessionManager` puis un POST de licence. Le pre-roll joue avec le tracking breakStart → impression → start → quartiles → complete (détecté via `onPositionDiscontinuity(AUTO_TRANSITION)`), la pub passe à PLAYED, le contenu reprend, le mid-roll arrive vers 15 s, puis le post-roll et `STATE_ENDED`.

### Macros VAST : le sujet signature (lien direct avec mon expérience Bedrock)
- Pattern Strategy : `MacroStrategy` (`name` + `resolve(MacroContext): MacroValue`), `MacroValue` = `Known(raw)` / `Unknown` (→ -1) / `Restricted` (→ -2). Stratégies concrètes : TIMESTAMP, CACHEBUSTING, ERRORCODE, ADPLAYHEAD, MEDIAPLAYHEAD (alias CONTENTPLAYHEAD), BREAKPOSITION, ASSETURI.
- `MacroExpander` fait **un seul passage de regex** (`[NOM]` ou `%5BNOM%5D`), délègue à la stratégie, applique **encodeURIComponent** à toutes les valeurs, met **-1** pour une macro de la spec non fournie et laisse **intactes** les macros hors spec. Une valeur insérée n'est jamais re-scannée.
- `MacroExpander` est appelé **juste avant chaque requête** (pixels et appels VMAP/VAST/Wrapper).
- Historique des commits : `af684cd` (v1 Strategy) → `4c7b3cd` (alignement sur **VAST 4.1 §6**, vérifié dans le PDF officiel). Dans la v1, `[TIMESTAMP]` était un « instantané figé par événement », ce qui était **faux** : la spec dit « heure d'accès **par URI** », et `[CACHEBUSTING]` doit être nouveau **à chaque occurrence**. Je raconte cette erreur en entretien comme exemple d'une suggestion d'IA convaincante mais fausse, détectée en relisant la source primaire.
- Comparaison avec Bedrock : Bedrock avait raison sur la sémantique de TIMESTAMP et CACHEBUSTING, la v1 avait raison sur l'encodage, les -1 et les macros inconnues, et la version finale garde le meilleur des deux.

### Tests (JVM pur, `./gradlew test`, sans émulateur)
`ParsersTest`, `AdRepositoryImplTest`, `AdRepositoryWrapperErrorsTest` (avec `FakeHttpClient` et des fixtures XML), `HttpAdTrackerTest`, `MacroExpanderTest`, `MacroStrategiesTest` (horloge et générateur aléatoire injectés), `MediaFileSelectorTest`, `AdProgressTrackerTest`. Logcat utile : `PlaybackQoE`, `AdTracker`, `EventLogger`.

## 3. Documents de préparation à la racine
- `GUIDE_ENTRETIEN.md` : architecture, rôle de chaque fichier, classes Media3 à connaître, flux de bout en bout, questions probables avec réponses (DASH vs HLS, L1/L3, écran noir avec le son, Wrapper, CSAI vs SSAI, IMA SDK, OMID/SIMID, pause ads…), pistes d'amélioration.
- `GUIDE_MACROS_VAST.md` : verdict Bedrock / v1 / finale face à la spec VAST 4.1 avec citations, récit STAR au « je », liste « à apprendre à la main ».
- `PRESENTATION_ORALE.md` (non commité) : mon pitch oral selon la trame Ekkiden en entonnoir (Société → Direction → Équipe → Mission → Résultat → Conclusion ; dire « je » et pas « on » ; 6-7 min au total, avec Bedrock et WBD en version longue). Il reste des `______` que **je** dois remplir moi-même. **Ne pas inventer de chiffres.**

## 4. Pistes non implémentées (à proposer ou à ajouter)
`MediaSessionService` (lecture en arrière-plan, notification) + Picture-in-Picture ; **pause ads** ; pubs non linéaires et compagnons ; OM SDK ; mid-rolls en pourcentage (qui demandent la durée du contenu) ; Hilt + Navigation Compose ; tracking persistant avec WorkManager ; tests instrumentés avec `media3-test-utils` ; CI GitHub Actions ; un module `:feature:player-tv`.

## 5. Ce que j'attends de toi (LLM)
- M'aider à **comprendre et défendre** chaque choix technique à l'oral, en français, avec des liens vers la doc officielle (developer.android.com/media/media3, specs IAB VAST/VMAP).
- Me faire passer des **entretiens blancs** sur ce code et sur la fiche de poste, en posant des questions de relance comme un vrai lead Player.
- Si tu écris du code : le commenter abondamment en français, respecter l'architecture (Media3 uniquement dans `:player:engine`, `:core:*` en Kotlin pur), et **vérifier les API Media3** au lieu de les inventer.
- Sur les specs (VAST, VMAP, OMID), **citer la source primaire**. Ne pas affirmer de mémoire.
