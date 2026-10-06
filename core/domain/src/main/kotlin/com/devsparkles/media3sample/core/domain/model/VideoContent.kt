package com.devsparkles.media3sample.core.domain.model

/**
 * RÔLE : modèle métier d'un contenu vidéo à lire.
 *
 * Volontairement indépendant de Media3 : on ne manipule PAS de `MediaItem` ici.
 * La traduction VideoContent -> MediaItem est faite dans :player:engine (MediaItemMapper).
 * C'est le principe "le domaine ne connaît pas les détails techniques".
 *
 * @property streamUrl URL du manifest (.mpd pour DASH, .m3u8 pour HLS, .mp4 pour progressif)
 * @property drm configuration DRM si le flux est chiffré, null si le flux est en clair
 * @property adTagUrl URL du VMAP renvoyé par l'Ad Proxy (null = pas de pub)
 */
data class VideoContent(
    val id: String,
    val title: String,
    val streamUrl: String,
    val streamType: StreamType,
    val drm: DrmConfig? = null,
    val adTagUrl: String? = null,
)

/**
 * Type de flux. Utile pour donner un "hint" au player (mimeType) afin qu'il choisisse
 * directement la bonne MediaSource sans deviner à partir de l'extension de l'URL.
 *
 * - DASH : MPEG-DASH, standard ISO. Manifest XML (.mpd) qui décrit des Periods > AdaptationSets
 *   > Representations (une par bitrate). Le player choisit la Representation selon la bande
 *   passante (ABR = Adaptive BitRate). Doc : https://developer.android.com/media/media3/exoplayer/dash
 * - HLS : équivalent Apple (.m3u8).
 * - PROGRESSIVE : simple fichier MP4 (c'est le cas des pubs VAST la plupart du temps).
 */
enum class StreamType { DASH, HLS, PROGRESSIVE }

/**
 * Configuration DRM (Digital Rights Management).
 *
 * WIDEVINE (Google) est le DRM natif d'Android, exposé via l'API système `MediaDrm`.
 * Fonctionnement simplifié :
 *   1. Le manifest DASH contient un <ContentProtection> avec le PSSH (identifiant des clés).
 *   2. Le player crée une session MediaDrm et génère une "key request" (challenge).
 *   3. Cette requête est envoyée en POST au serveur de licence (licenseUrl), souvent
 *      avec un token d'authentification dans les headers (requestHeaders).
 *   4. Le serveur renvoie une licence contenant les clés (chiffrées pour CET appareil).
 *   5. Le CDM (Content Decryption Module) déchiffre les segments, idéalement dans le TEE
 *      (Widevine L1 = déchiffrement + décodage matériel sécurisé, requis pour HD/4K ;
 *       L3 = logiciel, souvent limité à la SD par les ayants droit).
 * Doc : https://developer.android.com/media/media3/exoplayer/drm
 *       https://developers.google.com/widevine/drm/overview
 *
 * @property multiSession true si le flux fait de la rotation de clés (plusieurs licences)
 *           ou mélange audio/vidéo avec des clés différentes.
 */
data class DrmConfig(
    val scheme: DrmScheme,
    val licenseUrl: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val multiSession: Boolean = false,
)

enum class DrmScheme { WIDEVINE, PLAYREADY, CLEARKEY }
