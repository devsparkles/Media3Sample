# Guide d'entretien — Développeur·se Android, équipe Player

Ce projet est un player Media3/ExoPlayer **mobile** qui couvre les points de la fiche de poste :
lecture **MPEG-DASH**, DRM **Widevine**, pubs **VMAP/VAST** (pre/mid/post-rolls, skip, tracking),
**Clean Architecture** multi-modules, **coroutines**, **observabilité** et **tests**.

> Lancer : `./gradlew test` (tests JVM, sans émulateur) puis exécuter `:app` depuis Android Studio.
> Logcat : filtrer sur `PlaybackQoE`, `AdTracker`, `EventLogger`.

---

## 1. Architecture

```
:app ──► :feature:player ──► :player:engine ──► :core:domain ◄── :core:data
  └──────────────────────────────────────────────────────────────────┘ (câblage DI)
```

| Module | Type | Rôle | Dépend de |
|---|---|---|---|
| `:core:domain` | Kotlin/JVM pur | Modèles métier, interfaces de repository, use cases | coroutines uniquement |
| `:core:data` | Kotlin/JVM pur | HTTP, parsing VMAP/VAST, résolution des wrappers, tracking | domain |
| `:player:engine` | Android lib | **Tout Media3** : construction d'ExoPlayer, DRM, `AdsLoader` maison, mapping des erreurs, analytics | domain, media3 |
| `:feature:player` | Android lib + Compose | ViewModel, écrans, overlay pub | engine |
| `:app` | Application | Composition root (DI manuelle), navigation | tous |

**Pourquoi ce découpage ?**
- La règle de dépendance de la Clean Architecture : tout pointe vers le domaine, qui ne dépend d'aucun framework.
- Le parsing VAST et les règles pub sont testables en JVM pur, donc en quelques millisecondes.
- Pour **TV/tablette**, on ajoute `:feature:player-tv` (Compose for TV, focus D-pad) et on réutilise engine/domain/data sans y toucher.
- Docs : [Modularisation](https://developer.android.com/topic/modularization) · [Couche domaine](https://developer.android.com/topic/architecture/domain-layer) · [Guide d'architecture](https://developer.android.com/topic/architecture)

---

## 2. Rôle de chaque fichier

### `:core:domain`
| Fichier | Rôle |
|---|---|
| `model/VideoContent.kt` | Contenu à lire (URL, type de flux, `DrmConfig`, URL de l'Ad Proxy). Contient l'explication du flux Widevine et de L1/L3. |
| `model/AdSchedule.kt` | Planning pub métier : `AdBreak` (pre/mid/post), `LinearAd`, `MediaFile`, `AdTrackingEvent`. Rappel des specs VAST/VMAP. |
| `model/PlayerError.kt` | Erreurs de lecture métier (Network, Drm, Decoder…) que l'UI comprend. |
| `repository/Repositories.kt` | Interfaces `ContentRepository`, `AdRepository`, `AdTracker` (inversion de dépendances). |
| `usecase/UseCases.kt` | `LoadAdScheduleUseCase` (règles : pas de break vide, tri par séquence), `MediaFileSelector` (choix du fichier pub selon le bitrate). |

### `:core:data`
| Fichier | Rôle |
|---|---|
| `network/HttpClient.kt` | Interface HTTP + implémentation `HttpURLConnection` « main-safe » (`Dispatchers.IO`). |
| `ads/parser/XmlSupport.kt` | Parsing DOM **sécurisé contre XXE**, gestion des namespaces (`vmap:`) et des CDATA. |
| `ads/parser/TimeParser.kt` | `"00:00:15.500"`, `"start"`, `"end"`, `"25%"` → millisecondes / position. |
| `ads/parser/VmapParser.kt` | VMAP → liste d'AdBreak bruts (`AdTagURI` ou VAST embarqué). |
| `ads/parser/VastParser.kt` | VAST → `InLine` / `Wrapper`, MediaFiles, tracking, skipoffset. |
| `ads/AdRepositoryImpl.kt` | **Orchestration** : VMAP → VAST en parallèle (`async`), suivi des Wrappers (5 au maximum), fusion du tracking de chaque niveau, timeouts, codes d'erreur VAST. |
| `ads/HttpAdTracker.kt` | Envoi des pixels (« fire and forget »), macros remplacées juste avant chaque requête. |
| `ads/macro/MacroStrategy.kt` | **Pattern Strategy** : `MacroStrategy`, `MacroValue` (valeur / -1 / -2) et `MacroContext` (un par requête). Détails dans `GUIDE_MACROS_VAST.md`. |
| `ads/macro/MacroStrategies.kt` | Une stratégie par macro : TIMESTAMP, CACHEBUSTING, ERRORCODE, ADPLAYHEAD, MEDIAPLAYHEAD/CONTENTPLAYHEAD, BREAKPOSITION, ASSETURI. |
| `ads/macro/MacroExpander.kt` | Un passage de regex, encodeURIComponent, -1 pour les macros de la spec non fournies, macros hors spec intactes. |
| `content/FakeContentRepository.kt` | Catalogue de démo : Widevine + VMAP, VAST skippable, Widevine sans pub, licence invalide. |

### `:player:engine`
| Fichier | Rôle |
|---|---|
| `PlayerFactory.kt` | **Fichier clé.** Configure DataSource, DRM, `MediaSourceFactory`, `LoadControl` (buffer), `TrackSelector` (ABR, langues), focus audio, analytics. Contient aussi `PlayerSession.release()`. |
| `MediaItemMapper.kt` | `VideoContent` → `MediaItem` (mimeType DASH, `DrmConfiguration`, `AdsConfiguration`). |
| `ads/VmapAdsLoader.kt` | **Implémentation de `AdsLoader`** : charge le VMAP, publie l'`AdPlaybackState`, gère le tracking (impression, quartiles, complete, pause, mute), le skip, le clic et les erreurs de pub. |
| `ads/AdPlaybackStateMapper.kt` | `AdSchedule` → `AdPlaybackState` (groupes en µs, `C.TIME_END_OF_SOURCE` pour le post-roll). |
| `ads/AdProgressTracker.kt` | Logique pure des quartiles : chaque événement est envoyé une seule fois. |
| `ads/DeferredAdViewProvider.kt` | Conteneur des pubs (nécessaire pour OMID/SIMID), branché plus tard par la PlayerView. |
| `error/PlaybackErrorMapper.kt` | Familles de codes `PlaybackException` (2xxx I/O, 3xxx parsing, 4xxx décodeur, 6xxx DRM) → `PlayerError`. |
| `analytics/PlaybackAnalyticsLogger.kt` | QoE : TTFF, temps de buffering, changements de qualité, frames perdues, durée de la licence DRM. |
| `drm/WidevineCapabilities.kt` | Lit le niveau de sécurité Widevine (L1/L3) et le niveau HDCP de l'appareil. |

### `:feature:player` et `:app`
| Fichier | Rôle |
|---|---|
| `PlayerViewModel.kt` | Possède le player (il survit à la rotation), état UI unique, retry, recalage `BEHIND_LIVE_WINDOW`, skip et clic sur la pub, `release()` dans `onCleared`. |
| `PlayerScreen.kt` | `PlayerView` dans `AndroidView`, pause sur `ON_STOP`, overlay pub (compteur, « Passer dans X s »), overlay d'erreur. |
| `catalog/CatalogScreen.kt` | Liste des contenus de démo. |
| `di/AppContainer.kt` | Composition root : le seul endroit qui instancie les implémentations (équivalent manuel de Hilt). |
| `ScreenStores.kt` | Un `ViewModelStore` par écran, pour que le retour arrière libère vraiment le player. |
| `MainActivity.kt` | Navigation à deux écrans. |

---

## 3. Les classes ExoPlayer/Media3 à connaître

| Classe / méthode | Rôle | Où dans le code |
|---|---|---|
| `ExoPlayer.Builder` / `ExoPlayer` | Le moteur. À utiliser depuis **un seul thread** (l'`applicationLooper`). | `PlayerFactory` |
| `Player` (interface) | API commune (play, pause, seek, état). Elle est aussi implémentée par `MediaController` et `CastPlayer`. | `PlayerViewModel.player` |
| `setMediaItem()` → `prepare()` → `playWhenReady = true` | Séquence de lancement. `prepare()` charge le manifest, la licence et le VMAP. | `PlayerViewModel.loadContent` |
| `release()` | Libère les décodeurs (ressource rare) et les sessions DRM. **Obligatoire.** | `PlayerSession.release` |
| États `IDLE / BUFFERING / READY / ENDED` | Après une erreur fatale → `IDLE` ; `prepare()` relance depuis la même position. | `PlayerViewModel.retry` |
| `MediaItem` | La *description* d'un média (URI, mimeType, DRM, pubs, métadonnées). | `MediaItemMapper` |
| `MediaSource` / `DefaultMediaSourceFactory` | Transforme un `MediaItem` en source lisible (`DashMediaSource`, `HlsMediaSource`, `ProgressiveMediaSource`). | `PlayerFactory` |
| `DataSource.Factory` (`DefaultHttpDataSource`) | Pile réseau (OkHttp et Cronet en alternative). | `PlayerFactory` |
| `LoadControl` (`DefaultLoadControl`) | **Quand** charger : tailles du buffer, seuils de démarrage et de rebuffering. | `PlayerFactory` |
| `TrackSelector` (`DefaultTrackSelector`) | **Quoi** charger : qualité (ABR), langue audio, sous-titres. | `PlayerFactory` |
| `BandwidthMeter` | Estime le débit et alimente l'ABR. | (défaut) |
| `Renderer` / `MediaCodec` | Décodent et affichent l'audio et la vidéo. | (interne) |
| `DrmSessionManager` / `DefaultDrmSessionManagerProvider` | Sessions `MediaDrm` et requêtes de licence. | `PlayerFactory` |
| `MediaItem.DrmConfiguration` | UUID Widevine, URL de licence, headers, multi-session. | `MediaItemMapper` |
| `AdsLoader` / `AdsMediaSource` | Insertion de pubs côté client (CSAI). | `VmapAdsLoader` |
| `AdPlaybackState` | Où sont les pubs et dans quel état (AVAILABLE/PLAYED/SKIPPED/ERROR). Immuable. | `AdPlaybackStateMapper` |
| `AdViewProvider` | Vue des pubs et overlays (OMID « friendly obstructions »). | `DeferredAdViewProvider` |
| `Player.Listener` | Callbacks : état, erreurs, discontinuités, volume… | `VmapAdsLoader`, `PlayerViewModel` |
| `onPositionDiscontinuity(AUTO_TRANSITION)` | Fin naturelle d'une pub → `complete`. | `VmapAdsLoader` |
| `isPlayingAd`, `currentAdGroupIndex`, `currentAdIndexInAdGroup` | Savoir quelle pub est en cours. | `VmapAdsLoader` |
| `AnalyticsListener` / `EventLogger` / `PlaybackStatsListener` | Observabilité et debug. | `PlaybackAnalyticsLogger` |
| `PlaybackException.errorCode` | Codes d'erreur par famille. | `PlaybackErrorMapper` |
| `PlayerView` | UI prête à l'emploi (SurfaceView, contrôles, marqueurs de pubs). | `PlayerScreen` |
| `MediaSession` / `MediaSessionService` | Lecture en arrière-plan, notification, Android Auto et TV. *Non utilisé ici, mais à connaître.* | — |

Docs : [ExoPlayer](https://developer.android.com/media/media3/exoplayer) · [Personnalisation](https://developer.android.com/media/media3/exoplayer/customization) · [DASH](https://developer.android.com/media/media3/exoplayer/dash) · [DRM](https://developer.android.com/media/media3/exoplayer/drm) · [Insertion de pubs](https://developer.android.com/media/media3/exoplayer/ad-insertion) · [Analytics](https://developer.android.com/media/media3/exoplayer/analytics) · [Événements](https://developer.android.com/media/media3/exoplayer/listening-to-player-events) · [Sources Media3 (GitHub)](https://github.com/androidx/media)

---

## 4. Flux de bout en bout

1. L'utilisateur choisit un contenu. `PlayerViewModel` appelle `setMediaItem(content.toMediaItem())` puis `prepare()`.
2. `DefaultMediaSourceFactory` voit l'`AdsConfiguration` et enveloppe la `DashMediaSource` dans une `AdsMediaSource`.
3. `AdsMediaSource` appelle `VmapAdsLoader.start()`. Le loader lance une coroutine qui :
   1. récupère le VMAP via `LoadAdScheduleUseCase` → `AdRepositoryImpl` ;
   2. résout chaque `AdBreak` en parallèle (VAST, puis Wrapper → InLine) ;
   3. publie un `AdPlaybackState`. **Le contenu attend cette publication**, d'où le timeout de 10 s.
4. En parallèle, `DashMediaSource` télécharge le `.mpd`. Le `<ContentProtection>` déclenche le `DrmSessionManager`, qui envoie un POST au serveur de licence Widevine, puis `onDrmKeysLoaded`.
5. Le pre-roll se lance. Le loader envoie dans l'ordre : `breakStart`, impression, start, quartiles, complete (via la discontinuité), puis marque la pub `PLAYED`.
6. Le contenu reprend. À 15 s environ, ExoPlayer lit le mid-roll. Si on seeke au-delà d'un mid-roll, ExoPlayer joue d'abord le break non vu qui précède la nouvelle position.
7. En fin de contenu, le post-roll se lance, puis `STATE_ENDED`.

---

## 5. Questions probables et éléments de réponse

**Streaming / buffering**
- *DASH vs HLS ?* DASH est une norme ISO, avec un manifest XML et du CMAF/fMP4. HLS vient d'Apple, avec des playlists m3u8. ExoPlayer gère les deux. Widevine est la combinaison la plus courante sur Android, avec DASH.
- *Comment réduire le temps de démarrage ?* Baisser `bufferForPlaybackMs`, précharger (`PreloadManager`), utiliser `setPlayClearContentWithoutKey`, démarrer à un bitrate initial bas et partager une connexion HTTP déjà ouverte.
- *Un rebuffering en boucle ?* Regarder `onLoadError`, l'estimation de bande passante et `bufferForPlaybackAfterRebufferMs`, puis plafonner la qualité (`setMaxVideoBitrate`).

**DRM**
- *L1 vs L3 ?* En L1, le déchiffrement et le décodage se font dans le TEE (HD/4K autorisées). En L3, tout est logiciel (souvent limité à la SD). Voir `WidevineCapabilities`.
- *Écran noir avec le son ?* Souvent une `TextureView` utilisée avec un contenu sécurisé, ou un HDCP insuffisant sur une sortie externe.
- *Licence qui expire en cours de lecture ou rotation de clés ?* `setMultiSession(true)`, renouvellement de licence, token côté backend.
- *Hors-ligne ?* `OfflineLicenseHelper` + `DownloadManager`, et `keySetId` dans la `DrmConfiguration`.

**Pub**
- *VAST vs VMAP ?* VMAP dit **quand** passer les pubs, VAST dit **quoi** jouer et **quoi tracker**.
- *Wrapper ?* Une redirection entre régies. Il faut pinger le tracking de **tous** les niveaux et limiter la profondeur.
- *Une pub en erreur ?* `withAdLoadError`, puis ping de l'URL `<Error>` avec le code 405. Le contenu continue.
- *Ad Proxy lent ?* Timeout global : on lit le contenu sans pub. La pub ne doit jamais bloquer le contenu.
- *Pause ads ?* On écoute `onIsPlayingChanged(false)` hors pub, on affiche une pub non linéaire ou une image en overlay, et on envoie l'impression à l'affichage.
- *OMID ?* Mesure de la visibilité, avec les « friendly obstructions » déclarées via `AdOverlayInfo`. *SIMID ?* Pub interactive dans une WebView qui communique avec le player.
- *CSAI vs SSAI ?* Ici, c'est du CSAI : le client insère des fichiers séparés. En SSAI, les pubs sont « cousues » dans le flux côté serveur (DAI, MediaTailor) : un seul flux et moins de bloqueurs, mais le tracking passe par les métadonnées.
- *IMA SDK ?* `media3-exoplayer-ima` / `ImaAdsLoader` fait tout ceci tout seul (OMID compris). Une implémentation maison se justifie quand on a un Ad Proxy propriétaire ou quand on veut un contrôle total.

**Architecture / Kotlin**
- *Pourquoi le player dans le ViewModel ?* Il survit à la rotation. Pour le background, on passe à un `MediaSessionService`.
- *Concurrence structurée ?* `coroutineScope` + `async` dans `AdRepositoryImpl`. On relance toujours `CancellationException`.
- *Comment tester ?* Parsers et repository en JVM pur avec des fixtures XML et un `FakeHttpClient`. `AdProgressTracker` est isolé. Pour le player : `TestExoPlayerBuilder` et `FakeMediaSource` (`media3-test-utils`), ou Robolectric.

**IA (dans la fiche de poste)** : générer des fixtures VAST de cas limites, écrire des tests à partir d'un bug de prod, analyser des logs `EventLogger`. Toujours relire, car l'IA invente facilement des API Media3 qui n'existent pas : vérifier la signature dans la doc ou les sources.

---

## 6. Pistes d'amélioration (à proposer en entretien)
- Background et notification avec `MediaSessionService`, plus Picture-in-Picture.
- Hilt et Navigation Compose à la place de la DI manuelle et de la navigation maison.
- Tracking persistant (WorkManager) pour ne pas perdre d'impressions hors-ligne.
- Mid-rolls en pourcentage (`timeOffset="50%"`), qui nécessitent la durée du contenu (`handleContentTimelineChanged`).
- Pubs non linéaires, compagnons et pause ads. Intégration de l'OM SDK.
- Tests instrumentés avec `media3-test-utils` et CI (GitHub Actions : `./gradlew test lint`).

---

## 7. Tracking d'audience

### 7.1 Ce que c'est, et ce que ce n'est pas
Il y a **deux** trackings distincts dans le player. Ils ne partagent ni contrat ni code :

| | Tracking pub (`AdTracker`) | Mesure d'audience (`PlaybackTracker`) |
|---|---|---|
| Question | « La régie a-t-elle diffusé sa pub ? » | « Combien de temps a-t-on regardé ce programme, et quelles pubs ? » |
| Cible | URLs de pixels fournies par le VAST | SDK Nielsen, outil maison (cas d'un client comme Canal+) |
| Rythme | Un GET par événement (impression, quartiles…) | Un battement de cœur (playhead) **toutes les secondes** |
| Branché dans | `VmapAdsLoader` | `PlayerEventTranslator` (dans `PlayerFactory`) |

### 7.2 Architecture
```
 Media3 (Player.Listener)                                     :player:engine
   callbacks individuels ──► raisons (seek ? repeat ? cause de la pause ?)
   onEvents(player, events) ──► PlayerSnapshot (état CONSOLIDÉ)    PlayerEventTranslator
   ticker 1 s (applicationLooper) ──► snapshot                      (seul code qui connaît Media3)
                          │
                          ▼                                         :core:domain (Kotlin pur)
                PlaybackSessionStateMachine ──► PlaybackEvent (SessionStarted, Paused, Tick…)
                          │  dispatchTo()
                          ▼
      BrandTrackerFactory(BrandTrackingConfig) ──► liste des reporters de LA marque
                          ▼
                    CompositeTracker  (fan-out + isolation des exceptions)
                     │            │
                     ▼            ▼                                 :core:data (Kotlin pur)
              NielsenTracker   InHouseTracker
              (règles Nielsen) (beacons + heartbeat toutes les 10 s)
                     │
                     ▼
              NielsenSdkGateway ──► LoggingNielsenSdkGateway (Logcat « Nielsen »)
              ⚠️ le SDK Nielsen réel n'est PAS inclus (propriétaire, appid fourni par Nielsen)
```

| Fichier | Rôle |
|---|---|
| `core/domain/…/tracking/TrackingModels.kt` | `TrackedContent`, `TrackedAdBreak`, `TrackedAd`, `Playhead`, `PauseReason`, `SessionEndReason` |
| `core/domain/…/tracking/PlaybackEvent.kt` | Événements normalisés + `dispatchTo(tracker)` (un `when` exhaustif) |
| `core/domain/…/tracking/PlaybackTracker.kt` | Le contrat commun à tous les outils (pattern Adapter) |
| `core/domain/…/tracking/CompositeTracker.kt` | Fan-out : un tracker qui plante ne casse ni la lecture ni les autres |
| `core/domain/…/tracking/PlaybackSessionStateMachine.kt` | Snapshots → événements ; toutes les décisions (buffering, repeat, erreur…) |
| `core/data/…/tracking/nielsen/NielsenTracker.kt` | Règles Nielsen DCR, avec la page source citée à chaque règle |
| `core/data/…/tracking/nielsen/NielsenSdkGateway.kt` | Frontière avec le SDK + `LoggingNielsenSdkGateway` |
| `core/data/…/tracking/inhouse/InHouseTracker.kt` | Tracker maison générique (garde la cause de la pause, heartbeat toutes les 10 s) |
| `core/data/…/tracking/brand/BrandTrackers.kt` | `BrandTrackingConfig` (les reporters d'une marque) + `BrandTrackerFactory` (config → liste de trackers) |
| `player/engine/…/tracking/PlayerEventTranslator.kt` | `onEvents` → snapshot, ticker 1 s, position de sortie des transitions, `release()` idempotent |
| `player/engine/…/tracking/TechnicalMediaItem.kt` | Marque les items techniques (vidéo « one pixel ») : invisibles pour la mesure |
| `app/…/di/AppContainer.kt` | Choisit la config de la marque, construit ses trackers (DI manuelle) et les passe à `PlayerFactory` |

Debug : dans Logcat, filtrer sur `PlaybackTracking`. On y lit la timeline « callback Media3 → événement normalisé → appel SDK » :
```
onEvents[onPlaybackStateChanged(READY), onIsPlayingChanged(true)] → SessionStarted(...)
      → Nielsen.play({channelName=Media3Sample, mediaURL=})
      → Nielsen.loadMetadata({type=content, assetid=tears-widevine-ads, ...})
onEvents[onPlaybackStateChanged(READY), onIsPlayingChanged(true)] → AdStarted(... kind=PREROLL ...)
      → Nielsen.stop()
      → Nielsen.loadMetadata({type=preroll, assetid=6359933237, ...})
onEvents[onPositionDiscontinuity(AUTO_TRANSITION)] → AdBreakEnded(... PREROLL ..., resumesContent=true)
      → Nielsen.stop()
      → Nielsen.loadMetadata({type=content, ...})
```
(extrait réel : émulateur Pixel 10 Pro, contenu « Widevine + VMAP »)

### 7.3 Tableau : callback Media3 → événement normalisé → appels Nielsen

| Situation | Ce que lit le traducteur (Media3) | Événement normalisé | Appels Nielsen |
|---|---|---|---|
| Lancement | `onEvents` : `STATE_READY` et `playWhenReady = true` (premier READY) | `SessionStarted` | (flush si besoin) `play(channelInfo)`, `loadMetadata(type=content)` |
| Lecture en cours | ticker 1 s sur `applicationLooper`, seulement si `isPlaying` | `Tick` | `setPlayheadPosition(s)` : position en s (VOD) ou heure UTC en s (live) |
| Pause utilisateur | `onPlayWhenReadyChanged(false, USER_REQUEST)` | `Paused(USER)` | `setPlayheadPosition(final)` si nécessaire, puis `stop()` |
| Appel, alarme | `playbackSuppressionReason = TRANSIENT_AUDIO_FOCUS_LOSS` (`playWhenReady` reste à true) | `Paused(INTERRUPTION)` | `stop()` |
| Casque débranché | `onPlayWhenReadyChanged(false, AUDIO_BECOMING_NOISY)` | `Paused(AUDIO_BECOMING_NOISY)` | `stop()` |
| Reprise | `playWhenReady = true` et plus de suppression | `Resumed` | `play()`, `loadMetadata(asset en cours : contenu OU pub)` |
| Buffering | `STATE_BUFFERING`, `isPlaying = false` | aucun (les ticks s'arrêtent) | aucun appel ; le playhead est retenu |
| Seek | `onPositionDiscontinuity(SEEK)` | `Seeked` | aucun (non documenté par Nielsen) |
| Début d'un break | `isPlayingAd` passe à true / `currentAdGroupIndex` change | `AdBreakStarted`, `AdStarted(exitPlayhead)` | `setPlayheadPosition(final contenu)`, `stop()`, `loadMetadata(type=preroll\|midroll\|postroll)` |
| Pub suivante du break | `currentAdIndexInAdGroup` change (`AUTO_TRANSITION`) | `AdStarted(exitPlayhead)` | `setPlayheadPosition(final pub)`, `stop()`, `loadMetadata(pub)` |
| Retour au contenu | `isPlayingAd` passe à false (`onPositionDiscontinuity(AUTO_TRANSITION)`) | `AdBreakEnded(resumesContent=true, exitPlayhead)` | `setPlayheadPosition(final pub)`, `stop()`, `loadMetadata(content)` ; le tick suivant reprend à la position du contenu, pas à 0 |
| Fin du post-roll | `STATE_ENDED` alors qu'une pub est en cours | `AdBreakEnded(false)`, `Ended(COMPLETED)` | `setPlayheadPosition(final)`, `end()` |
| Fin du contenu | `STATE_ENDED` | `Ended(COMPLETED)` | `end()` |
| Média suivant (playlist) | `onMediaItemTransition(AUTO)` + `onPositionDiscontinuity(AUTO_TRANSITION)` dans **le même** `onEvents` | `ContentChanged` (un seul, avec `exitPlayhead`) | flush : `setPlayheadPosition(final)`, `end()`, puis `play()`, `loadMetadata(content)` |
| Item technique (« one pixel ») | `currentMediaItem` marqué `TechnicalMediaItem` | aucun (content = null) | aucun : la session démarre sur le premier vrai contenu |
| Repeat | `onMediaItemTransition(REPEAT)` | `Ended(COMPLETED)` + `SessionStarted` | `end()`, `play()`, `loadMetadata` |
| Erreur fatale (ex : licence 401) | `EVENT_PLAYER_ERROR`, puis `STATE_IDLE` | `Ended(ERROR)` | `end()` |
| `BEHIND_LIVE_WINDOW` | `EVENT_PLAYER_ERROR`, alors que le ViewModel a déjà relancé `prepare()` | `Ended(ERROR)`, puis `SessionStarted` au READY | `end()`, puis `play()`, `loadMetadata` |
| `player.stop()` | `STATE_IDLE` sans erreur | `Ended(STOPPED)` | `end()` |
| App en arrière-plan | `ON_STOP` → `ViewModel.onBackground()` → `pause()` | lifecycle + `Paused(USER)` | `appInBackground()`, `stop()` |
| Retour au premier plan | `ON_START` | lifecycle | `appInForeground()` |
| Sortie de l'écran | `onCleared()` → `PlayerSession.release()` | `Ended(RELEASED)`, `Released` | `end()`, une seule fois |
| Fermeture de l'app | non branché | — | `close()` : non appelé (voir 7.4) |

### 7.4 Décisions prises (à savoir défendre)
- **Point d'entrée `onEvents`.** La doc Media3 garantit que les changements d'une même itération du Looper sont « reported together and only after all individual callbacks were triggered ». Je lis donc l'état consolidé. Les callbacks individuels ne servent qu'aux *raisons* (seek, repeat, cause de la pause).
- **Démarrage au premier READY avec `playWhenReady`**, et non au `prepare()`. Le buffering initial n'est pas du visionnage, et à ce moment-là la durée et le statut live sont connus pour les métadonnées.
- **Buffering ≠ pause.** La doc Nielsen **couvre** ce cas, contrairement à ce que je pensais au départ. FAQ : « For brief buffering […] do not call stop right away. Instead, stop sending the playheadPosition ». Interruption Scenarios : « Call `stop` immediately (except when content is buffering) ». Il n'y a pas d'événement : les ticks s'arrêtent d'eux-mêmes, car `isPlaying` passe à false.
- **Suppression (focus audio transitoire) = `Paused(INTERRUPTION)`**, distincte d'une pause utilisateur. Nielsen fait `stop()` dans les deux cas (« Call stop as soon as a SIM or Skype / Hangout call is observed »). Le tracker maison conserve la cause. Le `SCRUBBING` n'est pas une interruption.
- **Repeat = fin + nouvelle session.** Chaque boucle est un visionnage complet.
- **Erreur = `end()`, pas `stop()`.** Après une erreur fatale, on ne sait pas si l'utilisateur va réessayer ; « Réessayer » ouvre une nouvelle session. L'alternative `stop()` se défendrait (Nielsen traite la perte réseau comme une interruption), mais elle laisserait une session ouverte indéfiniment.
- **`ON_STOP` = suspension (`stop()`), pas fin.** Interruption Scenarios : « Call stop as soon as the app goes to background ». La fin (`end()`) a lieu au `release()`.
- **`close()` non branché.** Android n'a aucun signal fiable de fermeture de l'app (`Application.onTerminate` n'est jamais appelé sur un vrai appareil), et fermer le SDK à la fin d'une activité le désactiverait pour le reste du process. `NielsenTracker.onAppClose()` existe et il est testé.
- **Le flush Nielsen**, à un seul endroit (`NielsenTracker.flushPreviousSession`) : avant chaque `play()` de session, s'il reste une session ouverte, on appelle `end()` si le contenu change, ou `stop()` si c'est le même contenu et que le SDK est en PROCESSING. C'est aussi le vrai chemin du changement de contenu.
- **Dernier playhead à chaque transition.** DCR Android : « The final playhead position must be sent for the current asset being played before calling `stop`, `end` or `loadMetadata` ». Avant, aux transitions pub, on faisait `stop()` sans position finale : Nielsen perdait jusqu'à une seconde (l'écart avec le dernier tick) à chaque coupure. La position exacte est donnée par Media3 : `oldPosition.positionMs` dans `onPositionDiscontinuity(AUTO_TRANSITION | SKIP | REMOVE)`. Le traducteur la transmet (`SnapshotHints.exitPositionMs`), la state machine la met dans `exitPlayhead` et `NielsenTracker` l'envoie avant `stop()`/`end()`. Si elle est absente, on prend le dernier playhead observé.
- **Items techniques invisibles (bug du « one pixel »).** Une vidéo d'un pixel placée en tête de playlist pour démarrer ExoPlayer quand il n'y a pas de pre-roll est un média comme un autre pour le player. Sans marquage, on ouvre une session Nielsen sur ce faux asset, puis `end()`, `play()` et `loadMetadata` au passage au vrai programme. Le marquage est porté par le `MediaItem` (extras de `MediaMetadata`) et non par un index de playlist : il suit l'item partout. Le bug et sa correction sont reproduits par deux tests Robolectric.
- **Reporters par marque.** Un player en marque blanche sert plusieurs diffuseurs, chacun avec ses obligations de mesure. La marque est décrite par une **config** (`BrandTrackingConfig`), et `BrandTrackerFactory` construit ses reporters. Le player ne connaît que la liste obtenue, donc pas de `if (brand == …)` dans le player. Un SDK n'est créé que si la marque l'utilise.
- **`stop()` uniquement en PROCESSING.** Jamais de `stop()` « par précaution » sur un SDK IDLE (FAQ : « do not call stop and play APIs in that sequence. Always call play first and then call stop »).

### 7.5 Vérifié par test, sur appareil, ou par la doc seule
| Point | Statut |
|---|---|
| State machine : démarrage, pause/reprise, seek (y compris pendant le buffering et vers la fin), fin, changement de contenu, repeat, bascules pub, interruption, erreur, BEHIND_LIVE_WINDOW, release idempotent | **Test JVM** (`PlaybackSessionStateMachineTest`, 22 tests) |
| Contrat commun (pas d'appel hors session, pas de double fin, ordre, robustesse sur 500 appels aléatoires) | **Test JVM** (`PlaybackTrackerContractTest`, exécuté par Nielsen et par le tracker maison). Un test de mutation confirme qu'il détecte un double `end()` |
| Règles Nielsen (stop/loadMetadata autour des pubs, flush, playhead live en UTC, pas de stop en IDLE, **dernier playhead avant stop/end à chaque transition**, reprise du contenu à sa position…) | **Test JVM** (`NielsenTrackerTest`, 20 tests) |
| Reporters par marque (jeux différents, SDK créé seulement si utilisé, channelName de la marque) | **Test JVM** (`BrandTrackerFactoryTest`) |
| Position de sortie exacte au mid-roll (5 000 ms) ; bug du « one pixel » reproduit puis corrigé | **Test Robolectric** (`PlayerEventTranslatorTest`) |
| Séquence réelle avec ExoPlayer : contenu seul, pause + seek + reprise, release, `stop()`, **pre + mid + post-roll**, playlist (un seul `ContentChanged`), repeat | **Test Robolectric** (`PlayerEventTranslatorTest` : vrai ExoPlayer, `FakeClock`, `FakeMediaSource` + `AdPlaybackState`) |
| Pubs via le vrai `AdsMediaSource` + `VmapAdsLoader`, arrière-plan/premier plan, release, erreur DRM 401 | **Sur appareil seulement** (émulateur, Logcat `PlaybackTracking`) ; pas de test automatisé |
| Perte réelle du focus audio (appel), casque débranché | **Doc Media3 + test JVM de la state machine** ; non provoqué sur appareil |
| `BEHIND_LIVE_WINDOW` | **Test JVM** uniquement (aucun contenu live dans le catalogue) |
| Comportement du SDK Nielsen réel | **Doc seule** : SDK non inclus |
| Place de `end()` après un post-roll ; seek ; `end()` sur un SDK IDLE | **Non documenté** dans les pages Nielsen consultées : choix signalés dans `NielsenTracker` |

Pièges découverts pendant les tests :
- `onEvents` est livré à l'itération **suivante** du Looper : `untilState(STATE_ENDED)` rend la main avant le dernier événement normalisé. Il faut d'abord vider la file (`untilPendingCommandsAreFullyHandled`).
- Robolectric 4.16 (tiré par media3-test-utils-robolectric 1.11.1) s'arrête à l'API 36, alors que le projet cible l'API 37. Solution : `src/test/resources/robolectric.properties` avec `sdk=36`.
- Le contenu « licence invalide » **joue quelques secondes** avant l'erreur. Tears of Steel commence par une partie en clair, et le 401 n'est d'abord qu'un `onDrmSessionManagerError` non fatal. L'erreur fatale arrive à la fin de cette partie (≈ 10 s) : la session démarre donc bien, puis elle se termine en `ERROR`.

### 7.6 Sources
- Media3 : [Listening to player events (`onEvents`)](https://developer.android.com/media/media3/exoplayer/listening-to-player-events) · [Threading](https://developer.android.com/media/media3/exoplayer/hello-world#a-note-on-threading) · [Ad insertion](https://developer.android.com/media/media3/exoplayer/ad-insertion) · sources de `Player.java` 1.11.1 (constantes `PLAYBACK_SUPPRESSION_REASON_*`, `PLAY_WHEN_READY_CHANGE_REASON_*`, `MEDIA_ITEM_TRANSITION_REASON_*`)
- Nielsen Engineering Portal : [DCR Video Android SDK](https://engineeringportal.nielsen.com/wiki/DCR_Video_Android_SDK) · [Android SDK API Reference](https://engineeringportal.nielsen.com/wiki/Android_SDK_API_Reference) · [Digital Measurement FAQ](https://engineeringportal.nielsen.com/wiki/Digital_Measurement_FAQ) · [Interruption Scenarios](https://engineeringportal.nielsen.com/wiki/Digital_Measurement_Interruption_Scenarios) · [play()](https://engineeringportal.nielsen.com/wiki/play()) · [loadMetadata()](https://engineeringportal.nielsen.com/wiki/loadMetadata()) · [iOS SDK API Reference](https://engineeringportal.nielsen.com/wiki/iOS_SDK_API_Reference) (les états IDLE/PROCESSING sont décrits sur la page iOS, pas sur la page Android consultée)

### 7.6 bis Vérification d'une explication externe (texte d'un autre LLM), confrontée à la doc Nielsen
| Affirmation | Verdict | Source |
|---|---|---|
| Envoyer le dernier playhead avant `stop`, `end` ou `loadMetadata` | ✅ citation exacte | DCR Video Android SDK |
| Buffering court : ne pas faire `stop()`, suspendre le playhead | ✅ | Digital Measurement FAQ |
| `stop()` si le buffering dépasse 30 s | ✅ (FAQ) — **pas encore implémenté** ici | Digital Measurement FAQ |
| Reprise après pause : `loadMetadata` + playhead | ✅, et la FAQ ajoute `play()` avant (« call play, loadMetadata and start […] playheadPosition ») : c'est ce que fait le code | FAQ + DCR |
| Pub : `stop()` avant le break, `loadMetadata(ad)`, puis `stop()` + `loadMetadata(content)` | ✅ | FAQ, Pre-Certification Checklist |
| Chaque pub repart de 0 ; le contenu reprend là où il s'était arrêté | ✅ citations exactes | Pre-Certification Checklist |
| L'API simplifiée (`trackEvent`) « reconstruit » la séquence d'appels de l'ancienne API | ⚠️ exagéré : la page dit seulement qu'un événement envoyé hors séquence est remis dans le bon ordre | Simplified SDK API |
| Ton correctif exact chez Bedrock | ❓ inconnu de ce texte : ne pas le présenter comme un fait | — |

### 7.7 Récit STAR : le flush Nielsen chez Bedrock (à compléter)
Ce dont je me souviens : le player avait **plusieurs reporters**, avec un jeu différent selon la marque (Videoland et RTL n'avaient pas les mêmes). Les événements du player étaient écoutés (listener / observer) puis transmis aux reporters, dont celui qui alimentait le SDK Nielsen. Le bug venait d'un `MediaItem` technique, une vidéo d'un pixel, mise en tête de playlist pour démarrer ExoPlayer quand il n'y avait pas de pub. Le reporter Nielsen le prenait pour un vrai contenu, et il a fallu fermer proprement (flush) l'état précédent avant d'ouvrir le nouveau.

- **Situation** : chez Bedrock Streaming, dans l'équipe Player & Ads, j'ai constaté que `______` (symptôme : quel écart de mesure, sur quelle app et quelle plateforme, signalé par qui ?).
- **Tâche** : je devais trouver pourquoi `______`, et garantir qu'une session Nielsen était toujours fermée avant qu'une nouvelle s'ouvre.
- **Action** : j'ai analysé `______` (logs, Charles Proxy ?) et j'ai trouvé que la cause réelle était `______`. J'ai alors centralisé la fermeture de l'ancienne session dans `______`.
- **Résultat** : `______` (effet mesuré, validation par Nielsen ou par la QA ?).
- **À vérifier avant de le dire** : le correctif exact (filtrer l'item technique ? flush avant le vrai contenu ? les deux ?) et quelles marques utilisaient quels reporters.
- **Ce que j'en retiens** : un seul endroit pour le flush, `end()` si le contenu change, `stop()` sinon, et des tests de contrat pour que ça ne régresse pas. C'est ce que j'ai reproduit dans ce sample.
