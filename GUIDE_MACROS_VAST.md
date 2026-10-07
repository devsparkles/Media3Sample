# Macros VAST et pattern Strategy : verdict face à la spec

Ce guide compare trois implémentations du remplacement de macros VAST à la spécification officielle.

1. **Bedrock** : votre implémentation chez Bedrock Streaming, d'après le prompt Cursor v1 et la description de la PR #8991. Ce n'est pas le code final mergé, que nous n'avons pas.
2. **v1** : ma première version dans ce repo (commit `af684cd`).
3. **Finale** : la version actuelle (commit `4c7b3cd`), alignée sur la spec.

L'arbitre est le texte de **VAST 4.1** (`VAST4.1-final-Nov-8-2018.pdf`), section 6 « Macros », p. 91-108. Toutes les citations ci-dessous viennent de ce PDF. Les numéros de page sont ceux imprimés en bas des pages.

---

## 1. Verdict en une phrase

**Aucune des deux premières versions n'était conforme.**
- Bedrock avait raison sur la **sémantique** des deux macros implémentées : `[TIMESTAMP]` est calculé à chaque URL, `[CACHEBUSTING]` à chaque occurrence.
- La v1 avait raison sur l'**encodage**, sur les **valeurs inconnues** et sur les **macros hors spec**.
- La version finale garde le meilleur des deux.

Mon erreur dans la v1 est l'« instantané figé par événement » pour `[TIMESTAMP]`. Le texte de la spec le contredit (voir 2.1). C'est un bon exemple pour la partie IA de la fiche de poste : une suggestion d'IA convaincante, bien argumentée en commentaire, mais fausse face à la spec. On ne l'a détectée qu'en relisant la source primaire.

---

## 2. Ce que dit la spec (citations)

### 2.1 `[TIMESTAMP]` (p. 92, Required)
> « The date and time at which **the URI using this macro is accessed**. […] formatting conventions of ISO 8601. To add milliseconds, use the convention .mmm at the end of the time provided and before any time zone indicator. »
> Exemple : `2016-01-17T8:15:07.127-05` → encodé `2016-01-17T8%3A15%3A07.127-05`

Conséquence : **une heure par URI, prise au moment de la requête**. Ce n'est pas une heure par événement.

### 2.2 `[CACHEBUSTING]` (p. 92-93, Required)
> « To be replaced with a random 8-digit number. » Exemple : `12345678`

La spec ne dit pas explicitement « une valeur par occurrence ». En revanche, le but d'un cache-buster est que deux requêtes identiques restent distinctes pour les caches et les proxies. Tirer une valeur à chaque occurrence est donc l'interprétation la plus sûre. C'était aussi un critère de votre PR Bedrock.

### 2.3 Encodage (p. 92)
> « When replacing macros, make sure to apply **encodeURIComponent to any value**, to avoid creating invalid URLs. […] the **encoded version must always be used** as a macro substitution. »
> Pour les listes (`Array<T>`) : chaque valeur est encodée et les valeurs sont séparées par des **virgules non encodées** (ex. `abc%2Fdef,y%3Dz`).

Voir aussi p. 27 : « Macro responses must be correctly percent-encoded per RFC 3986. »

### 2.4 Valeurs inconnues (p. 91)
> « For any macros that are marked as optional or deprecated and where the actual macro is not provided […] : **-1** = value is unknown, but would be shared if it was known ; **-2** = value is known, but information can't be shared because of policy. »
> *Implementation Note* : « do **not** replace all unknown macros with -1, only do this for macros **specifically mentioned in this section** that you decide not to implement. »

### 2.5 Qui remplace (p. 91)
> « The responsibility to properly replace macros […] lies with **the party that will perform the HTTP request**. […] both for **VAST URLs** and tracking pixel URLs. »

Conséquence : il faut aussi remplacer les macros dans les URLs de **requête** (tag VMAP, `AdTagURI`, `VASTAdTagURI` des Wrappers), pas seulement dans les pixels de tracking.

### 2.6 Wrappers et erreurs (p. 26-28)
> « VAST Wrappers should be limited to five […] replace the [ERRORCODE] macro […] **Error codes should be sent for all wrappers in the chain** where provided. »

Codes (tableau §2.3.6.3) :
- 100 : XML parsing ;
- 300 : General Wrapper error ;
- 301 : timeout de l'URI d'un Wrapper ;
- 302 : limite de Wrappers atteinte ;
- 303 : pas de réponse VAST après des Wrappers ;
- 400 à 405 : erreurs Linear (403 : aucun MediaFile supporté, 405 : problème d'affichage) ;
- 900 : erreur indéfinie.

---

## 3. Tableau comparatif

| Règle de la spec | Bedrock | v1 (`af684cd`) | Finale (`4c7b3cd`) |
|---|---|---|---|
| `[TIMESTAMP]` = heure d'accès, par URI (p. 92) | ✅ `now()` par URL | ❌ instantané par événement | ✅ `clock()` lu dans `expand()` |
| ISO 8601 + `.mmm` avant le fuseau (p. 92) | ✅ | ✅ | ✅ |
| `[CACHEBUSTING]` à 8 chiffres, valeur distincte (p. 92) | ✅ par occurrence | ⚠️ partagé : même tracker sur 2 Wrappers = 2 requêtes identiques | ✅ par occurrence |
| encodeURIComponent sur **toute** valeur (p. 92) | ❌ `[TIMESTAMP]` brut (`:` et `+` non encodés) | ⚠️ `URLEncoder` : écarts sur `! ' ( ) ~` | ✅ équivalent exact |
| -1 / -2 (p. 91) | ❌ non traité | ⚠️ -1 seulement pour les macros implémentées | ✅ `MacroValue` à 3 états |
| Macro de la spec non fournie (ex. `[APIFRAMEWORKS]` sans OM) → -1 (p. 91) | ❌ laissée intacte | ❌ laissée intacte | ✅ -1 |
| Macro hors spec (ex. `[AD_MT]` de Google) laissée intacte (p. 91) | n/a | ✅ | ✅ |
| Remplacement aussi dans les requêtes VAST (p. 91) | ? (inconnu) | ❌ | ✅ |
| Erreurs Wrapper 300/301/100 (p. 26-28) | ? | ⚠️ 301 pour toute erreur | ✅ |
| Testabilité (hors spec) | ⚠️ horloge et aléa internes | ✅ injectés | ✅ injectés |

**Sur les deux macros de la PR Bedrock (TIMESTAMP, CACHEBUSTING), Bedrock était plus proche de la spec que la v1, sauf pour l'encodage.**

---

## 4. Architecture finale

```
  Player (VmapAdsLoader)                          :core:data
  ──────────────────────                          ──────────
  événement (quartile, erreur…)
      │  TrackingContext(errorCode, adPlayhead,
      │    assetUri, breakPosition, contentPlayhead)
      ▼
  AdTracker.track(urls, ctx) ──► HttpAdTracker ──► coroutine (scope applicatif)
                                                       │ pour chaque URL, JUSTE avant la requête :
                                                       ▼
                                   MacroExpander.expand(url, ctx)
                                     1. MacroContext(ctx, accessTimeMs = clock(), random)  ← une heure par URI
                                     2. regex unique : [NOM] ou %5BNOM%5D
                                     3. stratégie trouvée → resolve() → MacroValue
                                        sinon : macro de la spec → -1, macro inconnue → intacte
                                     4. Known → encodeURIComponent | Unknown → -1 | Restricted → -2
                                                       ▼
                                               http.fire(url)
```

| Classe | Rôle dans le pattern | Fichier |
|---|---|---|
| `MacroStrategy` | **Stratégie** (interface) : `name` + `resolve(context): MacroValue` | `macro/MacroStrategy.kt` |
| `MacroValue` | Résultat : `Known(raw)`, `Unknown` (-1), `Restricted` (-2) | idem |
| `MacroContext` | Données d'**une** requête : événement + heure d'accès + générateur aléatoire | idem |
| `TimestampMacro`, `CacheBustingMacro`, `ErrorCodeMacro`, `AdPlayheadMacro`, `MediaPlayheadMacro` (+ alias `CONTENTPLAYHEAD`), `BreakPositionMacro`, `AssetUriMacro` | **Stratégies concrètes** | `macro/MacroStrategies.kt` |
| `MacroExpander` | **Contexte** du pattern : trouve, délègue, encode, applique -1/-2 | `macro/MacroExpander.kt` |
| `HttpAdTracker` | Client : appelle `expand()` juste avant chaque pixel | `HttpAdTracker.kt` |
| `AdRepositoryImpl` | Client : appelle `expand()` avant chaque requête VMAP/VAST/Wrapper | `AdRepositoryImpl.kt` |
| `TrackingContext` | Données métier fournies par le player | `:core:domain` |

**Pourquoi la stratégie ne voit pas l'URL** (différence avec le `apply(url)` de Bedrock) :
- le parsing, y compris la forme `%5B…%5D`, existe en un seul endroit : il n'est pas dupliqué dans chaque stratégie ;
- le remplacement se fait en **un seul passage** : l'ordre des stratégies n'a plus d'importance, et une valeur insérée n'est jamais re-scannée. Le test `a substituted value is never re-scanned` le vérifie ;
- les règles transverses de la spec (encodage, -1/-2, macros inconnues) sont codées une seule fois.

**Ce que coûte ce choix (lisibilité)**, dit honnêtement : chez Bedrock, une seule classe suffisait pour comprendre `[TIMESTAMP]`. Ici il faut en lire deux : `TimestampMacro` pour la valeur, `MacroExpander` pour les règles. La v1 en demandait quatre, ce qui était trop. La version finale a simplifié le contexte (plus d'instantané) pour revenir à deux.

---

## 5. Les tests et ce qu'ils prouvent

Lancer : `./gradlew :core:data:test`. Il y a 49 tests au total dans le projet.

| Test | Règle prouvée |
|---|---|
| `MacroStrategiesTest` (11) | Chaque stratégie isolée : format ISO 8601 et fuseau, 8 chiffres, timecode `HH:MM:SS.mmm`, codes BREAKPOSITION 1/2/3, `Unknown` si aucune valeur |
| `MacroExpanderTest.values are always percent-encoded…` | §6.1 : encodage de toute valeur (exemple de la spec avec `%3A`) |
| `…encoding matches javascript encodeURIComponent` | Équivalence exacte avec `encodeURIComponent` |
| `…each url gets its own access time` | §6.2 : `[TIMESTAMP]` = heure d'accès par URI |
| `…each cachebusting occurrence gets a different value` | Une valeur par occurrence |
| `…two identical tracker urls … produce two distinct requests` | Le cas des Wrappers qui posait problème dans la v1 |
| `…implemented macro without value is replaced by -1`, `…restricted value is replaced by -2`, `…spec macro not implemented here is replaced by -1` | §6.1 : -1 / -2 |
| `…non-spec macros are left untouched` | Implementation Note de la spec |
| `HttpAdTrackerTest.timestamp is the time the pixel is actually requested` | Envoi retardé d'une minute → c'est l'heure de l'envoi qui part |
| `HttpAdTrackerTest.cancellation is not logged as a tracking failure` | Correction du `runCatching` qui avalait `CancellationException` |
| `AdRepositoryWrapperErrorsTest` (4) | §6.1 : macros remplacées dans `VASTAdTagURI` ; §2.3.6.3 : 301 (timeout, en temps virtuel), 300 (HTTP), 100 (XML) |

Technique de test à retenir :
- `SequenceRandom` (11111111, 22222222…) : un faux générateur aléatoire lisible ;
- `clock = { now }` : une horloge contrôlée par le test ;
- `TimeZone` injecté ;
- `runTest` + `advanceUntilIdle()` pour simuler un envoi retardé, `delay(10_000)` pour simuler un timeout sans attendre.

---

## 6. Retour sur la revue de l'autre conversation Claude

| Remarque | Décision | Pourquoi |
|---|---|---|
| Cache-buster partagé = problème avec les Wrappers | ✅ **Corrigé** | Bien vu, et cohérent avec votre PR |
| Macros conditionnelles : « laisser intact » plutôt que -1 | ❌ **Rejeté** | La spec (p. 91) demande **-1** pour une macro de la spec non fournie. Laisser `[APIFRAMEWORKS]` intact chez Bedrock n'était pas conforme |
| Ajouter `[APIFRAMEWORKS]` pour le lien avec OMID/SIMID | ⚠️ **Nuancé** | Son contexte dans la spec (p. 102) est « **VAST request URIs** » uniquement : la macro sert à *demander* des pubs compatibles, pas à tracker. Sans support d'OMID, elle vaut `-1`, et c'est ce que fait la version finale |
| Encodage toujours appliqué : à vérifier dans la spec | ✅ **Confirmé** | p. 92 : « encoded version must always be used » |
| `runCatching` attrape `CancellationException` | ✅ **Corrigé** | `try/catch` explicite, avec un test |
| Timeout sans ping 301 + exceptions avalées sans log | ✅ **Corrigé** | 300/301/100 par Wrapper + paramètre `logger` |
| `SimpleDateFormat` vs `java.time` | ✅ **Assumé** | minSdk 24 sans desugaring. Avec le desugaring, on prendrait `java.time` avec un `Clock` |

Un point manquait dans la revue : le remplacement des macros dans les **requêtes** VAST (§6.1, « party that performs the HTTP request »). Il est maintenant fait.

---

## 7. Et OpenRTB 2.6 ?

OpenRTB est le protocole d'**enchères** entre l'exchange (SSP) et les bidders (DSP). Le player n'y participe pas directement. Les liens avec votre poste :
- **Ses macros sont différentes** : `${AUCTION_PRICE}`, `${AUCTION_ID}`… (§4.5). Elles utilisent la syntaxe `${…}`, pas `[…]`, et c'est **l'exchange** qui les remplace, pas le player. Une valeur non disponible devient une chaîne vide, ou `"AUDIT"` en contexte de test. Le player ne doit pas y toucher : notre regex ne reconnaît que `[…]`, donc elles passent intactes.
- **Les listes partagées** : les valeurs de `[APIFRAMEWORKS]` et `[PLACEMENTTYPE]` viennent des listes AdCOM 1.0, auxquelles OpenRTB 2.6 renvoie. API Frameworks, de mémoire, à vérifier dans AdCOM : 1 VPAID 1.0, 2 VPAID 2.0, 3 MRAID 1.0, 4 ORMMA, 5 MRAID 2.0, 6 MRAID 3.0, 7 OMID 1.0, 8 SIMID 1.0. L'exemple de la spec VAST `2,7` signifie donc « VPAID 2 + OMID 1 ».
- **La chaîne complète** : player → Ad Proxy (requête VAST avec des macros `[…]`) → ad server / SSP → enchère OpenRTB → VAST renvoyé → player. Savoir situer le player dans cette chaîne est un bon signe de séniorité.

---

## 8. Comment le raconter vendredi (STAR, au « je »)

- **Situation** : chez Bedrock, dans l'équipe Player & Ads (6play, RTL+, Videoland), les pixels VAST contenaient des macros que le player devait remplacer.
- **Tâche** : implémenter `[CACHEBUSTING]` et `[TIMESTAMP]`, puis rendre le remplacement maintenable.
- **Action** : j'ai refactoré le remplacement avec le pattern Strategy, une stratégie par macro, pour pouvoir tester chacune isolément. J'ai débogué les pixels avec Charles Proxy.
- **Résultat** : *[à compléter avec un fait vrai : PR mergée, nombre de tests, bug évité…]*.
- **Recul** (ce qui distingue un senior) : « En reprenant le sujet avec la spec VAST 4.1 sous les yeux, j'ai identifié ce que je ferais différemment :
  - encoder systématiquement toutes les valeurs, comme le demande la spec ;
  - remplacer par -1 les macros de la spec non fournies ;
  - centraliser le parsing pour ne pas le dupliquer dans chaque stratégie ;
  - injecter l'horloge et l'aléa pour avoir des tests déterministes.

  Et j'ai vérifié qu'une suggestion d'IA, qui figeait le timestamp par événement, contredisait la spec. »

Phrase clé : « La stratégie calcule une valeur, l'expander applique les règles de la spec. »

---

## 9. Questions probables et réponses courtes

| Question | Réponse |
|---|---|
| Pourquoi Strategy plutôt qu'un `when` ? | Principe ouvert/fermé (on ajoute une macro sans modifier le parser), tests isolés, dépendances propres à chaque stratégie (fuseau horaire) |
| Comment tester `[TIMESTAMP]` ? | Injecter l'horloge et le fuseau, puis comparer à une chaîne exacte |
| Une macro inconnue ? | Si elle est dans la spec : -1 (ou -2 si on refuse de la partager). Si elle est hors spec : intacte |
| Faut-il encoder ? | Toujours, avec encodeURIComponent. Pour une liste, on encode chaque valeur et on garde des virgules brutes |
| Même cache-buster partout ? | Non, une valeur par occurrence. Sinon, deux trackers identiques produisent deux requêtes identiques |
| Qui remplace les macros en SSAI ? | Le serveur qui fait la requête (§6.1). En SSAI, c'est souvent le serveur de stitching |
| Et `${AUCTION_PRICE}` ? | C'est une macro OpenRTB : l'exchange la remplace, le player n'y touche pas |
| Wrapper qui ne répond pas ? | Erreur 301 envoyée à toutes les `<Error>` de la chaîne, et le contenu continue sans pub |

---

## 10. À apprendre « à la main » avant vendredi

Ce sont les choses que vous n'avez pas faites vous-même dans ce repo, ou pas récemment. Elles sont classées par priorité. Faites chaque exercice **sans IA**, puis comparez avec le code.

### Priorité 1 (aujourd'hui ou demain, environ 3 h)
1. **Réécrire de mémoire une stratégie et son test** (20 min) : `[PODSEQUENCE]` ou `[ADCOUNT]` (spec p. 95). Interface, classe, enregistrement dans `defaultStrategies()`, test. C'est votre sujet phare, il doit sortir sans hésiter.
2. **Le tableau des codes d'erreur VAST** (15 min) : 100, 300, 301, 302, 303, 400, 401, 402, 403, 405, 900. Récitez-le à voix haute.
3. **Les règles de macros** (10 min) : heure d'accès par URI, -1 / -2, encodeURIComponent, macros hors spec intactes, remplacement par celui qui fait la requête. Ce sont les 5 points de la section 2.
4. **Lire un vrai VAST** (20 min) : ouvrir dans le navigateur l'URL `VAST_SKIPPABLE_PREROLL` de `FakeContentRepository`. Repérer `InLine`, `Impression`, `Tracking`, `MediaFile`, `skipoffset`, `Error`, puis faire de même avec le VMAP pre/mid/post.
5. **Le cycle de vie d'un `AdsLoader`** (30 min) : dessiner sur papier `setPlayer → start → onAdPlaybackState → handlePrepareComplete/Error → stop → release`, puis vérifier dans `VmapAdsLoader.kt`.
6. **Construire un `AdPlaybackState` à la main** (20 min) : pre-roll, mid-roll à 30 s, post-roll (`C.TIME_END_OF_SOURCE`), en µs. Puis `withPlayedAd` et `withSkippedAd`.

### Priorité 2 (demain, environ 2 h)
7. **Le flux Widevine** (20 min) : PSSH dans le MPD → key request → POST de licence → CDM. L1 vs L3. Pourquoi l'écran est noir avec une `TextureView`.
8. **Lire le manifest DASH** (15 min) : ouvrir `tears.mpd` et repérer `Period` → `AdaptationSet` → `Representation`, ainsi que `ContentProtection` (UUID Widevine `edef8ba9-…`).
9. **Les coroutines** (30 min) : refaire un mini-test avec `runTest`, `delay`, `withTimeoutOrNull` et `advanceUntilIdle`. Savoir expliquer pourquoi on relance `CancellationException`.
10. **OMID et SIMID** (20 min, pas sur votre CV) : lire les pages de présentation de l'IAB. Phrase à préparer : « Je ne l'ai pas encore intégré, mais je sais que l'OM SDK mesure la visibilité, avec des friendly obstructions déclarées via `AdOverlayInfo`, et que `[OMIDPARTNER]` est requis dès qu'OM est supporté. »
11. **Pause ads** (15 min) : concevoir sur papier (`onIsPlayingChanged(false)` hors pub → overlay non linéaire → impression à l'affichage → fermeture à la reprise).
12. **CSAI vs SSAI** (10 min) : où sont les pubs, qui envoie le tracking, avantages et inconvénients.

### Priorité 3 (si vous avez le temps)
13. **`TRANSACTIONID`** (spec p. 95-96) : l'identifiant doit être le **même** pour toute la chaîne de requêtes. C'est le seul vrai cas où un « instantané partagé » est correct. C'est un bon contre-exemple à donner en entretien.
14. **Les macros `Array<T>`** : implémenter `[BLOCKEDADCATEGORIES]` avec un encodage valeur par valeur et des virgules brutes.
15. **La spec VMAP 1.0** : ses codes d'erreur propres (on réutilise ici le code VAST 100, ce qui est une simplification).
16. **`MediaSessionService`** : vous l'avez fait chez Bedrock pour Fire TV, rafraîchissez la mémoire en lisant la doc Media3 « background playback ».
17. **SurfaceView et punch hole** : savoir l'expliquer en 30 secondes.

---

## Sources
- VAST 4.1 : `VAST4.1-final-Nov-8-2018.pdf` (§2.3.5-2.3.6 erreurs p. 26-28, §6 macros p. 91-108). Dépôt IAB : https://github.com/InteractiveAdvertisingBureau/vast
- OpenRTB 2.6 : `OpenRTB-2-6_FINAL.pdf` (§4.5 Substitution Macros, p. 42-43)
- Macros VAST 4.x en ligne : https://interactiveadvertisingbureau.github.io/vast/vast4macros/vast4-macros-latest.html
- Pattern Strategy : https://refactoring.guru/fr/design-patterns/strategy
