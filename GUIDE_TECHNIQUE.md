# Guide technique : comprendre un player vidéo Android, de zéro à l'entretien

> **Pour qui ?** Pour quelqu'un qui connaît Android et Kotlin, mais qui n'a **jamais** travaillé
> dans le streaming et ne connaît **pas** ExoPlayer.
>
> **Comment le lire ?** **D'une traite, du début à la fin, sans revenir en arrière.** Chaque
> notion est expliquée avant d'être utilisée. Quand une idée déjà vue resservira, elle sera
> rappelée en une phrase sur place : tu n'auras jamais besoin de remonter.
>
> Les liens vers la documentation officielle servent à **approfondir plus tard**. Tu n'as pas
> besoin de les ouvrir pour comprendre.
>
> **Le chemin** :
> 1. Le streaming vidéo expliqué sans code.
> 2. Comment ExoPlayer fonctionne.
> 3. Comment le projet est découpé.
> 4. Le code lui-même, dans l'ordre où il s'exécute, du lancement de l'app à la fermeture de l'écran.
> 5. Comment on prouve que ça marche (les tests).
> 6. Des exercices sur l'émulateur.
> 7. La préparation de l'entretien.
>
> **Durée** : environ 4 heures. Tu peux t'arrêter à la fin de n'importe quelle partie : chacune
> se termine par un encadré « À retenir ».

---

## Partie 1 : le streaming vidéo, sans code

**Ce que tu vas apprendre** : comment une vidéo arrive sur un téléphone, comment on la protège,
comment on y insère des pubs, et ce qu'on mesure.

### 1.1 Une vidéo en streaming, ce n'est pas un fichier

Quand tu regardes un film sur une plateforme, ton téléphone ne télécharge pas un gros fichier
`film.mp4`. Côté serveur, le film a été **découpé en petits morceaux** de quelques secondes
(2 à 6 s en général), appelés **segments**. Le player les télécharge un par un, juste un peu
en avance sur ce que tu regardes.

Pourquoi découper ?
- **Démarrer vite** : un ou deux segments suffisent pour lancer la lecture.
- **S'adapter au réseau** : chaque segment existe en **plusieurs qualités** (240p, 480p, 720p,
  1080p…). Quand ton débit baisse (dans le métro, par exemple), le player prend le segment
  suivant dans une qualité plus basse, et remonte quand le réseau revient. On appelle ça
  l'**ABR** (*Adaptive BitRate*, « débit adaptatif »).
- **Sauter n'importe où** dans la vidéo (faire un **seek**) sans tout télécharger.

Pour savoir quels segments existent, le player lit d'abord un fichier d'index : le
**manifest**. Il liste les qualités disponibles, les langues audio, les sous-titres et
l'adresse de chaque segment.

> **Analogie** : le manifest est le sommaire d'un livre, les segments sont les pages. Et chaque
> page existe en plusieurs tailles d'impression, selon la vitesse à laquelle tu peux la recevoir.

### 1.2 DASH et HLS : deux formats de manifest

Il existe deux formats principaux :

| | MPEG-DASH | HLS |
|---|---|---|
| Origine | Norme internationale (ISO) | Apple |
| Manifest | Fichier XML `.mpd` | Fichier texte `.m3u8` |
| Usage typique | Android, TV, web | iOS, Safari, mais lu partout |

Un manifest DASH est rangé comme des poupées russes :
```
MPD (le manifest)
 └─ Period              une tranche de temps (souvent une seule pour un film)
     └─ AdaptationSet   un type de flux : la vidéo, l'audio français, les sous-titres…
         └─ Representation  UNE qualité précise (ex : 1280x720 à 2 Mbit/s)
              └─ segments
```
Faire de l'ABR, c'est choisir, segment après segment, la bonne **Representation**.

**Ce projet lit du DASH.**

### 1.3 Le buffer : la réserve qui évite les coupures

Le **buffer** est la réserve de vidéo déjà téléchargée, mais pas encore affichée. Tant qu'il
reste de la réserve, la lecture est fluide. Si elle se vide parce que le réseau est trop lent,
l'image se fige et une roue tourne : c'est le **rebuffering**, l'ennemi n°1 d'une équipe Player.

Régler le buffer est un compromis :
- **petite réserve exigée avant de démarrer** : démarrage rapide, mais coupures plus probables ;
- **grande réserve** : lecture robuste, mais démarrage plus lent, et plus de données et de
  mémoire consommées.

### 1.4 La DRM : empêcher la copie

Les ayants droit (studios, chaînes) exigent que leurs films soient **chiffrés** : seuls des
appareils autorisés doivent pouvoir les lire. C'est la **DRM** (*Digital Rights Management*).

Sur Android, la DRM s'appelle **Widevine** (de Google). Elle fonctionne en cinq temps :

1. Les segments sont chiffrés. Le manifest contient une balise `<ContentProtection>` qui
   signifie « ce contenu est protégé par Widevine » et identifie les clés nécessaires.
2. Le player demande au **CDM** (*Content Decryption Module*, la partie Widevine du téléphone)
   de préparer une **requête de licence**.
3. Le player envoie cette requête en HTTP POST au **serveur de licence** de la plateforme,
   souvent avec un jeton qui prouve que l'utilisateur est abonné.
4. Le serveur renvoie une **licence** : les clés de déchiffrement, elles-mêmes chiffrées pour
   CET appareil précis.
5. Le CDM déchiffre les segments. L'application ne voit jamais les clés.

Il existe deux niveaux de sécurité Widevine :
- **L1** : le déchiffrement ET le décodage se font dans une zone matérielle isolée du
  processeur, le **TEE** (*Trusted Execution Environment*). Les ayants droit l'exigent pour la
  HD et la 4K.
- **L3** : tout se fait en logiciel. C'est le cas des émulateurs et des téléphones rootés ou
  non certifiés, et la qualité est souvent limitée à la SD.

Une conséquence très concrète : une vidéo protégée doit s'afficher dans une **`SurfaceView`**,
une zone d'affichage gérée directement par le système. Si on utilise une **`TextureView`** à
la place, on obtient la panne classique : **écran noir, mais avec le son**.

### 1.5 La publicité vidéo

**Deux façons d'insérer une pub :**
- **CSAI** (*Client-Side Ad Insertion*) : le **player** interrompt le film, joue un fichier de
  pub séparé, puis reprend le film. **C'est ce que fait ce projet.**
- **SSAI** (*Server-Side Ad Insertion*) : le **serveur** colle la pub directement dans le flux
  vidéo. Le player ne voit qu'un flux continu. C'est plus difficile à bloquer, mais il est plus
  difficile de savoir quand une pub commence.

**Le vocabulaire des positions :**
- **pre-roll** : avant le film ;
- **mid-roll** : pendant le film (par exemple à 15 minutes) ;
- **post-roll** : après le film ;
- **pause ad** : une image affichée quand l'utilisateur met en pause (non faite ici).

Plusieurs pubs jouées d'affilée forment un **break** (on dit aussi **ad pod**).

**Les deux formats standards.** Ils sont définis par l'**IAB**, l'organisme qui normalise la
publicité en ligne :
- **VMAP** répond à « **QUAND** passer des pubs ? ». C'est un XML qui liste les breaks, par
  exemple « un au début, un à 15 min, un à la fin ».
- **VAST** répond à « **QUOI** jouer, et **QUOI** signaler ? ». C'est un XML qui décrit une pub :
  - l'adresse du fichier vidéo, souvent proposé en plusieurs qualités ;
  - sa durée ;
  - le moment à partir duquel on peut la passer (`skipoffset`) ;
  - la page à ouvrir si on clique ;
  - et surtout les **URLs de tracking** (section suivante).

En pratique, l'app interroge un serveur de la plateforme, l'**Ad Proxy** (ou *ad server*), qui
renvoie un VMAP. Chaque break du VMAP pointe vers un VAST.

**Les Wrappers.** Une régie publicitaire peut renvoyer un VAST qui ne contient pas la pub, mais
**l'adresse d'un autre VAST**, chez une autre régie. Ce VAST-redirection s'appelle un
**Wrapper**. Le VAST final, qui contient vraiment la pub, s'appelle un **InLine**. Deux règles :
- signaler les événements (section suivante) à **chaque** niveau de la chaîne, car chaque
  régie veut savoir que la pub a été vue ;
- limiter la longueur de la chaîne (ici à 5), sinon une boucle de redirections bloquerait tout.

**Le *no fill*.** Une régie peut aussi n'avoir **aucune pub** à proposer. Elle renvoie alors un
VAST vide. Ce n'est pas une erreur : le break est simplement ignoré.

### 1.6 Le tracking publicitaire : prévenir les régies

Les annonceurs paient à la pub **réellement vue**. Le player doit donc prévenir les régies à
chaque étape, en appelant les URLs fournies par le VAST. Ce sont de simples requêtes HTTP GET
dont on ignore la réponse. Pour des raisons historiques, on les appelle des **pixels**.

Les principaux événements signalés :
- **impression** : la pub a commencé à s'afficher. C'est ce qui est facturé ;
- **start**, **firstQuartile** (25 %), **midpoint** (50 %), **thirdQuartile** (75 %),
  **complete** (100 %) ;
- **pause**, **resume**, **mute**, **unmute**, **skip**, **clickTracking** ;
- **error** : la pub n'a pas pu être jouée. L'appel porte un **code d'erreur VAST**, par
  exemple 405 (« fichier illisible ») ou 303 (« aucune pub »).

**Les macros.** Ces URLs contiennent des trous entre crochets, que le player doit remplir
**juste avant** l'appel :
```
https://track.example/imp?t=[TIMESTAMP]&cb=[CACHEBUSTING]&err=[ERRORCODE]
```
- `[TIMESTAMP]` : l'heure de l'appel ;
- `[CACHEBUSTING]` : un nombre aléatoire. Sans lui, un cache réseau pourrait considérer deux
  appels identiques comme un seul, et une impression serait perdue ;
- `[ERRORCODE]`, `[ADPLAYHEAD]` (la position dans la pub), etc.

La spécification officielle (VAST 4.1) précise des règles fines. Les valeurs doivent être
encodées pour être mises dans une URL. Une valeur inconnue s'écrit `-1`. Une macro inventée
par une régie, absente de la spec, doit être laissée telle quelle.

### 1.7 La mesure d'audience : un autre tracking

À ne pas confondre avec le tracking publicitaire. La question n'est plus « la pub a-t-elle été
vue ? », mais « **combien de temps** cet utilisateur a-t-il regardé ce programme ? ». C'est le
métier d'instituts comme **Nielsen** (ou Médiamétrie en France), ou d'outils maison chez
certains diffuseurs.

Le principe : un **SDK** (bibliothèque) fourni par l'institut. Le player l'informe de chaque
événement (début, pause, pub, fin) et lui envoie **toutes les secondes** la position de lecture.
Ce signal régulier s'appelle le **playhead** ou **heartbeat** (battement de cœur). L'institut
reconstitue ensuite la durée vue.

### 1.8 La qualité d'expérience (QoE)

Ce qu'une équipe Player surveille en production :
- **TTFF** (*Time To First Frame*) : le délai entre le clic et la première image ;
- **taux de rebuffering** : le temps passé figé alors que l'utilisateur veut regarder ;
- **débit moyen** et nombre de **changements de qualité** ;
- **images perdues** (*dropped frames*) : l'appareil n'arrive pas à suivre ;
- **taux d'erreur**, classé par type (réseau, DRM, décodage).

> **À retenir (partie 1)**
> - Une vidéo en streaming = un **manifest** (le sommaire) + des **segments** en plusieurs qualités ; l'**ABR** choisit la qualité.
> - **Widevine** chiffre le contenu ; une **licence** obtenue auprès d'un serveur permet de le lire ; L1 = matériel, L3 = logiciel.
> - **VMAP** = quand passer les pubs, **VAST** = quoi jouer et quoi signaler ; les **pixels** préviennent les régies.
> - La **mesure d'audience** (Nielsen) est un tracking distinct, avec un **playhead** chaque seconde.

---

## Partie 2 : comment ExoPlayer fonctionne

**Ce que tu vas apprendre** : les pièces d'ExoPlayer, ses états, comment l'écouter, et comment
il gère les pubs et les erreurs.

### 2.1 Media3 et ExoPlayer

- **ExoPlayer** est le lecteur vidéo open source de Google pour Android. La plupart des
  applications de streaming Android l'utilisent, à commencer par YouTube.
- **Media3** (`androidx.media3`) est la bibliothèque Jetpack qui le contient aujourd'hui.
  Avant, ExoPlayer était publié sous `com.google.android.exoplayer2`. Migrer de l'un à l'autre
  est un chantier classique.

Ce projet utilise **Media3 1.11.1**, avec ces modules :

| Module | Rôle |
|---|---|
| `media3-exoplayer` | Le moteur de lecture |
| `media3-exoplayer-dash` | La lecture du format DASH |
| `media3-ui` | `PlayerView` : une vue prête à l'emploi (image, boutons, marqueurs de pubs) |
| `media3-test-utils` (+ `-robolectric`) | Des outils pour tester un vrai player sans téléphone |

### 2.2 La chaîne de lecture, du lien jusqu'à l'écran

Voici le schéma le plus important de ce guide :

```
 MediaItem          « je veux lire CECI » (une description : URL, type, DRM, pubs, titre)
     │
     ▼  MediaSource.Factory
 MediaSource        « je sais lire CE FORMAT » (DashMediaSource pour un .mpd, etc.)
     │  télécharge via un DataSource (la couche HTTP)
     ▼
 Timeline           « voici ce qu'il y a à lire » (durées, morceaux, emplacement des pubs)
     │
     │   LoadControl   décide QUAND charger (quelle réserve garder)
     │   TrackSelector décide QUOI charger (quelle qualité, quelle langue)
     ▼
 Renderers          décodent et affichent (image, son, sous-titres)
     ▼
 L'écran
```

Chaque pièce en une phrase :
- **`MediaItem`** : une **description** de ce qu'on veut lire, pas un lecteur. On y met l'URL,
  le type de flux, la configuration DRM, celle des pubs, et le titre.
- **`MediaSource`** : l'objet qui sait transformer un `MediaItem` en morceaux lisibles. On ne la
  crée presque jamais soi-même : la `DefaultMediaSourceFactory` choisit la bonne selon le type.
- **`DataSource`** : la couche réseau (comment on télécharge les octets).
- **`Timeline`** : le plan de ce qui va être lu. Elle contient :
  - des **Windows**, ce que l'utilisateur perçoit comme un média ;
  - des **Periods**, les morceaux internes. Avec des pubs, la Period du film porte les
    positions des pubs.
- **`LoadControl`** : la politique de réserve, c'est-à-dire le buffer.
- **`TrackSelector`** : la politique de qualité et de langue, c'est-à-dire l'ABR.
- **`Renderer`** : un afficheur par type de piste (vidéo, audio, texte). Il s'appuie sur
  **MediaCodec**, le décodeur matériel d'Android.

### 2.3 Les quatre états, et deux booléens à ne pas confondre

À tout instant, `player.playbackState` vaut l'une de ces quatre valeurs :

| État | Signification |
|---|---|
| `STATE_IDLE` | Au repos : rien n'est préparé, ou le player s'est arrêté après une **erreur** |
| `STATE_BUFFERING` | Il charge et ne peut pas encore lire |
| `STATE_READY` | Il peut lire tout de suite |
| `STATE_ENDED` | Il a fini de lire le média |

Deux booléens se ressemblent mais disent des choses différentes :
- **`playWhenReady`** = l'**intention**. « L'utilisateur veut que ça joue. » Le bouton pause le
  met à `false`.
- **`isPlaying`** = la **réalité**. « Les images avancent en ce moment. » Il vaut `true` seulement
  si l'intention est là, ET que l'état est READY, ET que rien ne **suspend** la lecture.

Exemple de suspension : pendant un appel téléphonique, l'utilisateur n'a rien demandé, donc
`playWhenReady` reste à `true`. Mais le système coupe le son de l'app : `isPlaying` passe à
`false`, et ExoPlayer l'explique avec `playbackSuppressionReason = TRANSIENT_AUDIO_FOCUS_LOSS`
(« perte temporaire du focus audio »).

### 2.4 Lancer et arrêter une lecture

```kotlin
player.setMediaItem(mediaItem)   // 1. quoi lire
player.prepare()                 // 2. commence à charger (manifest, licence, pubs) -> BUFFERING
player.playWhenReady = true      // 3. « lis dès que possible » -> READY puis lecture
```
Et quand on a fini, **toujours** :
```kotlin
player.release()                 // libère les décodeurs et les sessions DRM
```
Pourquoi c'est vital : un téléphone a très peu de décodeurs matériels, parfois **un seul**
décodeur sécurisé pour Widevine. Un player oublié le garde occupé, et le suivant ne démarre pas.

### 2.5 Écouter le player : callbacks et `onEvents`

On s'abonne avec `player.addListener(object : Player.Listener { ... })`. Il existe une méthode
par type de changement :
- `onPlaybackStateChanged` : l'état a changé ;
- `onIsPlayingChanged` : `isPlaying` a changé ;
- `onPlayerError` : erreur ;
- `onPositionDiscontinuity` : la position a « sauté » (fin d'une pub, seek…) ;
- `onMediaItemTransition` : passage au média suivant ;
- et d'autres.

**Le piège** : plusieurs changements arrivent souvent ensemble, et l'ordre de ces méthodes entre
elles n'est pas garanti. Media3 fournit donc **`onEvents(player, events)`**. Elle est appelée
**une seule fois**, **après** toutes les autres méthodes d'un même cycle, avec la liste de ce qui
a changé. On y relit l'état complet du player d'un seul coup, et cet état est forcément cohérent.

### 2.6 Un seul thread

Un ExoPlayer s'utilise depuis **un seul thread** : celui sur lequel il a été créé, ici le thread
principal. Sous Android, chaque thread de ce type traite une file de messages, l'un après
l'autre, grâce à un **Looper** ; celui du player s'appelle son `applicationLooper`.
- Tous les callbacks arrivent sur ce thread.
- Lire `player.currentPosition` depuis un autre thread est interdit.
- Le vrai travail (décodage, réseau) se fait sur des threads internes, sans que tu aies à t'en
  occuper.

### 2.7 Les pubs dans ExoPlayer

ExoPlayer sait jouer des pubs, mais il ne sait pas parler aux régies. Il délègue ce travail à
un **`AdsLoader`** (une interface). Le déroulé :

1. Si le `MediaItem` porte une **`AdsConfiguration`** (l'URL du VMAP), la
   `DefaultMediaSourceFactory` enveloppe la source du film dans une **`AdsMediaSource`**.
2. L'`AdsMediaSource` appelle `adsLoader.start(...)`.
3. L'`AdsLoader` va chercher les pubs, puis **publie** un **`AdPlaybackState`**, par exemple :
   « un break à 0 s avec 1 pub (fichier X, 10 s), un à 15 s, un à la fin ».
4. ExoPlayer joue lui-même les pubs aux bons moments, **dans le même player** que le film.

Pendant une pub :
- `player.isPlayingAd` vaut `true` ;
- `currentAdGroupIndex` donne le numéro du break ;
- `currentAdIndexInAdGroup` donne le numéro de la pub dans le break.

Google fournit un `AdsLoader` tout fait, l'**IMA SDK**. Ce projet écrit le sien pour comprendre
le mécanisme et garder la main sur un Ad Proxy maison.

**Attention aux unités** : l'`AdPlaybackState` compte en **microsecondes** (µs, millionièmes de
seconde). L'API `Player` (`currentPosition`, `duration`) compte en **millisecondes** (ms).

### 2.8 Les erreurs

Une erreur fatale arrive dans `onPlayerError(error: PlaybackException)`. Le player passe alors
en `STATE_IDLE`, mais il garde son média et sa position : un simple `prepare()` relance la
lecture au même endroit.

Les codes d'erreur sont rangés par familles :

| Codes | Famille | Exemple |
|---|---|---|
| 1xxx | Divers | 1002 `BEHIND_LIVE_WINDOW` : en direct, on est sorti de la fenêtre de replay |
| 2xxx | Réseau | Pas de connexion, HTTP 404 |
| 3xxx | Lecture du manifest | Manifest mal formé |
| 4xxx | Décodage | Format non supporté par l'appareil |
| 6xxx | DRM | 6004 `LICENSE_ACQUISITION_FAILED` : licence refusée |

### 2.9 Les API « instables »

Beaucoup d'API avancées de Media3 sont marquées `@UnstableApi` : elles peuvent changer d'une
version à l'autre. Les utiliser est normal dans un player professionnel, mais il faut le
déclarer explicitement avec `@OptIn(UnstableApi::class)`. Tu en verras beaucoup dans le code.

> **À retenir (partie 2)**
> - `MediaItem` (description) → `MediaSource` (format) → `Timeline` ; `LoadControl` = quand charger, `TrackSelector` = quoi charger ; `Renderers` = affichage.
> - 4 états (IDLE, BUFFERING, READY, ENDED) ; `playWhenReady` = intention, `isPlaying` = réalité.
> - `onEvents` donne un état cohérent une fois par cycle ; tout se passe sur **un seul thread**.
> - Les pubs : un `AdsLoader` publie un `AdPlaybackState`, ExoPlayer joue les pubs dans le même player.
> - Toujours appeler `release()`.

---

## Partie 3 : comment le projet est découpé

**Ce que tu vas apprendre** : les cinq modules, pourquoi ce découpage, et comment les objets
sont créés.

### 3.1 Les cinq modules

```
:app ──► :feature:player ──► :player:engine ──► :core:domain ◄── :core:data
  └──────────────────── câblage (AppContainer) ──────────────────────┘
```
Une flèche signifie « dépend de ».

| Module | Nature | Contenu | Connaît Media3 ? |
|---|---|---|---|
| `:core:domain` | Kotlin pur (aucun Android) | Modèles métier, interfaces, règles | Non |
| `:core:data` | Kotlin pur | Réseau, lecture des XML VMAP/VAST, tracking, Nielsen | Non |
| `:player:engine` | Bibliothèque Android | **Tout ce qui touche à Media3** | **Oui, le seul** |
| `:feature:player` | Bibliothèque Android + Compose | Écrans et ViewModels | Seulement `PlayerView` et l'interface `Player` |
| `:app` | Application | Point d'entrée, câblage | Non |

### 3.2 Pourquoi ce découpage

C'est la **Clean Architecture** : toutes les dépendances pointent **vers le domaine**, et le
domaine ne dépend de rien.
- Les règles métier sont en **Kotlin pur**, par exemple « la pub ne doit jamais bloquer le
  film » ou « une session de mesure ne se termine qu'une fois ». Elles se testent en
  millisecondes, sans téléphone.
- L'écran ne **peut pas** appeler directement le code qui lit les VAST : le compilateur l'en
  empêche.
- Pour faire une version TV, on ajouterait un module `:feature:player-tv` et on réutiliserait
  tout le reste.

### 3.3 Qui crée les objets ?

Il n'y a pas de bibliothèque d'injection de dépendances (pas de Hilt). Un seul objet,
`AppContainer`, crée toutes les implémentations et les passe aux classes qui en ont besoin.
C'est exactement le code que Hilt générerait. L'écrire à la main permet de voir tout le graphe.

> **À retenir (partie 3)**
> - Cinq modules ; Media3 ne vit que dans `:player:engine`.
> - Le domaine est en Kotlin pur : testable sans téléphone, indépendant du lecteur vidéo.
> - `AppContainer` est le seul endroit où l'on choisit les implémentations.

---

## Partie 4 : le code, dans l'ordre où il s'exécute

**Ce que tu vas apprendre** : ce qui se passe, fichier par fichier, quand on lance l'app, qu'on
choisit le contenu « Tears of Steel — DASH + Widevine + VMAP », qu'on le regarde, puis qu'on
quitte l'écran.

> Ouvre chaque fichier au moment où il est cité. Les chemins sont abrégés : `.../` remplace
> `src/main/kotlin/com/devsparkles/media3sample/<module>`.

### 4.1 Le démarrage de l'app

📄 `app/.../MediaSampleApplication.kt`

C'est la première classe exécutée (elle est déclarée dans `AndroidManifest.xml`). Elle crée un
seul `AppContainer` pour toute la durée de vie de l'app.

📄 `app/.../di/AppContainer.kt`

Lis-le de haut en bas, c'est le plan de l'application :
1. **`applicationScope`** : un scope de coroutines qui vit aussi longtemps que l'app. Il sert aux
   pixels publicitaires, qui doivent partir même si l'utilisateur vient de quitter l'écran.
2. **`httpClient`** : le client HTTP utilisé pour les pubs.
3. **`adTracker`** : l'envoi des pixels VAST.
4. **`adRepository`** : la chaîne qui va du VMAP aux VAST.
5. **`contentRepository`** : le catalogue de démo.
6. **`playbackTrackers`** : les outils de mesure d'audience (un Nielsen simulé et un outil maison).
7. **`playerFactory`** : l'usine à players, qui reçoit tout ce qui précède.

Chacun de ces objets est détaillé plus bas, au moment où il entre en jeu.

### 4.2 L'écran d'accueil et le catalogue

📄 `app/.../MainActivity.kt`

La navigation est volontairement minimaliste : une variable `selectedContentId`. Si elle est
vide, on affiche le catalogue ; sinon, le player. `rememberSaveable` garde la sélection quand
on tourne l'écran.

📄 `feature/player/.../catalog/CatalogScreen.kt` et 📄 `core/data/.../content/FakeContentRepository.kt`

Le catalogue propose **quatre contenus de démo**, chacun choisi pour montrer un cas du métier :

| Contenu | Ce qu'il démontre |
|---|---|
| Tears of Steel, DASH + Widevine + VMAP | Le cas complet : DRM, pre-roll, mid-roll et post-roll |
| Tears of Steel, DASH en clair + pre-roll skippable | Un VAST seul (sans VMAP), avec le bouton « Passer » |
| Tears of Steel, DASH + Widevine sans pub | La DRM seule |
| Démo d'erreur : licence Widevine invalide | Le serveur de licence répond 401 : chemin d'erreur DRM |

Les vidéos viennent des serveurs de démonstration Widevine de Google, et les pubs des exemples
fournis par l'IMA SDK de Google.

### 4.3 L'ouverture du player

📄 `app/.../ScreenStores.kt`

Un problème subtil d'abord. Un ViewModel vit normalement aussi longtemps que l'Activity. En
revenant au catalogue, le player resterait donc en mémoire, avec ses décodeurs. `ScreenStores`
donne à chaque écran sa propre boîte à ViewModels (un `ViewModelStore`), qu'on vide au retour
arrière :
- **à la rotation**, la boîte est conservée : le player survit, sans recharger ni redemander de
  licence ;
- **au retour arrière**, la boîte est vidée : le ViewModel est détruit et le player libéré.

📄 `feature/player/.../PlayerViewModel.kt`

C'est le ViewModel qui **possède le player**. À sa construction, il :
1. fabrique le player avec `playerFactory.create()` (section suivante) ;
2. s'abonne au player (buffering, erreurs) et à la pub en cours (pour l'affichage) ;
3. lance la lecture avec `loadContent()`.

Il expose un **état unique**, `PlayerUiState` (titre, buffering, pub en cours, erreur), que
l'écran se contente d'afficher. Les actions de l'utilisateur (`retry`, `skipAd`) remontent vers
le ViewModel. C'est le principe **UDF** (*Unidirectional Data Flow*) : l'état descend, les
actions montent.

### 4.4 La fabrication du player : `PlayerFactory`, le fichier clé

📄 `player/engine/.../PlayerFactory.kt`

La fonction `create()` monte, dans l'ordre, chaque pièce de la chaîne
« `MediaItem` → `MediaSource` → `Timeline` → `Renderers` » décrite en partie 2 :

1. **DataSource** (la couche HTTP) : `DefaultHttpDataSource`, avec un user-agent, des délais
   maximum de 8 s et le suivi des redirections http → https.
2. **DRM** : `DefaultDrmSessionManagerProvider`. Pour chaque contenu protégé, il ouvre une
   session Widevine et envoie la requête de licence **avec la même couche HTTP** que la vidéo.
3. **Pubs** : il crée notre `VmapAdsLoader` (détaillé en 4.7) et un `DeferredAdViewProvider`
   (expliqué juste après cette liste).
4. **MediaSourceFactory** : `DefaultMediaSourceFactory`, branchée sur la DRM et sur les pubs
   (`setLocalAdInsertionComponents`). On y règle aussi 5 tentatives par segment en cas d'échec
   réseau, au lieu de 3 par défaut.
5. **LoadControl** (le buffer) :
   - 15 s de réserve minimum, 50 s maximum ;
   - **1,5 s** de réserve suffit pour démarrer ;
   - **3 s** pour repartir après une coupure, un peu plus pour ne pas enchaîner les micro-coupures.
6. **TrackSelector** (qualité et langue) : préférence pour l'audio et les sous-titres en
   français. L'ABR est automatique.
7. **Le player** : `ExoPlayer.Builder`, avec deux comportements activés :
   - la gestion du **focus audio** : pause quand une autre app prend le son ;
   - la pause quand on **débranche le casque**.
8. **Observabilité** : `PlaybackAnalyticsLogger` (nos indicateurs de qualité, détaillés en 4.11)
   et `EventLogger` (le journal de debug fourni par Media3).
9. **Mesure d'audience** : le `PlayerEventTranslator`, détaillé en 4.8.

`create()` renvoie une **`PlayerSession`** qui regroupe tout ce qui vit et meurt avec le player.
Ainsi, un seul appel `release()` libère tout, dans le bon ordre.

📄 `player/engine/.../ads/DeferredAdViewProvider.kt`

Un problème d'ordre de création. Le player est créé dans le ViewModel, **avant** que l'écran,
et donc la `PlayerView`, existe. Or Media3 veut connaître la vue des pubs dès la construction.
Cette classe sert de **prise** : l'écran y branche sa `PlayerView` quand il s'affiche.

C'est aussi là qu'on déclarerait, pour **OMID**, les boutons posés par-dessus la vidéo. OMID est
le standard IAB qui mesure si une pub était réellement **visible** à l'écran. Ces boutons ne
doivent pas être comptés comme masquant la pub.

### 4.5 Le lancement : du modèle métier au `MediaItem`

Retour dans `PlayerViewModel.loadContent()` :
```kotlin
val content = getPlayableContent(contentId)            // le modèle métier (VideoContent)
session.player.setMediaItem(content.toMediaItem())      // traduit en MediaItem Media3
session.player.prepare()
session.player.playWhenReady = true
```

📄 `player/engine/.../MediaItemMapper.kt`

Ce fichier traduit `VideoContent` (notre modèle) en `MediaItem` (le modèle de Media3) :
- `setMediaId(id)` : l'identifiant du contenu, réutilisé plus tard par la mesure d'audience ;
- `setMimeType(APPLICATION_MPD)` : « c'est du DASH ». ExoPlayer n'a pas à deviner le format
  d'après l'URL ;
- `setDrmConfiguration(...)` : l'identifiant Widevine, l'URL de licence, et
  `setPlayClearContentWithoutKey(true)`. Ce dernier réglage permet de jouer les premières
  secondes non chiffrées pendant que la licence arrive, donc de démarrer plus vite ;
- `setAdsConfiguration(...)` : l'URL du VMAP. **C'est ce qui déclenche l'insertion des pubs.**

Pourquoi passer par un mapper ? Le modèle métier ne doit pas connaître Media3. Si on change un
jour de lecteur vidéo, seul `:player:engine` est modifié.

### 4.6 Pendant `prepare()` : trois chantiers en parallèle

**a) Le manifest et la DRM.** La `DashMediaSource` télécharge le `.mpd`. Elle y trouve la balise
Widevine `<ContentProtection>`, ouvre une session DRM et envoie la requête de licence. Avec le
contenu « Démo erreur », le serveur répond 401 et l'on obtient une erreur de la famille 6xxx
(détaillée en 4.10).

**b) Les pubs.** Le `MediaItem` porte une `AdsConfiguration`. La source du film est donc
enveloppée dans une `AdsMediaSource`, qui appelle `VmapAdsLoader.start()`. Point essentiel :
**le film ne démarre pas tant que le loader n'a pas publié son `AdPlaybackState`**, parce que le
player doit savoir s'il y a un pre-roll. D'où un **délai maximum de 10 s** : si l'Ad Proxy ne
répond pas à temps, on publie « aucune pub » et le film démarre. Règle d'or : **la pub ne doit
jamais bloquer le contenu.**

**c) Le buffering** démarre dès que le manifest est connu.

### 4.7 La chaîne publicitaire, du VMAP aux pixels

C'est l'étape la plus longue. Elle suit le trajet d'une pub, de la demande à l'Ad Proxy
jusqu'au dernier pixel envoyé.

**4.7.1 Le loader** : 📄 `player/engine/.../ads/VmapAdsLoader.kt`

Commence par le commentaire en tête de classe : il décrit le **cycle de vie** imposé par
Media3. `setPlayer` est appelé avant `prepare()` ; `start` au `prepare()` ;
`handlePrepareComplete` ou `handlePrepareError` quand un fichier de pub est prêt ou illisible ;
`stop` puis `release` à la fin.

Dans `start()`, une coroutine :
1. appelle `loadAdSchedule(url)`, avec `withTimeoutOrNull(10 s)` : le délai de la règle d'or ;
2. convertit le planning obtenu en `AdPlaybackState` ;
3. le publie avec `eventListener.onAdPlaybackState(state)`.

Un détail malin : si `start()` est rappelé pour le **même** contenu (après « Réessayer »), le
loader republie l'état qu'il avait. Les pubs déjà vues restent marquées « jouées » et ne
repassent pas.

**4.7.2 Les règles métier** : 📄 `core/domain/.../usecase/UseCases.kt`

`LoadAdScheduleUseCase` nettoie le planning reçu :
- il retire les breaks vides (*no fill*) ;
- il trie les pubs d'un break dans l'ordre imposé par le VAST (attribut `sequence`) ;
- il garde au plus un break par position.

`MediaFileSelector` choisit, parmi les fichiers vidéo proposés par le VAST, un format qu'ExoPlayer
sait lire, au débit le plus proche de 2 Mbit/s sans le dépasser. Une pub qui coupe est une pub
qu'on ne regarde pas jusqu'au bout.

**4.7.3 Les appels réseau** : 📄 `core/data/.../ads/AdRepositoryImpl.kt`

```
VMAP ──► pour chaque break, EN PARALLÈLE :
          ├─ VAST inclus dans le VMAP, ou téléchargé depuis son adresse
          └─ pour chaque pub du VAST :
               InLine  -> on construit la pub (LinearAd)
               Wrapper -> on suit l'adresse (5 niveaux maximum),
                          en CUMULANT les URLs de tracking de chaque niveau
```
Ce qu'il faut savoir expliquer :
- **`coroutineScope { async { … } }.awaitAll()`** : c'est la **concurrence structurée**. Les
  breaks sont résolus en même temps, et si l'utilisateur quitte l'écran, toutes les requêtes
  sont annulées ensemble.
- **Un délai par break et par Wrapper** : une régie lente ne retarde que sa propre pub.
- **`CancellationException` est toujours relancée** : l'attraper casserait le mécanisme
  d'annulation des coroutines.
- **En cas d'échec**, on envoie le bon **code d'erreur VAST** à toutes les URLs `<Error>` de la
  chaîne :

  | Code | Signification |
  |---|---|
  | 100 | XML illisible |
  | 301 | Wrapper trop lent |
  | 302 | Trop de Wrappers |
  | 303 | Aucune pub |
  | 403 | Aucun fichier lisible |
- **Un échec donne « pas de pub pour ce break »**, jamais un crash.

**4.7.4 La lecture des XML** : 📄 `core/data/.../ads/parser/`

- **`XmlSupport.kt`** : la lecture sécurisée du XML.
  - Le XML vient d'un tiers, donc on désactive les « entités externes ». Sans ça, un XML piégé
    pourrait faire lire des fichiers du téléphone : c'est l'attaque **XXE**.
  - On gère le préfixe `vmap:` devant les noms de balises.
  - On gère aussi les blocs `<![CDATA[…]]>`, une notation XML pour « texte brut » dans laquelle
    les URLs sont presque toujours rangées.
- **`VmapParser.kt`** et **`VastParser.kt`** : des fonctions **pures** qui transforment un texte
  XML en objets Kotlin. Le commentaire en tête de chaque classe contient un exemple de XML
  annoté : c'est le meilleur résumé des deux formats.
- **`TimeParser.kt`** : convertit `"00:00:15.500"`, `"start"`, `"end"` et `"25%"` en millisecondes.

**4.7.5 La conversion pour ExoPlayer** : 📄 `player/engine/.../ads/AdPlaybackStateMapper.kt`

Notre planning devient un `AdPlaybackState` :
- un « groupe » par break, positionné en **microsecondes** : 0 pour un pre-roll,
  `C.TIME_END_OF_SOURCE` (« à la fin ») pour un post-roll ;
- pour chaque pub : son fichier, sa durée et son identifiant.

À partir de là, ExoPlayer joue les pubs tout seul, et la `PlayerView` dessine des marqueurs
jaunes sur la barre de progression.

**4.7.6 Le tracking pendant la pub** (retour dans `VmapAdsLoader`)

- **Un sondage toutes les 200 ms** (`onProgressTick`) lit la position dans la pub. Le player
  n'émet pas d'événement « la position a avancé » : ce serait beaucoup trop fréquent.
- **L'impression** ne part que lorsque la pub **joue vraiment** (`isPlaying`), pas pendant son
  chargement. C'est ce que les régies facturent.
- 📄 **`AdProgressTracker.kt`** décide quand envoyer start et les quartiles, **une seule fois
  chacun**, même si un saut de position en franchit plusieurs d'un coup.
- **complete** est déclenché par `onPositionDiscontinuity(AUTO_TRANSITION)` : la position
  « saute » naturellement de la fin de la pub vers le film. Pour un post-roll, il n'y a pas de
  film après : c'est `STATE_ENDED` qui sert de signal.
- **skip** : `skipCurrentAd()` marque la pub « passée » dans l'`AdPlaybackState`, et ExoPlayer
  la quitte immédiatement.
- **Fichier de pub illisible** : `handlePrepareError` marque la pub en erreur, ExoPlayer la
  saute, et on envoie le code 405.

**4.7.7 L'envoi des pixels** : 📄 `core/data/.../ads/HttpAdTracker.kt` et le dossier `ads/macro/`

`HttpAdTracker.track(urls, contexte)` lance une coroutine dans le scope de l'application (le
pixel part même si l'écran se ferme). Pour chaque URL, **juste avant** l'appel,
`MacroExpander` remplit les macros.

Il utilise le **pattern Strategy** :
- **une classe par macro** (`TimestampMacro`, `CacheBustingMacro`, `ErrorCodeMacro`…). Chacune
  sait seulement calculer sa valeur ;
- **un moteur commun** (`MacroExpander`) qui :
  - trouve toutes les macros en une seule passe ;
  - demande sa valeur à chaque classe ;
  - encode les valeurs pour l'URL ;
  - met `-1` pour une macro officielle sans valeur ;
  - laisse intactes les macros inconnues.

Avantage : ajouter une macro, c'est ajouter une classe, sans toucher au moteur.

### 4.8 La mesure d'audience (Nielsen et outil maison)

Ce module est séparé du tracking publicitaire qu'on vient de voir : il n'utilise ni `AdTracker`
ni les pixels. Il suit quatre étages, du plus proche de Media3 au plus proche du SDK.

**4.8.1 Le traducteur** : 📄 `player/engine/.../tracking/PlayerEventTranslator.kt`

C'est le seul code de la mesure d'audience qui connaît Media3. Il fait quatre choses :
- **dans les callbacks individuels**, il note seulement les **raisons** : « c'était un seek »,
  « c'était une reprise en boucle », « la pause vient du casque débranché » ;
- **dans `onEvents`** (la méthode appelée une fois par cycle avec un état cohérent), il prend une
  **photographie** complète du player, le `PlayerSnapshot`. Elle contient l'état, l'intention,
  la réalité de lecture, la suspension éventuelle, la pub en cours et les positions. Il la
  transmet à l'étage suivant ;
- **un minuteur d'une seconde**, sur le thread du player, produit le playhead, uniquement quand
  une session est en cours ;
- **`release()`** est appelé **avant** `player.release()`, pour pouvoir encore lire la position
  finale.

**4.8.2 Le cerveau** : 📄 `core/domain/.../tracking/PlaybackSessionStateMachine.kt`

C'est du Kotlin pur. Elle reçoit les photographies et renvoie des **événements normalisés** :
`SessionStarted`, `Paused`, `Resumed`, `Seeked`, `ContentChanged`, `AdBreakStarted`,
`AdStarted`, `AdBreakEnded`, `Ended`, `Released` et `Tick` (le playhead).

Ses décisions, écrites en tête du fichier :
- la session démarre au **premier READY** avec l'intention de lire : le chargement initial
  n'est pas du visionnage ;
- le **buffering n'est pas une pause**, c'est aussi la règle Nielsen : on cesse seulement
  d'envoyer le playhead ;
- un **appel téléphonique** est une **interruption**, distincte d'une pause voulue ;
- un **seek** produit uniquement un événement `Seeked` ;
- une session se termine **au plus une fois**, quel que soit le chemin (fin, erreur, sortie).

**4.8.3 Le contrat et la diffusion** : 📄 `PlaybackTracker.kt`, `PlaybackEvent.kt` et `CompositeTracker.kt` (même dossier)

- **`PlaybackTracker`** est l'interface que tout outil de mesure implémente. C'est le
  **pattern Adapter** : chaque outil traduit les événements normalisés dans son propre langage.
- **`CompositeTracker`** diffuse chaque événement à tous les outils, en **isolant** les erreurs.
  Si un SDK tiers plante, ni la lecture ni les autres outils ne sont affectés.

**4.8.4 Les outils** : 📄 `core/data/.../tracking/`

**`nielsen/NielsenTracker.kt`** applique les règles du SDK Nielsen ; chaque règle cite sa page
de documentation Nielsen. Le **flush**, qui ferme proprement une session restée ouverte avant
d'en ouvrir une nouvelle, vit à **un seul endroit**. Le vrai SDK n'est pas inclus :
`LoggingNielsenSdkGateway` écrit chaque appel dans Logcat.

Les traductions principales :

| Ce qui se passe | Événement normalisé | Appels au SDK Nielsen |
|---|---|---|
| La lecture démarre | `SessionStarted` | `play()`, `loadMetadata(type=content)` |
| Chaque seconde de lecture | `Tick` | `setPlayheadPosition(secondes)` |
| Pause, appel téléphonique, arrière-plan | `Paused` | `stop()` |
| Reprise | `Resumed` | `play()`, `loadMetadata(...)` |
| Une pub commence | `AdStarted` | `stop()`, `loadMetadata(type=preroll / midroll / postroll)` |
| Retour au film | `AdBreakEnded` | `stop()`, `loadMetadata(type=content)` |
| Fin, erreur, sortie de l'écran | `Ended` | `end()`, une seule fois |

**`inhouse/InHouseTracker.kt`** est un outil maison générique. Il n'a pas les contraintes
Nielsen, garde la cause des pauses, et n'envoie un heartbeat que toutes les 10 secondes.

### 4.9 L'écran

📄 `feature/player/.../PlayerScreen.kt`

- La **`PlayerView`** est une vue Android classique, intégrée dans Compose grâce à
  `AndroidView`. Elle utilise une `SurfaceView` par défaut. C'est indispensable pour un contenu
  Widevine : avec une `TextureView`, on aurait un écran noir avec le son.
- **`onRelease`** détache le player de la vue quand celle-ci quitte l'écran, pour éviter une
  fuite mémoire.
- **`LifecycleEventEffect(ON_STOP)`** met la lecture en pause quand l'app passe en arrière-plan.
  On utilise `ON_STOP` et pas `ON_PAUSE`, parce qu'en multi-fenêtre une app peut être « en
  pause » tout en restant visible. `ON_START` signale le retour au premier plan.
- **`AdOverlay`** affiche « Publicité 1/2 · 12 s », « Passer dans 3 s » puis « Passer ▸ », et
  « En savoir plus ».
- **`ErrorOverlay`** affiche un message lisible et un bouton « Réessayer » quand l'erreur s'y prête.

### 4.10 Quand ça casse : le trajet d'une erreur

```
ExoPlayer : onPlayerError(PlaybackException, code 6004 = licence refusée)
   └─► PlayerViewModel : error.toPlayerError()              (PlaybackErrorMapper.kt)
         └─► PlayerError.Drm("…")  rangé dans PlayerUiState
               └─► ErrorOverlay : « Ce contenu protégé ne peut pas être lu »
```
📄 `player/engine/.../error/PlaybackErrorMapper.kt` classe le code d'erreur selon sa famille
(2xxx réseau, 3xxx manifest, 4xxx décodage, 6xxx DRM). Il le traduit en `PlayerError`, un type
métier défini dans 📄 `core/domain/.../model/PlayerError.kt`. Chaque type indique si on peut
réessayer : oui pour le réseau, non pour la DRM ou le décodage.

- **« Réessayer »** appelle simplement `prepare()` : après une erreur, le player a gardé son
  média et sa position.
- **Cas particulier du direct.** L'erreur 1002 `BEHIND_LIVE_WINDOW` arrive quand on est resté
  trop longtemps en pause sur un direct : la position est sortie de la fenêtre de replay. On
  n'affiche pas d'erreur. On se recale sur le direct (`seekToDefaultPosition()` puis
  `prepare()`).
- **Ce qu'on observe vraiment sur l'émulateur** : le contenu « licence invalide » **joue environ
  10 s** avant l'erreur. Tears of Steel commence par une partie non chiffrée, et grâce au réglage
  `setPlayClearContentWithoutKey(true)` vu en 4.5, ExoPlayer la joue pendant que la licence
  échoue. L'erreur fatale n'arrive qu'au premier segment chiffré.

### 4.11 Observer la qualité

📄 `player/engine/.../analytics/PlaybackAnalyticsLogger.kt`

Un `AnalyticsListener` reçoit **tous** les événements internes du player : chargements,
changements de qualité, DRM, images perdues. On y calcule les indicateurs de qualité
d'expérience :
- le TTFF (temps avant la première image) ;
- le temps de buffering cumulé ;
- les changements de qualité ;
- la durée de la requête de licence.

En production, ces mesures partiraient vers un outil comme Datadog, Firebase, Conviva ou Mux.

📄 `player/engine/.../drm/WidevineCapabilities.kt` sait lire le niveau Widevine (L1 ou L3) de
l'appareil. C'est un utilitaire de diagnostic **qui n'est appelé nulle part** dans l'app : à
présenter comme piste d'amélioration (envoyer l'info au backend, ou plafonner la qualité en L3).

### 4.12 La sortie de l'écran

```
Retour arrière ─► ScreenStores.clear() ─► PlayerViewModel.onCleared()
   └─► PlayerSession.release()
         1. tracking.release()      fin de la mesure d'audience (Nielsen end()), une seule fois
         2. adsLoader.setPlayer(null)
         3. player.release()        libère les décodeurs et les sessions DRM
                                    (avec le Cast, c'est le CastPlayer : il libère aussi l'ExoPlayer)
         4. adsLoader.release()     annule les coroutines publicitaires
```

### 4.13 Google Cast : envoyer la vidéo sur la TV

📄 `player/engine/.../cast/CastSupport.kt` · 📄 `app/.../MediaSampleApplication.kt` ·
📄 `feature/player/.../PlayerScreen.kt`

**L'idée.** Avec le Cast, le téléphone ne lit plus la vidéo. Il envoie des ordres (« lis ce
lien à 12 min », « pause ») à une application web qui tourne sur la TV, le *receiver*. C'est la
TV qui télécharge et décode. Le téléphone devient une télécommande.

**Le montage.** Media3 fournit un `CastPlayer` : un `Player` qui contient deux players et
délègue au bon :
```
PlayerView ─► CastPlayer ─┬─ ExoPlayer local      (pas de session Cast)
                          └─ RemoteCastPlayer     (session Cast ouverte) ─► TV
```
On donne le `CastPlayer` à l'écran. Les pubs, les analytics et la mesure d'audience restent
branchés sur l'ExoPlayer local.

**Dans l'ordre d'exécution :**
1. `MediaSampleApplication.onCreate()` appelle `CastSupport.initialize()`. Le Cast SDK se
   prépare en arrière-plan. Sans Google Play Services, il échoue en silence : le bouton ne
   trouve aucun appareil.
2. `PlayerFactory` enveloppe l'ExoPlayer : `CastSupport.wrap(context, exoPlayer)`.
3. `PlayerScreen` affiche le bouton Cast (`MediaRouteButton`). Il n'apparaît que si un appareil
   Cast est sur le même Wi-Fi.
4. L'utilisateur choisit sa TV. Le `CastPlayer` **bascule** : il copie playlist et position
   vers la TV, puis appelle `stop()` sur l'ExoPlayer local.
5. Le `PlayerViewModel` reçoit `onDeviceInfoChanged` et affiche « Lecture sur Salon TV ».

**Deux pièges, corrigés par `AdAwareTransferCallback` :**
- *Pendant une pub*, la position du player est celle **dans la pub** (par exemple 5 s). Envoyée
  telle quelle, la TV démarrerait le film à 5 s. On envoie la position du **contenu**.
- *Au retour sur le téléphone*, le lien revient de la TV **sans la configuration des pubs**.
  Comme l'ExoPlayer local a gardé sa playlist après `stop()`, on y reprend l'élément d'origine,
  et les pubs reviennent.

**Et la mesure d'audience ?** Quand l'ExoPlayer local reçoit `stop()`, il passe en `IDLE` : le
traducteur ferme la session (`end()` pour Nielsen). Le téléphone ne mesure pas ce que joue la TV.
Au retour, une nouvelle session démarre.

**En arrière-plan**, on ne met pas en pause si on caste : quitter l'app ne doit pas couper la TV.

> **Limite à connaître.** Le receiver par défaut de Google ne lit que des contenus en clair :
> pour Widevine et les pubs sur la TV, il faut un receiver personnalisé. Et comme le player vit
> dans le ViewModel, quitter l'écran libère le `CastPlayer` alors que la TV continue : la doc
> recommande de le placer dans un `MediaSessionService`. Détails : `GUIDE_ENTRETIEN.md` §8.

> **À retenir (partie 4)**
> - Démarrage : `AppContainer` crée tout ; `PlayerViewModel` possède le player ; `PlayerFactory` le monte pièce par pièce.
> - Lancement : `VideoContent` → `MediaItem` (DASH + DRM + URL du VMAP) → `prepare()`.
> - Pubs : `VmapAdsLoader` → règles métier → réseau en parallèle → `AdPlaybackState` → ExoPlayer joue les pubs → pixels avec macros.
> - Audience : traducteur (`onEvents`) → state machine → `CompositeTracker` → Nielsen / outil maison.
> - Erreurs : `PlaybackException` → `PlayerError` → overlay ; `prepare()` pour réessayer.
> - Sortie : `release()` dans le bon ordre, une seule fin de session.
> - Cast : `CastPlayer` enveloppe l'ExoPlayer ; au basculement, l'état est copié vers la TV et le player local est arrêté.

---

## Partie 5 : comment on prouve que ça marche

**Ce que tu vas apprendre** : ce que couvrent les tests et les quatre techniques qu'ils utilisent.

Lancer tous les tests, sans téléphone :
```bash
./gradlew test
```
Une CI GitHub Actions lance la même commande à chaque pull request.

| Où | Ce qui est testé | Technique |
|---|---|---|
| `core/domain/src/test` | Choix du fichier de pub, state machine d'audience (22 scénarios), diffusion multi-outils | JVM pure, données écrites à la main |
| `core/data/src/test/.../ads` | Lecture VMAP/VAST, Wrappers, codes d'erreur, envoi des pixels | Faux client HTTP qui renvoie des XML d'exemple |
| `core/data/src/test/.../macro` | Chaque macro, l'encodage, le `-1`, une heure par URL | Horloge et hasard injectés |
| `core/data/src/test/.../tracking` | Une **suite de contrat** commune aux outils d'audience + les règles Nielsen | Classe de test abstraite, héritée par chaque outil |
| `player/engine/src/test` | Quartiles ; séquence d'événements avec un **vrai ExoPlayer**, y compris pre + mid + post-roll | Robolectric + `FakeClock` |

Les quatre techniques à savoir expliquer :
1. **Des « fakes » plutôt que des mocks.** `FakeHttpClient` et `RecordingNielsenSdk` sont de
   vraies petites implémentations qui enregistrent ce qu'on leur demande. Les tests restent
   lisibles et ne cassent pas au moindre refactoring.
2. **Injecter le temps.** `MacroExpander(clock = { now })` ou `FakeClock` : un test qui dépend
   de l'heure réelle n'est pas reproductible.
3. **Les tests de contrat.** `PlaybackTrackerContractTest` décrit ce que **tout** outil
   d'audience doit garantir : rien en dehors d'une session, jamais deux fins de session.
   Nielsen et l'outil maison en héritent. Un nouvel outil héritera de la même classe et sera
   testé gratuitement.
4. **Un vrai player sans téléphone.** **Robolectric** simule Android sur la JVM.
   `TestExoPlayerBuilder` crée un vrai ExoPlayer avec des afficheurs factices. `FakeClock` fait
   avancer le temps tout seul : 10 s de vidéo se « lisent » en quelques millisecondes, toujours
   de la même façon.

Deux pièges rencontrés en écrivant ces tests, et qui font de bons sujets de discussion :
- `onEvents` arrive au cycle **suivant** du thread. Un test qui attend la fin de la lecture
  doit laisser passer un cycle de plus avant de vérifier, sinon le dernier événement n'est pas
  encore émis.
- Robolectric 4.16 ne simule qu'Android 36, alors que le projet cible Android 37. Le fichier
  `robolectric.properties` force donc `sdk=36` pour les tests.

> **À retenir (partie 5)**
> - Toute la logique se teste sans téléphone, en quelques secondes.
> - Fakes, temps injecté, tests de contrat, Robolectric : quatre techniques, quatre bons sujets d'entretien.

---

## Partie 6 : exercices sur l'émulateur

**Ce que tu vas apprendre** : à observer le player en vrai, grâce aux journaux.

Lance l'app depuis Android Studio, ouvre **Logcat** et utilise ces filtres (des « tags ») :

| Tag | Ce que tu y vois |
|---|---|
| `PlaybackTracking` | La chronologie « callback Media3 → événement normalisé → appel Nielsen » |
| `Nielsen` | Uniquement les appels au SDK Nielsen simulé |
| `InHouseTracker` | Les messages de l'outil maison |
| `AdTracker` | Chaque pixel publicitaire envoyé, macros remplies |
| `AdRepository` | Les problèmes de la chaîne publicitaire (délais dépassés, breaks ignorés) |
| `PlaybackQoE` | TTFF, buffering, qualité, licence DRM |
| `EventLogger` | Le journal brut, très bavard, de Media3 |

Exercices, du plus simple au plus riche :
1. **Contenu « Widevine + VMAP »**, tag `PlaybackQoE` : trouve `DRM keys loaded`, la durée de
   la licence, et le TTFF.
2. **Même contenu**, tag `PlaybackTracking` : suis le pre-roll (`AdStarted` → `Nielsen.stop()` →
   `loadMetadata({type=preroll…})`) puis le retour au film. Attends le mid-roll vers 15 s.
3. **Tag `AdTracker`** : retrouve l'impression puis les quartiles. Vérifie que `[TIMESTAMP]`
   est bien remplacé par une date.
4. **Bouton Home pendant la lecture** : observe `appInBackground()`, puis `Paused` → `stop()`.
   Reviens dans l'app : `appInForeground()`.
5. **Contenu « skippable »** : attends « Passer ▸ », clique, et retrouve le pixel `skip`.
6. **Contenu « Démo erreur »** : observe environ 10 s de lecture, puis le 401, l'erreur 6004,
   `Ended(ERROR)` et l'écran d'erreur.
7. **Retour arrière** : observe `release() → Ended(RELEASED)` puis un seul `Nielsen.end()`.
8. **Mode avion pendant la lecture** (exercice non vérifié par l'auteur, à toi d'observer) : en
   théorie, la vidéo continue tant que la réserve dure (jusqu'à 50 s). ExoPlayer réessaie
   ensuite 5 fois par segment avant une erreur réseau (2xxx). Coupe le mode avion et clique
   « Réessayer ».

---

## Partie 7 : préparer l'entretien

**Ce que tu vas apprendre** : présenter le projet, répondre aux questions probables, et parler
de ses limites.

### 7.1 Le pitch du projet en une minute

> « Pour préparer ce poste, j'ai construit un player Media3 mobile qui couvre la fiche de poste.
> Il lit du DASH protégé par Widevine. Il insère des pubs côté client avec mon propre
> `AdsLoader`, piloté par un VMAP : pre-roll, mid-roll, post-roll, skip, et tracking VAST avec
> des macros conformes à la spec 4.1. Il mesure l'audience avec plusieurs outils, dont un
> adapter Nielsen. L'architecture est en modules : Media3 ne vit que dans un module, les règles
> métier sont en Kotlin pur et testées sans téléphone, et le traducteur Media3 est testé avec
> un vrai ExoPlayer sous Robolectric. J'ai utilisé l'IA, et j'ai appris à vérifier ses
> propositions contre la spec : une de ses suggestions sur `[TIMESTAMP]` était fausse. »

### 7.2 Les questions probables, avec une réponse courte

**Fondamentaux ExoPlayer**
- *Décris l'architecture d'ExoPlayer.* `MediaItem` → `MediaSource` → `Timeline`. Le
  `LoadControl` décide quand charger, le `TrackSelector` quoi charger, les `Renderers`
  décodent et affichent.
- *`playWhenReady` ou `isPlaying` ?* L'intention ou la réalité. Pendant un appel téléphonique,
  le premier reste vrai et le second devient faux.
- *Pourquoi `release()` ?* Les décodeurs matériels et les sessions DRM sont rares : un player
  non libéré empêche le suivant de démarrer.
- *Et les threads ?* Un seul thread pour toutes les interactions avec le player. Le reste est
  interne.
- *Pourquoi `onEvents` ?* Pour lire un état cohérent une fois par cycle, sans dépendre de
  l'ordre des callbacks.

**Streaming**
- *DASH ou HLS ?* Une norme ISO avec un manifest XML, contre le format d'Apple avec des
  playlists texte. ExoPlayer lit les deux.
- *Comment démarrer plus vite ?*
  - exiger moins de réserve avant de démarrer ;
  - jouer le début non chiffré pendant que la licence arrive ;
  - précharger (`PreloadManager`) ;
  - démarrer en basse qualité.
- *Les coupures s'enchaînent ?* Regarder les erreurs de chargement, l'estimation de débit et la
  réserve exigée pour repartir. Plafonner la qualité si besoin.

**DRM**
- *L1 ou L3 ?* Matériel sécurisé, autorisé pour la HD/4K, contre logiciel, souvent limité à la SD.
- *Écran noir avec le son ?* Une `TextureView` avec un contenu protégé, ou une protection de
  sortie HDMI (HDCP) insuffisante.
- *La lecture hors ligne ?* Une licence persistante (`OfflineLicenseHelper`), et l'identifiant
  de cette licence stocké avec le contenu téléchargé.

**Publicité**
- *VMAP ou VAST ?* Quand passer les pubs, contre quoi jouer et quoi signaler.
- *C'est quoi un Wrapper ?* Une redirection entre régies. On signale à chaque niveau et on
  limite la profondeur.
- *L'Ad Proxy ne répond pas ?* Délai de 10 s, puis le film démarre sans pub.
- *Une pub est illisible ?* Elle est marquée en erreur, ExoPlayer la saute, et on envoie le code 405.
- *Pourquoi ne pas utiliser l'IMA SDK ?* Il fait tout, y compris OMID. Une version maison se
  justifie avec un Ad Proxy propriétaire ou un besoin de contrôle total.
- *Comment ferais-tu les pause ads ?* Hors pub, quand `isPlaying` passe à faux à cause d'une
  pause utilisateur, afficher une image en overlay et envoyer l'impression à l'affichage.
- *OMID ? SIMID ?* La mesure de visibilité des pubs, et les pubs interactives affichées dans
  une WebView.

**Mesure d'audience**
- *Quelle différence avec le tracking pub ?* Le tracking pub signale des événements aux régies.
  La mesure d'audience mesure la durée regardée, avec un playhead chaque seconde.
- *Le buffering, c'est une pause pour Nielsen ?* Non : on cesse d'envoyer le playhead, sans
  appeler `stop()`. C'est écrit dans la FAQ Nielsen.
- *Comment éviter deux fins de session ?* Un seul endroit émet la fin, chaque outil ignore une
  fin en double, et des tests de contrat le vérifient.

**Architecture et Kotlin**
- *Pourquoi le player dans le ViewModel ?* Il survit à la rotation. Pour jouer en arrière-plan,
  on le déplacerait dans un `MediaSessionService`.

**Google Cast**
- *Comment l'écran sait-il qu'on caste ?* `onDeviceInfoChanged` : `playbackType` vaut
  `PLAYBACK_TYPE_REMOTE`.
- *Que deviennent les pubs pendant le Cast ?* Notre `AdsLoader` ne tourne que sur le téléphone.
  Sur la TV, il faut des pubs gérées par le receiver ou insérées dans le flux (SSAI).
- *Quel piège au basculement ?* Pendant une pub, la position est celle de la pub : on envoie
  celle du contenu.
- *Concurrence structurée ?* `coroutineScope` + `async` : tout est annulé ensemble. On relance
  toujours `CancellationException`.
- *Pourquoi du Kotlin pur dans le domaine ?* Des tests en millisecondes, et un métier qui ne
  dépend pas du lecteur vidéo.

### 7.3 Les limites du projet, à annoncer toi-même

- Pas de lecture en arrière-plan ni de notification (`MediaSessionService`), pas de
  Picture-in-Picture.
- Pas de pubs non linéaires, de pubs compagnons, de pause ads, ni d'OM SDK.
- Les pixels ne sont pas mis en attente quand le réseau est coupé : hors ligne, ils sont perdus.
  La solution serait une file persistante avec WorkManager.
- Le SDK Nielsen est simulé, et la lecture du niveau Widevine n'est pas branchée.
- Pas de contenu en direct dans le catalogue : le cas `BEHIND_LIVE_WINDOW` n'est testé qu'en
  JVM.
- Mobile uniquement. La TV demanderait un module dédié (navigation à la télécommande, focus).
- Cast : receiver par défaut (contenus en clair seulement), player dans le ViewModel et non dans
  un `MediaSessionService`, et pas encore essayé sur un vrai Chromecast (tests JVM seulement).

Annoncer soi-même ses limites, avec la solution qu'on y apporterait, montre la maturité qu'on
attend d'un profil senior.

### 7.4 Ton plan de révision

1. Redessine de mémoire le schéma de la chaîne de lecture (`MediaItem` → … → écran).
2. Refais le parcours de la partie 4 en ouvrant chaque fichier, sans le guide.
3. Fais les exercices 1, 2, 4 et 6 sur l'émulateur.
4. Lis les questions 7.2 à voix haute, et réponds avant de lire la réponse.
5. La veille, relis les fiches et le récit sur les macros (voir « Les autres documents » ci-dessous).

---

## Glossaire

| Terme | Définition |
|---|---|
| **ABR** | *Adaptive BitRate* : changer de qualité segment par segment selon le débit |
| **Ad pod / break** | Plusieurs pubs jouées d'affilée |
| **Ad Proxy / ad server** | Serveur de la plateforme qui renvoie le VMAP |
| **`AdPlaybackState`** | Structure Media3 qui décrit où sont les pubs et dans quel état |
| **`AdsLoader`** | Interface Media3 : « va chercher les pubs et dis-moi où elles sont » |
| **Cast (sender / receiver)** | Le téléphone envoie les ordres ; l'app web sur la TV lit le flux |
| **`CastPlayer`** | `Player` Media3 qui bascule entre l'ExoPlayer local et la TV |
| **CDM** | *Content Decryption Module* : la partie Widevine de l'appareil |
| **CSAI / SSAI** | Insertion de pub par le player, ou par le serveur dans le flux |
| **DASH** | Format de streaming normalisé ISO, avec un manifest `.mpd` |
| **DRM** | Protection du contenu par chiffrement et licence |
| **Heartbeat / playhead** | Position de lecture envoyée régulièrement à un outil de mesure |
| **HLS** | Format de streaming d'Apple, avec des playlists `.m3u8` |
| **IAB** | Organisme qui normalise la publicité en ligne (VAST, VMAP, OMID, SIMID) |
| **Impression** | Signal « la pub a commencé à s'afficher », ce qui est facturé |
| **InLine / Wrapper** | VAST qui contient la pub, ou VAST qui redirige vers un autre |
| **L1 / L3** | Niveaux de sécurité Widevine : matériel ou logiciel |
| **Licence** | Réponse du serveur DRM, qui contient les clés de déchiffrement |
| **Looper** | File de messages d'un thread Android, traités un par un |
| **Macro** | Trou `[NOM]` dans une URL de tracking, rempli par le player |
| **Manifest** | Index d'un flux : qualités, langues, adresses des segments |
| **`MediaItem`** | Description Media3 d'un média à lire |
| **`MediaSource`** | Objet Media3 qui sait lire un format donné |
| **No fill** | La régie n'avait aucune pub à proposer (VAST vide) |
| **OMID** | Standard IAB de mesure de la visibilité des pubs |
| **Period / Window** | Morceaux de la `Timeline` Media3 (interne / perçu par l'utilisateur) |
| **Output Switcher** | Panneau système Android pour choisir où joue le son et la vidéo |
| **Pixel** | Requête HTTP GET de tracking dont on ignore la réponse |
| **QoE** | *Quality of Experience* : TTFF, rebuffering, qualité, erreurs |
| **Quartiles** | Signaux à 25 %, 50 % et 75 % d'une pub |
| **Rebuffering** | Arrêt de la lecture parce que la réserve est vide |
| **Robolectric** | Bibliothèque qui simule Android sur la JVM pour les tests |
| **Segment** | Morceau de quelques secondes d'une vidéo en streaming |
| **SIMID** | Standard IAB des pubs interactives (WebView par-dessus le player) |
| **Skipoffset** | Moment à partir duquel une pub peut être passée |
| **TEE** | Zone matérielle sécurisée du processeur (Widevine L1) |
| **TTFF** | *Time To First Frame* : délai avant la première image |
| **UDF** | *Unidirectional Data Flow* : l'état descend vers l'écran, les actions remontent |
| **VAST** | Format IAB qui décrit une pub et ses URLs de tracking |
| **VMAP** | Format IAB qui décrit quand passer les breaks |
| **Widevine** | La DRM de Google, intégrée à Android |
| **XXE** | Attaque par XML piégé, bloquée dans `XmlSupport` |

---

## Les autres documents du dépôt

Tu as fini le parcours. Ces documents servent à **réviser** ou à **approfondir** :
- `README.md` : le point d'entrée du dépôt (lancer, ordre de lecture, historique des PR, limites).
- `GUIDE_ENTRETIEN.md` : les fiches de révision. Tableaux, questions/réponses, et en section 7 le
  tableau complet « callback Media3 → événement → appel Nielsen ». Section 8 : Google Cast.
- `GUIDE_MACROS_VAST.md` : les macros VAST confrontées à la spec officielle, et le récit de
  l'erreur d'IA corrigée.
- `PRESENTATION_ORALE.md` : le pitch de ton parcours professionnel.
- `CONTEXTE_LLM.md` : le contexte du projet, à coller dans un assistant IA.

## Pour aller plus loin (documentation officielle)
- Media3 / ExoPlayer : https://developer.android.com/media/media3/exoplayer
- Personnaliser ExoPlayer : https://developer.android.com/media/media3/exoplayer/customization
- DASH : https://developer.android.com/media/media3/exoplayer/dash
- DRM : https://developer.android.com/media/media3/exoplayer/drm
- Insertion de pubs : https://developer.android.com/media/media3/exoplayer/ad-insertion
- Événements du player : https://developer.android.com/media/media3/exoplayer/listening-to-player-events
- Threads : https://developer.android.com/media/media3/exoplayer/hello-world#a-note-on-threading
- Analytics : https://developer.android.com/media/media3/exoplayer/analytics
- Lecture en arrière-plan : https://developer.android.com/media/media3/session/background-playback
- Google Cast avec Media3 : https://developer.android.com/media/media3/cast
- Sources de Media3 : https://github.com/androidx/media
- Standards IAB (VAST, VMAP, OMID, SIMID) : https://iabtechlab.com/standards/
- Nielsen DCR Android : https://engineeringportal.nielsen.com/wiki/DCR_Video_Android_SDK
- Architecture Android : https://developer.android.com/topic/architecture
- Injection de dépendances manuelle : https://developer.android.com/training/dependency-injection/manual
