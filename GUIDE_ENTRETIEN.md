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
| `ads/HttpAdTracker.kt` | Envoi des pixels (« fire and forget »), macros `[ERRORCODE]` et `[CACHEBUSTING]`. |
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
