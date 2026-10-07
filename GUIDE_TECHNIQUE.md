# Guide technique : comprendre un player vidéo Android de A à Z

> **À qui s'adresse ce guide ?** À quelqu'un qui connaît Android et Kotlin mais n'a **jamais**
> travaillé dans le streaming et ne connaît **pas** ExoPlayer. Tout est expliqué ici. Les liens
> vers la documentation officielle servent à approfondir, pas à comprendre.
>
> **Comment le lire ?** Dans l'ordre. Les parties 1 et 2 posent les notions (sans code). La
> partie 3 montre l'architecture. La partie 4 suit le code **depuis le point d'entrée**, fichier
> par fichier, dans l'ordre où il s'exécute. Garde le projet ouvert à côté et lis chaque fichier
> au moment où le guide en parle. La partie 7 te prépare à l'entretien.
>
> **Temps de lecture** : environ 3 h pour les parties 1 à 4, 1 h pour les parties 5 à 7.
>
> **Les autres documents du dépôt** :
> - `GUIDE_ENTRETIEN.md` : fiches de révision (tableaux, questions/réponses), à relire la veille ;
> - `GUIDE_MACROS_VAST.md` : le sujet des macros VAST en profondeur, confronté à la spec ;
> - `PRESENTATION_ORALE.md` : le pitch de parcours ;
> - `CONTEXTE_LLM.md` : le contexte du projet, à coller dans un assistant IA.

---

## Sommaire

1. [Les bases du streaming vidéo (sans code)](#partie-1--les-bases-du-streaming-vidéo-sans-code)
2. [ExoPlayer / Media3 : le modèle mental](#partie-2--exoplayer--media3--le-modèle-mental)
3. [L'architecture du projet](#partie-3--larchitecture-du-projet)
4. [Le parcours du code, depuis le point d'entrée](#partie-4--le-parcours-du-code-depuis-le-point-dentrée)
5. [Les tests : comment on prouve que ça marche](#partie-5--les-tests--comment-on-prouve-que-ça-marche)
6. [Travaux pratiques sur l'émulateur](#partie-6--travaux-pratiques-sur-lémulateur)
7. [Préparer l'entretien](#partie-7--préparer-lentretien)
8. [Glossaire](#partie-8--glossaire)

---

## Partie 1 : les bases du streaming vidéo (sans code)

### 1.1 Une vidéo en streaming n'est pas « un fichier »

Quand tu regardes un film sur une plateforme de streaming, ton téléphone ne télécharge pas un
gros fichier `film.mp4`. Le film a été **découpé** côté serveur en petits morceaux de quelques
secondes, appelés **segments** (2 à 6 s en général). Le player télécharge les segments un par
un, juste un peu en avance sur ce que tu regardes.

Pourquoi découper ?
- **Démarrer vite** : il suffit d'un ou deux segments pour lancer la lecture.
- **S'adapter au réseau** : chaque segment existe en **plusieurs qualités** (240p, 480p, 720p,
  1080p…). Si ton débit chute dans le métro, le player prend le segment suivant dans une qualité
  plus basse. C'est l'**ABR** (*Adaptive BitRate*, débit adaptatif).
- **Sauter n'importe où** (seek) sans tout télécharger.

Pour savoir quels segments existent, le player lit d'abord un fichier d'index : le **manifest**.
Il liste les qualités disponibles, les langues audio, les sous-titres, et l'adresse de chaque
segment.

### 1.2 DASH et HLS : deux formats de manifest

| | MPEG-DASH | HLS |
|---|---|---|
| Origine | Norme internationale ISO | Apple |
| Manifest | Fichier XML `.mpd` | Fichier texte `.m3u8` (playlist) |
| Usage typique | Android, TV, web (avec Widevine) | iOS, Safari, et partout ailleurs |

Un manifest DASH (`.mpd`) est organisé ainsi :
```
MPD (le manifest)
 └─ Period            une tranche de temps (souvent une seule pour un film)
     └─ AdaptationSet un type de flux : la vidéo, l'audio français, les sous-titres…
         └─ Representation  une QUALITÉ précise (ex : 1280x720 à 2 Mbit/s)
              └─ segments
```
L'ABR consiste à choisir, segment après segment, la bonne **Representation**.

Ce projet lit du **DASH**. Le même code lirait du HLS en ajoutant le module `media3-exoplayer-hls`.

### 1.3 Le buffer

Le **buffer** est la réserve de vidéo déjà téléchargée mais pas encore affichée. Tant qu'il en
reste, la lecture est fluide. S'il se vide (réseau trop lent), la vidéo se fige et le player
affiche une roue : c'est le **rebuffering**, l'ennemi n°1 d'une équipe Player.

Le réglage du buffer est un compromis :
- un buffer cible **petit** donne un démarrage rapide, mais un rebuffering plus probable ;
- un buffer cible **grand** donne une lecture robuste, mais un démarrage plus lent et plus de
  données et de mémoire consommées.

### 1.4 La DRM : protéger le contenu

Les ayants droit (studios, chaînes) exigent que les films soient **chiffrés**. Seul un appareil
autorisé peut les déchiffrer. C'est la **DRM** (*Digital Rights Management*).

Sur Android, la DRM s'appelle **Widevine** (de Google). Le fonctionnement, en 5 étapes :

1. Les segments sont chiffrés. Le manifest contient une balise `<ContentProtection>` qui dit
   « ce contenu est protégé par Widevine » et identifie les clés nécessaires.
2. Le player demande au **CDM** (*Content Decryption Module*, le composant Widevine du
   téléphone) de préparer une **requête de licence** (un « challenge »).
3. Le player envoie ce challenge en HTTP POST au **serveur de licence** de la plateforme,
   souvent avec un jeton qui prouve que l'utilisateur est abonné.
4. Le serveur renvoie une **licence** qui contient les clés, chiffrées pour CET appareil.
5. Le CDM déchiffre les segments. Les clés ne sont jamais visibles par l'application.

Deux niveaux de sécurité à connaître :
- **L1** : le déchiffrement ET le décodage se font dans une zone matérielle sécurisée du
  processeur (le **TEE**). Les ayants droit l'exigent pour la HD et la 4K.
- **L3** : tout se fait en logiciel (émulateurs, téléphones rootés ou non certifiés). On est
  souvent limité à la SD.

Conséquence pratique : une vidéo protégée doit s'afficher dans une `SurfaceView` (une surface
gérée par le système). Une `TextureView` produit un **écran noir avec le son**, une panne
classique.

### 1.5 La publicité vidéo : CSAI, VAST, VMAP

**Deux façons d'insérer une pub :**
- **CSAI** (*Client-Side Ad Insertion*) : le **player** met le film en pause, joue un fichier de
  pub séparé, puis reprend le film. **C'est ce que fait ce projet.**
- **SSAI** (*Server-Side Ad Insertion*) : le **serveur** colle la pub directement dans le flux
  vidéo. Le player ne voit qu'un seul flux continu. C'est plus dur à bloquer, mais le tracking
  doit alors passer par des métadonnées.

**Les types de pub selon leur position :**
- **pre-roll** : avant le film ;
- **mid-roll** : au milieu (ex : à 15 min) ;
- **post-roll** : après le film ;
- **pause ad** : une image affichée quand l'utilisateur met en pause (non implémentée ici).

Un ensemble de pubs jouées d'affilée s'appelle un **break** (ou **ad pod**).

**Les deux formats standards de l'IAB** (l'organisme qui normalise la pub en ligne) :
- **VMAP** répond à « **QUAND** passer des pubs ? ». C'est un XML qui liste les breaks :
  « un break au début, un à 15 min, un à la fin ».
- **VAST** répond à « **QUOI** jouer et **QUOI** signaler ? ». C'est un XML qui décrit une pub :
  l'adresse du fichier vidéo (souvent en plusieurs qualités), sa durée, à partir de quand on
  peut la passer (`skipoffset`), l'adresse à ouvrir si on clique, et surtout les **URLs de
  tracking**.

En pratique, l'app interroge un serveur de la plateforme (l'**Ad Proxy** ou *ad server*) qui
renvoie un VMAP. Chaque break du VMAP pointe vers un VAST.

**Les Wrappers.** Une régie publicitaire peut renvoyer un VAST qui ne contient pas la pub, mais
**l'adresse d'un autre VAST** chez une autre régie. C'est un **Wrapper** (une redirection).
On peut en enchaîner plusieurs avant d'atteindre le VAST final (dit **InLine**). Deux règles :
- il faut signaler l'événement (impression, quartiles…) à **chaque** niveau de la chaîne,
  car chaque régie veut savoir que la pub a été vue ;
- il faut limiter la profondeur de la chaîne (ici 5), sinon une boucle bloquerait tout.

### 1.6 Le tracking publicitaire : les « pixels »

Les annonceurs paient à la pub **vue**. Le player doit donc prévenir les régies à chaque étape
en appelant des URLs fournies par le VAST. Ce sont de simples requêtes HTTP GET dont on ignore
la réponse, historiquement appelées **pixels** :
- **impression** : la pub a commencé à s'afficher (c'est ce qui est facturé) ;
- **start**, **firstQuartile** (25 %), **midpoint** (50 %), **thirdQuartile** (75 %),
  **complete** (100 %) ;
- **pause**, **resume**, **mute**, **unmute**, **skip**, **clickTracking** ;
- **error** : la pub n'a pas pu être jouée, avec un **code d'erreur VAST** (ex : 405 = fichier
  illisible, 303 = aucune pub à servir).

**Les macros.** Ces URLs contiennent des « trous » entre crochets que le player doit remplir
juste avant l'appel :
`https://track.example/imp?t=[TIMESTAMP]&cb=[CACHEBUSTING]&err=[ERRORCODE]`
- `[TIMESTAMP]` : l'heure de l'appel ;
- `[CACHEBUSTING]` : un nombre aléatoire, pour qu'aucun cache réseau ne « fusionne » deux
  appels identiques (sinon une impression serait perdue) ;
- `[ERRORCODE]`, `[ADPLAYHEAD]` (position dans la pub), etc.

Le détail de ce sujet, confronté à la spec officielle VAST 4.1, est dans `GUIDE_MACROS_VAST.md`.

### 1.7 La mesure d'audience : un autre tracking

À ne pas confondre avec le tracking pub. Ici, la question est : « combien de temps cet
utilisateur a-t-il regardé ce programme ? ». C'est le métier d'instituts comme **Nielsen** (ou
Médiamétrie en France), ou d'outils maison chez certains diffuseurs.

Le principe : un SDK fourni par l'institut, que le player informe de chaque événement (début,
pause, pub, fin), plus un **battement de cœur** (*heartbeat*, ou *playhead*) envoyé **toutes les
secondes** avec la position de lecture. L'institut reconstitue ensuite la durée vue.

### 1.8 Ce qu'on mesure côté qualité : la QoE

La **QoE** (*Quality of Experience*) regroupe les indicateurs qu'une équipe Player surveille en
production :
- **TTFF** (*Time To First Frame*) : le délai entre le clic et la première image ;
- **taux de rebuffering** : le temps passé figé alors que l'utilisateur veut regarder ;
- **débit moyen** et nombre de changements de qualité ;
- **images perdues** (*dropped frames*) : l'appareil n'arrive pas à suivre ;
- **taux d'erreur**, classé par type (réseau, DRM, décodeur).

---

## Partie 2 : ExoPlayer / Media3, le modèle mental

### 2.1 Media3, ExoPlayer : qui est qui ?

- **ExoPlayer** est le lecteur vidéo open source de Google pour Android. Il est utilisé par
  la plupart des applications de streaming Android, à commencer par YouTube.
- **Media3** (`androidx.media3`) est la bibliothèque Jetpack qui l'héberge aujourd'hui. Avant,
  ExoPlayer vivait dans `com.google.android.exoplayer2`. Migrer de l'un à l'autre est un chantier
  classique (c'est ce qui a été fait chez Bedrock).

Ce projet utilise **Media3 1.11.1**. Les modules utilisés :

| Module | Rôle |
|---|---|
| `media3-exoplayer` | Le moteur de lecture |
| `media3-exoplayer-dash` | La lecture du format DASH |
| `media3-ui` | `PlayerView`, la vue prête à l'emploi (vidéo, contrôles, marqueurs de pubs) |
| `media3-test-utils` (+ `-robolectric`) | Les outils pour tester un vrai player sans téléphone |

Doc d'entrée : https://developer.android.com/media/media3/exoplayer

### 2.2 La chaîne de lecture, du lien au pixel

Retiens ce schéma, il explique 80 % d'ExoPlayer :

```
 MediaItem          « je veux lire CECI » (une description : URL, type, DRM, pubs, titre)
     │
     ▼  MediaSource.Factory
 MediaSource        « je sais lire CE FORMAT » (DashMediaSource pour un .mpd, etc.)
     │  télécharge via un DataSource (HTTP)
     ▼
 Timeline           « voici ce qu'il y a à lire » (durée, périodes, pubs)
     │
     │   LoadControl   décide QUAND charger (combien de buffer garder)
     │   TrackSelector décide QUOI charger (quelle qualité, quelle langue)
     ▼
 Renderers          décodent (via MediaCodec, le décodeur matériel) et affichent
     │              (vidéo sur une Surface, audio sur le haut-parleur, sous-titres)
     ▼
 L'écran
```

En une phrase chacun :
- **`MediaItem`** : une **description**, pas un lecteur. Tu y mets l'URL, le type de flux, la
  configuration DRM, la configuration des pubs et le titre.
- **`MediaSource`** : l'objet qui sait transformer un `MediaItem` en morceaux lisibles. On ne la
  crée presque jamais à la main : la `DefaultMediaSourceFactory` choisit la bonne selon le type.
- **`DataSource`** : la couche réseau (comment on télécharge les octets).
- **`Timeline`** : la structure de ce qui va être lu. Une Timeline contient des **Windows**
  (ce que l'utilisateur voit comme un média) et des **Periods** (les morceaux internes ; avec
  des pubs, la Period du contenu porte les positions des pubs).
- **`LoadControl`** : la politique de buffer (partie 1.3).
- **`TrackSelector`** : la politique de qualité et de langue, donc l'ABR.
- **`Renderer`** : un afficheur par type de piste (vidéo, audio, texte).

### 2.3 Les quatre états du player

À tout instant, `player.playbackState` vaut l'un de ces quatre états :

| État | Signification |
|---|---|
| `STATE_IDLE` | Au repos : rien de préparé, ou arrêté après une **erreur** |
| `STATE_BUFFERING` | Il charge : il ne peut pas encore lire |
| `STATE_READY` | Il peut lire immédiatement |
| `STATE_ENDED` | Il a fini de lire le média |

Et deux booléens à ne **jamais** confondre :
- **`playWhenReady`** : l'**intention**. « L'utilisateur veut que ça joue. » Le bouton pause le
  met à `false`.
- **`isPlaying`** : la **réalité**. « Les images avancent en ce moment. » Il vaut `true` seulement
  si `playWhenReady` est vrai, ET que l'état est READY, ET que rien ne **suspend** la lecture.

Exemple de suspension : pendant un appel téléphonique, l'utilisateur n'a rien demandé
(`playWhenReady` reste `true`), mais le système coupe la lecture. ExoPlayer l'indique avec
`playbackSuppressionReason = TRANSIENT_AUDIO_FOCUS_LOSS`, et `isPlaying` vaut `false`.

Doc : https://developer.android.com/media/media3/exoplayer/listening-to-player-events

### 2.4 La séquence de lancement

```kotlin
player.setMediaItem(mediaItem)   // 1. quoi lire
player.prepare()                 // 2. commence à charger (manifest, licence, pubs) -> BUFFERING
player.playWhenReady = true      // 3. « lis dès que possible » -> READY puis lecture
```
Et à la fin, **toujours** :
```kotlin
player.release()                 // libère les décodeurs et les sessions DRM
```
Pourquoi `release()` est vital : un téléphone n'a qu'un petit nombre de décodeurs matériels
(parfois un seul décodeur sécurisé Widevine). Un player oublié les garde occupés, et le suivant
ne pourra pas démarrer.

### 2.5 Écouter le player : les callbacks et `onEvents`

On s'abonne avec `player.addListener(object : Player.Listener { ... })`. Il existe un callback
par changement : `onPlaybackStateChanged`, `onIsPlayingChanged`, `onPlayerError`,
`onPositionDiscontinuity` (la position a « sauté » : fin d'une pub, seek…), `onMediaItemTransition`
(passage au média suivant)…

**Le piège** : plusieurs changements arrivent souvent ensemble (ex : passage au média suivant
plus changement d'état), et l'ordre des callbacks entre eux n'est pas garanti. Media3 fournit
donc **`onEvents(player, events)`**, appelé **une fois**, **après** tous les callbacks d'un même
cycle, avec la liste de ce qui a changé. On y relit l'état complet du player d'un coup. C'est
la base du tracking d'audience de ce projet (étape 4.8).

### 2.6 Les threads

Un ExoPlayer s'utilise depuis **un seul thread** : celui sur lequel il a été créé (son
`applicationLooper`, ici le thread principal). Tous les callbacks arrivent sur ce thread, et
lire `player.currentPosition` depuis un autre thread est interdit. Le vrai travail (décodage,
réseau) se fait sur des threads internes, sans que tu aies à t'en occuper.

Doc : https://developer.android.com/media/media3/exoplayer/hello-world#a-note-on-threading

### 2.7 Les pubs dans ExoPlayer : `AdsLoader`, `AdsMediaSource`, `AdPlaybackState`

ExoPlayer sait insérer des pubs côté client, mais il ne sait pas parler aux régies. Il délègue
ce travail à un **`AdsLoader`** (une interface) :

1. Si le `MediaItem` a une `AdsConfiguration`, la `DefaultMediaSourceFactory` enveloppe la
   source du film dans une **`AdsMediaSource`**.
2. L'`AdsMediaSource` appelle `adsLoader.start(...)`.
3. L'`AdsLoader` va chercher les pubs, puis **publie** un **`AdPlaybackState`** : « il y a un
   break à 0 s avec 1 pub (fichier X, 10 s), un à 15 s, un à la fin ».
4. ExoPlayer joue alors lui-même les pubs aux bons moments, **dans le même player** que le film.

Pendant une pub : `player.isPlayingAd` vaut `true`, `currentAdGroupIndex` donne le numéro du
break et `currentAdIndexInAdGroup` le numéro de la pub dans le break.

Google fournit un `AdsLoader` tout fait, l'**IMA SDK** (`ImaAdsLoader`). Ce projet écrit le sien
(`VmapAdsLoader`) pour comprendre le mécanisme et garder la main sur un Ad Proxy maison.

**Attention aux unités** : l'`AdPlaybackState` compte en **microsecondes** (µs). L'API `Player`
(`currentPosition`, `duration`) compte en **millisecondes** (ms).

Doc : https://developer.android.com/media/media3/exoplayer/ad-insertion

### 2.8 Les erreurs

Une erreur fatale arrive dans `onPlayerError(error: PlaybackException)`. Le player passe alors
en `STATE_IDLE`, mais garde son média et sa position : un simple `prepare()` relance la lecture
au même endroit.

Les codes d'erreur sont rangés par familles, faciles à retenir :

| Codes | Famille | Exemple |
|---|---|---|
| 1xxx | Divers | 1002 `BEHIND_LIVE_WINDOW` (en direct, on est sorti de la fenêtre de replay) |
| 2xxx | Réseau / I/O | Pas de connexion, HTTP 404 |
| 3xxx | Parsing | Manifest mal formé |
| 4xxx | Décodage | Format non supporté par l'appareil |
| 6xxx | DRM | Licence refusée (6004 `LICENSE_ACQUISITION_FAILED`) |

### 2.9 Les opt-in « UnstableApi »

Beaucoup d'API avancées de Media3 sont marquées `@UnstableApi` : elles peuvent changer d'une
version à l'autre. Les utiliser est normal dans un player professionnel, mais il faut le
déclarer avec `@OptIn(UnstableApi::class)`. Tu en verras partout dans `:player:engine`.

---

## Partie 3 : l'architecture du projet

### 3.1 Les cinq modules

```
:app ──► :feature:player ──► :player:engine ──► :core:domain ◄── :core:data
  └──────────────────── câblage (AppContainer) ──────────────────────┘
```

| Module | Type | Contenu | Connaît Media3 ? |
|---|---|---|---|
| `:core:domain` | Kotlin pur (pas d'Android) | Modèles métier, interfaces, use cases, règles | Non |
| `:core:data` | Kotlin pur | Réseau, lecture des XML VMAP/VAST, tracking, Nielsen | Non |
| `:player:engine` | Bibliothèque Android | **Tout ce qui touche à Media3** | **Oui, et c'est le seul** |
| `:feature:player` | Bibliothèque Android + Compose | Écrans et ViewModels | Juste `PlayerView` et l'interface `Player` |
| `:app` | Application | Point d'entrée et câblage des dépendances | Non |

### 3.2 Pourquoi ce découpage ?

C'est la **Clean Architecture** : les flèches de dépendance pointent toujours **vers le
domaine**, qui ne dépend de rien.
- Les règles métier (« la pub ne doit jamais bloquer le film », « une session de mesure se
  termine une seule fois ») sont en **Kotlin pur** : elles se testent en millisecondes, sans
  téléphone.
- L'écran ne peut **pas** appeler directement le parser VAST : la frontière est imposée par le
  compilateur.
- Pour une version TV, on ajouterait `:feature:player-tv` en réutilisant tout le reste.

### 3.3 L'injection de dépendances « à la main »

Pas de Hilt ici. Un seul objet, `AppContainer`, crée toutes les implémentations et les passe
aux classes qui en ont besoin. C'est exactement ce que Hilt génère pour toi. Le faire à la main
permet de voir le graphe en entier.

Doc : https://developer.android.com/training/dependency-injection/manual

---

## Partie 4 : le parcours du code, depuis le point d'entrée

On suit ce qui se passe quand on lance l'app, qu'on choisit « Tears of Steel — DASH + Widevine
+ VMAP », qu'on regarde, puis qu'on quitte l'écran.

### Étape 4.1 : le démarrage, `MediaSampleApplication` puis `AppContainer`

📄 `app/src/main/java/com/devsparkles/media3sample/MediaSampleApplication.kt`

C'est la toute première classe exécutée (déclarée dans le `AndroidManifest.xml`). Elle crée un
seul `AppContainer` pour tout le processus.

📄 `app/src/main/java/com/devsparkles/media3sample/di/AppContainer.kt`

Lis-le de haut en bas, c'est le plan de l'application :
1. `applicationScope` : un scope de coroutines qui vit aussi longtemps que l'app. Il sert aux
   pixels de tracking, qui doivent partir même si l'utilisateur quitte l'écran.
2. `httpClient` : le client HTTP des pubs (`UrlConnectionHttpClient`).
3. `adTracker` : l'envoi des pixels VAST (`HttpAdTracker`).
4. `adRepository` : la chaîne VMAP → VAST (`AdRepositoryImpl`).
5. `contentRepository` : le catalogue de démo (`FakeContentRepository`).
6. `playbackTrackers` : les outils de mesure d'audience (Nielsen simulé + outil maison).
7. `playerFactory` : l'usine à players, qui reçoit tout ce dont elle a besoin.

C'est le **seul** endroit du projet qui connaît les classes concrètes de `:core:data`.

### Étape 4.2 : l'écran d'accueil, `MainActivity` et le catalogue

📄 `app/src/main/java/com/devsparkles/media3sample/MainActivity.kt`

Une navigation minimaliste à deux écrans, pilotée par une simple variable `selectedContentId` :
`null` affiche le catalogue, un identifiant affiche le player. `rememberSaveable` garde la
sélection à la rotation de l'écran.

📄 `feature/player/src/main/kotlin/.../catalog/CatalogScreen.kt` et
📄 `core/data/src/main/kotlin/.../content/FakeContentRepository.kt`

Le catalogue contient **quatre contenus de démo** qui couvrent les cas du métier :

| Contenu | Ce qu'il démontre |
|---|---|
| Tears of Steel, DASH + Widevine + VMAP | Le cas complet : DRM et pre/mid/post-roll |
| Tears of Steel, DASH en clair + pre-roll skippable | Un VAST seul (sans VMAP) avec bouton « Passer » |
| Tears of Steel, DASH + Widevine sans pub | La DRM seule |
| Démo d'erreur : licence Widevine invalide | Le serveur de licence répond 401 : chemin d'erreur DRM |

Les flux viennent des serveurs de démo de Google (Widevine) et les pubs des tags d'exemple de
Google IMA (DoubleClick).

### Étape 4.3 : l'ouverture du player, `ScreenStores` et `PlayerViewModel`

📄 `app/src/main/java/com/devsparkles/media3sample/ScreenStores.kt`

Un problème subtil : par défaut, un ViewModel vit aussi longtemps que l'Activity. En revenant
au catalogue, le player resterait donc en mémoire avec ses décodeurs. `ScreenStores` donne à
chaque écran sa propre boîte de ViewModels, qu'on vide au retour arrière. Résultat :
- à la **rotation**, le player survit (pas de rechargement, pas de nouvelle requête de licence) ;
- au **retour arrière**, `clear()` → `PlayerViewModel.onCleared()` → `release()`.

📄 `feature/player/src/main/kotlin/.../PlayerViewModel.kt`

Le ViewModel **possède le player**. Dès sa construction :
1. `playerFactory.create()` fabrique le player (étape suivante) ;
2. il s'abonne au player (buffering, erreurs) et à la pub en cours (pour l'overlay) ;
3. `loadContent()` lance la lecture.

Il expose un **état unique** (`PlayerUiState` : titre, buffering, pub en cours, erreur) que
l'écran se contente d'afficher. C'est le principe UDF (*Unidirectional Data Flow*) : l'état
descend vers l'UI, les actions (`retry`, `skipAd`) remontent vers le ViewModel.

### Étape 4.4 : la fabrication du player, `PlayerFactory` (le fichier clé)

📄 `player/engine/src/main/kotlin/.../PlayerFactory.kt`

Lis `create()` section par section. Chaque section correspond à une case du schéma de la
partie 2.2 :

1. **DataSource** : `DefaultHttpDataSource` (HTTP), avec un user-agent, des timeouts de 8 s et
   le suivi des redirections http→https. En production, on utiliserait souvent OkHttp ou Cronet.
2. **DRM** : `DefaultDrmSessionManagerProvider`. Pour chaque contenu protégé, il ouvre une
   session Widevine et envoie la requête de licence **avec la même pile HTTP** que la vidéo.
3. **Pubs** : crée notre `VmapAdsLoader` et un `DeferredAdViewProvider` (expliqué plus bas).
4. **MediaSourceFactory** : `DefaultMediaSourceFactory`, branchée sur la DRM et sur les pubs
   (`setLocalAdInsertionComponents`). On y règle aussi 5 essais par segment en cas d'échec
   réseau, au lieu de 3 par défaut.
5. **LoadControl** : le buffer. 15 s minimum, 50 s maximum, **1,5 s** pour démarrer, **3 s**
   pour repartir après un rebuffering (plus, pour éviter d'enchaîner les micro-coupures).
6. **TrackSelector** : préférence pour l'audio et les sous-titres en français. L'ABR est
   automatique.
7. **Le player** : `ExoPlayer.Builder` avec la gestion du **focus audio** (pause si une autre
   app prend le son) et la pause quand on **débranche le casque**.
8. **Observabilité** : `PlaybackAnalyticsLogger` (nos indicateurs QoE) et `EventLogger`
   (le journal de debug fourni par Media3).
9. **Mesure d'audience** : `PlayerEventTranslator` branché sur un `CompositeTracker` (étape 4.8).

`create()` renvoie une **`PlayerSession`** qui regroupe tout ce qui vit et meurt avec le player,
pour tout libérer d'un seul appel `release()`, dans le bon ordre.

📄 `player/engine/src/main/kotlin/.../ads/DeferredAdViewProvider.kt`

Un petit problème d'ordre de création : le player est créé dans le ViewModel, **avant** que
l'écran (et donc la `PlayerView`) existe. Or Media3 veut une vue pour les pubs dès la
construction. Cette classe sert de « prise » : l'écran y branche sa `PlayerView` quand il
s'affiche. C'est aussi là que se déclareraient, pour **OMID** (la mesure de visibilité des
pubs), les boutons posés par-dessus la vidéo.

### Étape 4.5 : le lancement, du `VideoContent` au `MediaItem`

Retour dans `PlayerViewModel.loadContent()` :
```kotlin
val content = getPlayableContent(contentId)            // le modèle métier
session.player.setMediaItem(content.toMediaItem())      // traduit en MediaItem
session.player.prepare()
session.player.playWhenReady = true
```

📄 `player/engine/src/main/kotlin/.../MediaItemMapper.kt`

La traduction du modèle métier `VideoContent` vers le `MediaItem` de Media3 :
- `setMediaId(id)` : l'identifiant du contenu (réutilisé par la mesure d'audience) ;
- `setMimeType(APPLICATION_MPD)` : « c'est du DASH ». Ainsi ExoPlayer n'a pas à deviner le
  format d'après l'URL ;
- `setDrmConfiguration(...)` : l'UUID Widevine, l'URL de licence, et
  `setPlayClearContentWithoutKey(true)`, qui permet de jouer les premières secondes non
  chiffrées pendant que la licence arrive (démarrage plus rapide) ;
- `setAdsConfiguration(...)` : l'URL du VMAP. C'est **ce qui déclenche** l'insertion de pubs.

Pourquoi un mapper ? Le domaine (`VideoContent`) ne doit pas connaître Media3. Si demain on
change de lecteur, seul `:player:engine` change.

### Étape 4.6 : ce qui se passe pendant `prepare()`

Trois choses démarrent **en parallèle** :

**a) Le manifest et la DRM.** La `DashMediaSource` télécharge le `.mpd`. Elle y trouve le
`<ContentProtection>` Widevine, ouvre une session DRM et envoie la requête de licence. Si le
serveur répond 401 (contenu « Démo erreur »), on obtient une erreur DRM (famille 6xxx).

**b) Les pubs.** Comme le `MediaItem` a une `AdsConfiguration`, la source du film est enveloppée
dans une `AdsMediaSource`, qui appelle `VmapAdsLoader.start()`. **Le film ne démarre pas tant
que le loader n'a pas publié son `AdPlaybackState`**, car le player doit savoir s'il y a un
pre-roll. D'où le **timeout de 10 s** : si l'Ad Proxy ne répond pas, on publie « aucune pub »
et le film démarre. **La pub ne doit jamais bloquer le contenu.**

**c) Le buffering** démarre dès que le manifest est connu.

### Étape 4.7 : la chaîne publicitaire, du VMAP au tracking

C'est la plus longue étape. Prends ton temps.

**4.7.1 Le loader** : 📄 `player/engine/src/main/kotlin/.../ads/VmapAdsLoader.kt`

Lis d'abord le commentaire de classe : il donne le **cycle de vie** imposé par Media3
(`setPlayer` → `start` → `handlePrepareComplete/Error` → `stop` → `release`).

Dans `start()`, une coroutine :
1. appelle `loadAdSchedule(url)` avec un `withTimeoutOrNull(10 s)` ;
2. convertit le résultat en `AdPlaybackState` (`AdPlaybackStateMapper`) ;
3. le publie avec `eventListener.onAdPlaybackState(state)`.

Détail malin : si `start()` est rappelé pour le **même** contenu (après un « Réessayer »), on
republie l'état existant. Les pubs déjà vues restent marquées « jouées » et ne repassent pas.

**4.7.2 La règle métier** : 📄 `core/domain/src/main/kotlin/.../usecase/UseCases.kt`

`LoadAdScheduleUseCase` nettoie le planning : il retire les breaks vides (la régie n'avait
rien à servir, c'est un *no fill*), trie les pubs d'un break selon leur `sequence`, et garde
au plus un break par position.

`MediaFileSelector` choisit, parmi les fichiers vidéo proposés par le VAST, un format lisible
par ExoPlayer, au débit le plus proche de 2 Mbit/s sans le dépasser. Une pub qui rebuffe est
une pub qu'on ne regarde pas jusqu'au bout.

**4.7.3 L'orchestration réseau** : 📄 `core/data/src/main/kotlin/.../ads/AdRepositoryImpl.kt`

```
VMAP ──► pour chaque break, EN PARALLÈLE (async) :
          ├─ VAST embarqué dans le VMAP, ou téléchargé via son AdTagURI
          └─ pour chaque <Ad> :
               InLine  -> on construit une LinearAd
               Wrapper -> on suit l'adresse (5 niveaux max), en CUMULANT le tracking de chaque niveau
```
Ce qu'il faut savoir expliquer :
- `coroutineScope { async { … } }.awaitAll()` est de la **concurrence structurée** : les breaks
  sont résolus en même temps, et si l'utilisateur quitte l'écran, toutes les requêtes sont
  annulées ensemble ;
- chaque break et chaque Wrapper a son timeout : une régie lente ne retarde que sa pub ;
- `CancellationException` est **toujours relancée**. L'attraper casserait l'annulation des
  coroutines ;
- en cas d'échec, on envoie le bon **code d'erreur VAST** à toutes les URLs `<Error>` de la
  chaîne (100 = XML illisible, 301 = timeout de Wrapper, 302 = trop de Wrappers, 303 = pas de
  pub, 403 = aucun fichier lisible) ;
- tout échec donne « pas de pub pour ce break », jamais un crash.

**4.7.4 La lecture des XML** : 📄 `core/data/src/main/kotlin/.../ads/parser/`

- `XmlSupport.kt` : la lecture sécurisée. Le XML vient d'un tiers, donc on désactive les
  entités externes (attaque **XXE**). On gère les préfixes `vmap:` et les blocs `<![CDATA[…]]>`
  dans lesquels sont presque toujours rangées les URLs.
- `VmapParser.kt` et `VastParser.kt` : des fonctions **pures** (texte XML → objets). Le
  commentaire de chaque classe montre un exemple de XML commenté : lis-le, c'est le meilleur
  résumé des formats.
- `TimeParser.kt` : convertit `"00:00:15.500"`, `"start"`, `"end"`, `"25%"` en millisecondes.

**4.7.5 La conversion pour ExoPlayer** : 📄 `player/engine/src/main/kotlin/.../ads/AdPlaybackStateMapper.kt`

Notre planning devient un `AdPlaybackState` : un « groupe » par break, positionné en
microsecondes (0 pour un pre-roll, `C.TIME_END_OF_SOURCE` pour un post-roll), avec pour chaque
pub son fichier, sa durée et son identifiant. À partir de là, ExoPlayer joue les pubs tout seul
et la `PlayerView` dessine les marqueurs jaunes sur la barre de progression.

**4.7.6 Le tracking pendant la pub** (retour dans `VmapAdsLoader`)

- Un **sondage toutes les 200 ms** (`onProgressTick`) lit la position dans la pub. Le player
  n'émet pas d'événement « la position a changé », ce serait trop fréquent.
- L'**impression** ne part que lorsque la pub **joue vraiment** (`isPlaying`), pas pendant son
  chargement : c'est ce que les régies facturent.
- 📄 `AdProgressTracker.kt` décide quand envoyer start et les quartiles, **une seule fois
  chacun**, même si un saut de position en franchit plusieurs d'un coup.
- **complete** est déclenché par `onPositionDiscontinuity(AUTO_TRANSITION)` : la position
  « saute » de la fin de la pub vers le film. Pour un post-roll, il n'y a pas de film après :
  c'est `STATE_ENDED` qui sert de signal.
- **skip** : `skipCurrentAd()` marque la pub « passée » dans l'`AdPlaybackState`, et ExoPlayer
  la quitte immédiatement.
- **Fichier de pub illisible** : `handlePrepareError` marque la pub en erreur (ExoPlayer la
  saute) et envoie le code 405.

**4.7.7 L'envoi des pixels** : 📄 `core/data/src/main/kotlin/.../ads/HttpAdTracker.kt` et `ads/macro/`

`HttpAdTracker.track(urls, contexte)` lance une coroutine dans le scope **applicatif**. Pour
chaque URL, **juste avant** l'appel, `MacroExpander` remplace les macros. Il applique le
**pattern Strategy** : une classe par macro (`TimestampMacro`, `CacheBustingMacro`…), et un
moteur commun qui trouve les macros, encode les valeurs, et met `-1` pour une valeur inconnue.
Tout le raisonnement est dans `GUIDE_MACROS_VAST.md`.

### Étape 4.8 : la mesure d'audience (Nielsen et outil maison)

Ce module est distinct du tracking pub : il n'utilise ni `AdTracker` ni ses pixels.

**4.8.1 Le traducteur** : 📄 `player/engine/src/main/kotlin/.../tracking/PlayerEventTranslator.kt`

C'est le seul code de la mesure d'audience qui connaît Media3. Son travail :
- dans les callbacks individuels, il note seulement les **raisons** (« c'était un seek »,
  « c'était un repeat », « la pause vient du casque débranché ») ;
- dans **`onEvents`**, il prend une **photographie** complète du player (`PlayerSnapshot` :
  état, `playWhenReady`, `isPlaying`, suppression, pub en cours, positions) et la passe à la
  state machine ;
- un **minuteur d'une seconde** (sur le thread du player) produit les battements de cœur,
  seulement quand une session est active ;
- `release()` est appelé **avant** `player.release()`, pour pouvoir encore lire la position
  finale.

**4.8.2 Le cerveau** : 📄 `core/domain/src/main/kotlin/.../tracking/PlaybackSessionStateMachine.kt`

En Kotlin pur. Elle reçoit des photographies et renvoie des **événements normalisés** :
`SessionStarted`, `Paused`, `Resumed`, `Seeked`, `ContentChanged`, `AdBreakStarted`, `AdStarted`,
`AdBreakEnded`, `Ended`, `Released`, `Tick`. Son commentaire de classe liste les décisions :
- la session démarre au **premier READY** avec `playWhenReady` (le chargement initial ne compte pas) ;
- le **buffering n'est pas une pause** ;
- un **appel téléphonique** est une **interruption**, distincte d'une pause utilisateur ;
- un **seek** n'est qu'un événement `Seeked` ;
- une session se termine **au plus une fois**, quel que soit le chemin (fin, erreur, sortie).

**4.8.3 Le contrat et la diffusion** : 📄 `PlaybackTracker.kt`, `PlaybackEvent.kt`, `CompositeTracker.kt`

`PlaybackTracker` est l'interface que tout outil de mesure implémente (pattern **Adapter**).
`CompositeTracker` diffuse chaque événement à tous les outils, en **isolant** les erreurs : un
SDK tiers qui plante ne casse ni la lecture ni les autres outils.

**4.8.4 Les outils** : 📄 `core/data/src/main/kotlin/.../tracking/`

- `nielsen/NielsenTracker.kt` applique les règles du SDK Nielsen : `play()` + `loadMetadata()`
  au démarrage, `setPlayheadPosition()` chaque seconde, `stop()` à chaque pause et autour de
  chaque pub, `end()` à la fin. Chaque règle cite sa page de documentation Nielsen. Le
  **flush** (fermer proprement une session restée ouverte avant d'en ouvrir une nouvelle) vit
  à **un seul endroit**. Le vrai SDK n'est pas inclus : `LoggingNielsenSdkGateway` écrit chaque
  appel dans Logcat.
- `inhouse/InHouseTracker.kt` : un outil maison générique. Il n'a pas les contraintes Nielsen,
  garde la cause des pauses et envoie un heartbeat toutes les 10 s seulement.

Le tableau complet « callback Media3 → événement → appel Nielsen » est dans
`GUIDE_ENTRETIEN.md`, section 7.

### Étape 4.9 : l'écran, `PlayerScreen`

📄 `feature/player/src/main/kotlin/.../PlayerScreen.kt`

- La `PlayerView` (une vue Android classique) est intégrée dans Compose via `AndroidView`.
  Elle utilise une `SurfaceView` par défaut, obligatoire pour la DRM (partie 1.4).
- `onRelease` détache le player de la vue quand elle quitte l'écran, pour éviter une fuite.
- `LifecycleEventEffect(ON_STOP)` met en pause quand l'app passe en arrière-plan. On utilise
  `ON_STOP` et pas `ON_PAUSE` : en multi-fenêtre, l'app est « en pause » mais toujours visible.
  `ON_START` signale le retour au premier plan.
- `AdOverlay` affiche « Publicité 1/2 · 12 s », « Passer dans 3 s » puis « Passer ▸ », et
  « En savoir plus ».
- `ErrorOverlay` traduit l'erreur en message lisible, avec un bouton « Réessayer » si l'erreur
  s'y prête.

### Étape 4.10 : quand ça casse, le chemin d'une erreur

```
ExoPlayer : onPlayerError(PlaybackException, code 6004)
   └─► PlayerViewModel : error.toPlayerError()           (PlaybackErrorMapper.kt)
         └─► PlayerError.Drm("…")  dans PlayerUiState
               └─► ErrorOverlay : « Ce contenu protégé ne peut pas être lu »
```
📄 `player/engine/src/main/kotlin/.../error/PlaybackErrorMapper.kt` range les codes par famille
(partie 2.8) dans un type métier, `PlayerError` (📄 `core/domain/.../model/PlayerError.kt`).
Chaque type indique si on peut réessayer : oui pour le réseau, non pour la DRM ou le décodeur.

**Réessayer** : `retry()` appelle simplement `prepare()`. Le player a gardé son média et sa
position.

**Cas particulier du direct** : `BEHIND_LIVE_WINDOW` (en pause trop longtemps sur un direct, la
position est sortie de la fenêtre de replay). On ne montre pas d'erreur : on se recale sur le
direct (`seekToDefaultPosition()` puis `prepare()`).

**Observé sur l'émulateur** : le contenu « licence invalide » **joue environ 10 s** avant
l'erreur. Tears of Steel commence par une partie non chiffrée, et grâce à
`setPlayClearContentWithoutKey(true)`, ExoPlayer la joue pendant que la licence échoue.
L'erreur fatale n'arrive qu'au premier segment chiffré.

### Étape 4.11 : observer, `PlaybackAnalyticsLogger`

📄 `player/engine/src/main/kotlin/.../analytics/PlaybackAnalyticsLogger.kt`

Un `AnalyticsListener` reçoit **tous** les événements internes du player (chargements,
formats, DRM, images perdues). On y calcule les indicateurs de la partie 1.8 : TTFF, temps de
buffering cumulé, changements de qualité, durée de la requête de licence. En production, ces
mesures partiraient vers Datadog, Firebase, Conviva ou Mux.

📄 `player/engine/src/main/kotlin/.../drm/WidevineCapabilities.kt` sait lire le niveau Widevine
(L1/L3) et HDCP de l'appareil. C'est un utilitaire de diagnostic **non branché** dans l'app : à
citer comme piste (envoyer ces infos au backend, ou plafonner la qualité en L3).

### Étape 4.12 : la sortie de l'écran

```
Retour arrière ─► ScreenStores.clear() ─► PlayerViewModel.onCleared()
   └─► PlayerSession.release()
         1. tracking.release()   fin de la mesure d'audience (Nielsen end()), une seule fois
         2. adsLoader.setPlayer(null)
         3. player.release()     libère décodeurs et sessions DRM
         4. adsLoader.release()  annule les coroutines pub
```

Tu as maintenant parcouru tout le code de production. 🎉

---

## Partie 5 : les tests, comment on prouve que ça marche

Lancer tous les tests (sans téléphone) :
```bash
./gradlew test
```

| Où | Ce qui est testé | Technique |
|---|---|---|
| `core/domain/src/test` | Choix du fichier de pub, state machine d'audience (22 scénarios), `CompositeTracker` | JVM pure, données construites à la main |
| `core/data/src/test/.../ads` | Lecture VMAP/VAST, Wrappers, codes d'erreur, envoi des pixels | `FakeHttpClient` qui renvoie des XML d'exemple (`Fixtures.kt`) |
| `core/data/src/test/.../macro` | Chaque macro, encodage, `-1`, une heure par URL | Horloge et aléatoire **injectés** (`clock = { now }`) |
| `core/data/src/test/.../tracking` | **Suite de contrat** commune aux trackers + règles Nielsen | Classe de test abstraite héritée par chaque tracker |
| `player/engine/src/test` | Quartiles ; séquence d'événements avec un **vrai ExoPlayer** (dont pre + mid + post-roll) | Robolectric + `TestExoPlayerBuilder` + `FakeClock` + `FakeMediaSource` |

Quatre techniques à savoir expliquer :
1. **Les fakes plutôt que les mocks** : `FakeHttpClient`, `RecordingNielsenSdk`. Ce sont de
   vraies petites implémentations qui enregistrent ce qu'on leur demande. Les tests restent
   lisibles et ne cassent pas au moindre refactoring.
2. **Injecter le temps** : `MacroExpander(clock = { now })`, `FakeClock`. Un test qui dépend de
   l'heure réelle n'est pas reproductible.
3. **Les tests de contrat** : `PlaybackTrackerContractTest` décrit ce que **tout** tracker doit
   garantir (pas d'appel hors session, jamais deux fins). Nielsen et l'outil maison en héritent.
   Un nouvel outil hérite de la même classe et se teste gratuitement.
4. **Un vrai player sans téléphone** : **Robolectric** simule Android sur la JVM. Avec
   `FakeClock` en mode « auto-avançant », 10 s de vidéo se « lisent » en quelques
   millisecondes, toujours de la même façon.

Deux pièges rencontrés en écrivant ces tests (bons sujets de discussion) :
- `onEvents` arrive au cycle **suivant** du Looper. Un test qui attend `STATE_ENDED` doit
  laisser passer un cycle de plus, sinon le dernier événement n'est pas encore émis.
- Robolectric 4.16 simule au plus Android 36, alors que le projet cible Android 37. Le fichier
  `robolectric.properties` force `sdk=36` pour les tests.

---

## Partie 6 : travaux pratiques sur l'émulateur

Lance l'app depuis Android Studio, ouvre **Logcat** et utilise ces filtres :

| Filtre (tag) | Ce que tu vois |
|---|---|
| `PlaybackTracking` | La timeline « callback Media3 → événement normalisé → appel Nielsen » |
| `Nielsen` | Uniquement les appels au SDK Nielsen simulé |
| `InHouseTracker` | Les beacons de l'outil maison |
| `AdTracker` | Chaque pixel VAST envoyé, macros remplacées |
| `AdRepository` | Les problèmes de la chaîne pub (timeouts, breaks ignorés) |
| `PlaybackQoE` | TTFF, buffering, qualité, licence DRM |
| `EventLogger` | Le journal brut et très bavard de Media3 |

Exercices, du plus simple au plus riche :
1. **Contenu « Widevine + VMAP »** : repère dans `PlaybackQoE` la ligne `DRM keys loaded` et la
   durée de la licence. Repère le TTFF.
2. **Même contenu** : dans `PlaybackTracking`, suis le pre-roll : `AdStarted` → `Nielsen.stop()`
   → `loadMetadata({type=preroll…})`, puis le retour au film. Attends le mid-roll vers 15 s.
3. **Dans `AdTracker`** : retrouve l'impression, puis les quartiles. Vérifie que `[TIMESTAMP]`
   est bien remplacé.
4. **Bouton Home pendant la lecture** : observe `appInBackground()` puis `Paused` → `stop()`.
   Reviens dans l'app : `appInForeground()`.
5. **Contenu « skippable »** : attends « Passer ▸ », clique, et retrouve le pixel `skip`.
6. **Contenu « Démo erreur »** : observe environ 10 s de lecture, puis le 401, l'erreur 6004,
   `Ended(ERROR)` et l'overlay d'erreur.
7. **Retour arrière** : observe `release() → Ended(RELEASED)` puis un seul `Nielsen.end()`.
8. **Mode avion pendant la lecture** (exercice non vérifié par l'auteur, à toi d'observer) : en
   théorie, la vidéo continue tant que le buffer dure (jusqu'à 50 s), puis ExoPlayer réessaie
   (5 fois par segment, cf. `PlayerFactory`) avant une erreur réseau (2xxx). Coupe le mode
   avion et clique « Réessayer ».

---

## Partie 7 : préparer l'entretien

### 7.1 Le pitch du projet en une minute

> « Pour préparer ce poste, j'ai construit un player Media3 mobile qui couvre la fiche de poste :
> lecture DASH avec DRM Widevine, insertion de pubs côté client avec mon propre `AdsLoader`
> piloté par un VMAP (pre, mid et post-roll, skip, tracking VAST avec les macros conformes à la
> spec 4.1), et une mesure d'audience multi-outils avec un adapter Nielsen. Le tout est en Clean
> Architecture multi-modules : Media3 ne vit que dans un module, et les règles métier sont en
> Kotlin pur, testées en JVM. Le traducteur Media3 est testé avec un vrai ExoPlayer sous
> Robolectric. J'ai aussi utilisé l'IA, et j'ai appris à vérifier ses propositions contre la
> spec : une de ses suggestions sur `[TIMESTAMP]` était fausse. »

### 7.2 Les questions probables, avec la réponse courte

**Fondamentaux ExoPlayer**
- *Décris l'architecture d'ExoPlayer.* `MediaItem` → `MediaSource` (via la factory) →
  `Timeline` ; `LoadControl` décide quand charger, `TrackSelector` quoi charger ; les
  `Renderer`s décodent et affichent.
- *`playWhenReady` ou `isPlaying` ?* L'intention de l'utilisateur, ou la réalité. Exemple : un
  appel téléphonique laisse `playWhenReady` à vrai, mais `isPlaying` passe à faux.
- *Pourquoi `release()` ?* Les décodeurs matériels et les sessions DRM sont rares. Un player non
  libéré empêche le suivant de démarrer.
- *Les threads ?* Un seul thread applicatif pour toutes les interactions. Le reste est interne.
- *`onEvents` plutôt que les callbacks ?* Pour lire un état cohérent une fois par cycle, sans
  dépendre de l'ordre des callbacks.

**Streaming**
- *DASH ou HLS ?* Norme ISO avec un manifest XML, contre le format d'Apple avec des playlists
  texte. ExoPlayer lit les deux.
- *Réduire le temps de démarrage ?* Baisser le buffer de démarrage, jouer le début en clair
  pendant que la licence arrive, précharger (`PreloadManager`), démarrer en basse qualité.
- *Un rebuffering en boucle ?* Regarder les erreurs de chargement, l'estimation de débit et le
  buffer de redémarrage, puis plafonner la qualité.

**DRM**
- *L1 ou L3 ?* Matériel sécurisé (HD/4K) contre logiciel (souvent SD).
- *Écran noir avec le son ?* `TextureView` avec un contenu protégé, ou HDCP insuffisant sur un
  écran externe.
- *Hors-ligne ?* Licence persistante (`OfflineLicenseHelper`) et `keySetId`.

**Publicité**
- *VMAP ou VAST ?* Quand passer les pubs, contre quoi jouer et quoi signaler.
- *Un Wrapper ?* Une redirection entre régies. On signale à chaque niveau et on limite la profondeur.
- *L'Ad Proxy ne répond pas ?* Timeout de 10 s, puis le film démarre sans pub.
- *Une pub illisible ?* Elle est marquée en erreur, ExoPlayer la saute, et on envoie le code 405.
- *Pourquoi ne pas utiliser l'IMA SDK ?* Il fait tout (y compris OMID), mais une implémentation
  maison se justifie avec un Ad Proxy propriétaire ou un besoin de contrôle total.
- *Les pause ads ?* Hors pub, `onIsPlayingChanged(false)` → afficher une image en overlay et
  envoyer l'impression à l'affichage.
- *OMID ? SIMID ?* La mesure de visibilité des pubs, et les pubs interactives dans une WebView.

**Mesure d'audience**
- *Différence avec le tracking pub ?* Le tracking pub signale des événements aux régies. La
  mesure d'audience mesure la durée regardée avec un playhead chaque seconde.
- *Le buffering, c'est une pause pour Nielsen ?* Non : on arrête d'envoyer le playhead, sans
  appeler `stop()` (c'est écrit dans la FAQ Nielsen).
- *Comment éviter une double fin de session ?* Un seul endroit émet la fin, une fin idempotente
  côté tracker, et une suite de tests de contrat.

**Architecture et Kotlin**
- *Pourquoi le player dans le ViewModel ?* Il survit à la rotation. Pour jouer en arrière-plan,
  on le déplacerait dans un `MediaSessionService`.
- *La concurrence structurée ?* `coroutineScope` + `async` : tout est annulé ensemble. On relance
  toujours `CancellationException`.
- *Pourquoi Kotlin pur dans le domaine ?* Des tests en millisecondes, et un domaine qui ne
  dépend pas du lecteur vidéo.

### 7.3 Les limites du projet, à dire toi-même avant qu'on te les fasse remarquer

- Pas de lecture en arrière-plan ni de notification (`MediaSessionService`), pas de Picture-in-Picture.
- Pas de pubs non linéaires, de compagnons, de pause ads, ni d'OM SDK.
- Les pixels ne sont pas mis en file d'attente persistante (WorkManager) : hors ligne, ils sont perdus.
- Le SDK Nielsen est simulé. `WidevineCapabilities` n'est pas branché.
- Pas de contenu en direct dans le catalogue : `BEHIND_LIVE_WINDOW` n'est testé qu'en JVM.
- Mobile uniquement : la TV demanderait un module dédié (navigation à la télécommande, focus).

Savoir dire ses limites, avec la solution qu'on y apporterait, montre la maturité qu'on attend
d'un profil senior.

### 7.4 Comment réviser

1. Relis la partie 2 jusqu'à pouvoir dessiner le schéma 2.2 de mémoire.
2. Refais le parcours de la partie 4 en ouvrant chaque fichier, sans le guide.
3. Fais les exercices 1, 2, 4 et 6 de la partie 6.
4. Lis les questions 7.2 à voix haute et réponds avant de lire la réponse.
5. La veille : `GUIDE_ENTRETIEN.md` (fiches) et `GUIDE_MACROS_VAST.md` (le récit STAR).

---

## Partie 8 : glossaire

| Terme | Définition |
|---|---|
| **ABR** | *Adaptive BitRate* : changer de qualité segment par segment selon le débit |
| **Ad pod / break** | Plusieurs pubs jouées d'affilée |
| **Ad Proxy / ad server** | Serveur de la plateforme qui renvoie le VMAP |
| **`AdPlaybackState`** | Structure Media3 qui décrit où sont les pubs et dans quel état |
| **`AdsLoader`** | Interface Media3 : « va chercher les pubs et dis-moi où elles sont » |
| **CDM** | *Content Decryption Module* : le composant Widevine de l'appareil |
| **CSAI / SSAI** | Insertion de pub par le player, ou par le serveur dans le flux |
| **DASH** | Format de streaming normalisé ISO, avec un manifest `.mpd` |
| **DRM** | Protection du contenu par chiffrement et licence |
| **Heartbeat / playhead** | Position de lecture envoyée régulièrement à un outil de mesure |
| **HLS** | Format de streaming d'Apple, avec des playlists `.m3u8` |
| **Impression** | Signal « la pub a commencé à s'afficher », ce qui est facturé |
| **InLine / Wrapper** | VAST qui contient la pub, ou VAST qui redirige vers un autre VAST |
| **L1 / L3** | Niveaux de sécurité Widevine : matériel ou logiciel |
| **Licence** | Réponse du serveur DRM qui contient les clés de déchiffrement |
| **Macro** | Trou `[NOM]` dans une URL de tracking, rempli par le player |
| **Manifest** | Index d'un flux : qualités, langues, adresses des segments |
| **`MediaItem`** | Description Media3 d'un média à lire |
| **`MediaSource`** | Objet Media3 qui sait lire un format donné |
| **No fill** | La régie n'avait aucune pub à servir (VAST vide) |
| **OMID** | Standard IAB de mesure de la visibilité des pubs |
| **Period / Window** | Morceaux de la `Timeline` Media3 (interne / vu par l'utilisateur) |
| **Pixel** | Requête HTTP GET de tracking dont on ignore la réponse |
| **QoE** | *Quality of Experience* : TTFF, rebuffering, qualité, erreurs |
| **Quartiles** | Signaux à 25 %, 50 % et 75 % d'une pub |
| **Rebuffering** | Arrêt de la lecture parce que le buffer est vide |
| **Segment** | Morceau de quelques secondes d'une vidéo en streaming |
| **SIMID** | Standard IAB des pubs interactives (WebView par-dessus le player) |
| **Skipoffset** | Moment à partir duquel une pub peut être passée |
| **TEE** | Zone matérielle sécurisée du processeur (Widevine L1) |
| **TTFF** | *Time To First Frame* : délai avant la première image |
| **VAST** | Format IAB qui décrit une pub et ses URLs de tracking |
| **VMAP** | Format IAB qui décrit quand passer les breaks |
| **Widevine** | La DRM de Google, native sur Android |
| **XXE** | Attaque par entité XML externe, bloquée dans `XmlSupport` |

---

### Pour aller plus loin (documentation officielle)
- Media3 / ExoPlayer : https://developer.android.com/media/media3/exoplayer
- Personnaliser ExoPlayer : https://developer.android.com/media/media3/exoplayer/customization
- DASH : https://developer.android.com/media/media3/exoplayer/dash
- DRM : https://developer.android.com/media/media3/exoplayer/drm
- Insertion de pubs : https://developer.android.com/media/media3/exoplayer/ad-insertion
- Événements du player : https://developer.android.com/media/media3/exoplayer/listening-to-player-events
- Analytics : https://developer.android.com/media/media3/exoplayer/analytics
- Lecture en arrière-plan : https://developer.android.com/media/media3/session/background-playback
- Sources de Media3 : https://github.com/androidx/media
- Standards IAB (VAST, VMAP, OMID, SIMID) : https://iabtechlab.com/standards/
- Nielsen DCR Android : https://engineeringportal.nielsen.com/wiki/DCR_Video_Android_SDK
- Architecture Android : https://developer.android.com/topic/architecture
