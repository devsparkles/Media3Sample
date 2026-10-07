package com.devsparkles.media3sample.core.domain.tracking

/**
 * RÔLE : modèles du TRACKING DE LECTURE (mesure d'audience : Nielsen, outil maison...).
 *
 * À NE PAS CONFONDRE avec le tracking PUB (AdTracker, pixels VAST) :
 *  - AdTracker      : « la régie X a-t-elle bien diffusé sa pub ? » -> GET sur des URLs fournies
 *                     par le VAST, une fois par événement (impression, quartiles...).
 *  - PlaybackTracker : « combien de temps cet utilisateur a-t-il regardé ce programme, et
 *                     quelles pubs ? » -> SDK de mesure (Nielsen DCR) ou backend maison,
 *                     avec un battement de cœur (playhead) toutes les secondes.
 * Les deux coexistent dans un vrai player et n'ont ni le même contrat, ni le même cycle de vie.
 *
 * Ces modèles sont en Kotlin pur : aucune notion de Media3 ici (c'est :player:engine qui
 * traduit un `MediaItem` en [TrackedContent]).
 */

/**
 * Le contenu mesuré.
 *
 * @property id identifiant stable du contenu (MediaItem.mediaId -> VideoContent.id)
 * @property durationMs durée du contenu (hors pubs), null si inconnue (timeline pas encore chargée)
 * @property isLive true pour un direct : Nielsen attend alors une heure UTC comme playhead.
 */
data class TrackedContent(
    val id: String,
    val title: String,
    val durationMs: Long?,
    val isLive: Boolean,
)

/** Position du break dans le contenu (la terminologie VMAP/Nielsen : preroll, midroll, postroll). */
enum class AdBreakKind { PREROLL, MIDROLL, POSTROLL }

/**
 * Un break publicitaire (= un « ad group » de l'AdPlaybackState Media3).
 *
 * @property groupIndex index du groupe dans l'AdPlaybackState (identifie le break)
 * @property adCount nombre de pubs prévues dans le break
 */
data class TrackedAdBreak(
    val groupIndex: Int,
    val kind: AdBreakKind,
    val adCount: Int,
)

/**
 * Une pub dans un break.
 *
 * @property id identifiant de la pub (VAST `<Ad id>`), indispensable pour Nielsen (`assetid`).
 */
data class TrackedAd(
    val adBreak: TrackedAdBreak,
    val indexInBreak: Int,
    val id: String,
    val durationMs: Long?,
)

/**
 * Photographie de la tête de lecture, envoyée toutes les secondes ([PlaybackEvent.Tick]) et à
 * chaque pause/fin (dernière position connue).
 *
 * @property positionMs position dans l'asset EN COURS : dans la pub pendant une pub, dans le
 *           contenu sinon. (Nielsen mesure chaque asset séparément.)
 * @property unixTimeMs heure murale UTC au moment de la mesure (playhead d'un direct pour Nielsen).
 */
data class Playhead(
    val positionMs: Long,
    val isAd: Boolean,
    val isLive: Boolean,
    val unixTimeMs: Long,
) {
    override fun toString() = "${positionMs}ms${if (isAd) " (pub)" else ""}${if (isLive) " live" else ""}"
}

/**
 * Pourquoi la lecture s'est arrêtée. Le cœur DISTINGUE les causes ; chaque tracker décide
 * ensuite quoi en faire (Nielsen fait `stop()` dans tous les cas, un outil maison peut vouloir
 * séparer « pause volontaire » et « interruption système »).
 */
enum class PauseReason {
    /** Bouton pause, ou pause programmatique (ex : app en arrière-plan). */
    USER,

    /** Perte DÉFINITIVE du focus audio (une autre app de lecture a démarré). */
    AUDIO_FOCUS_LOSS,

    /** Casque débranché / Bluetooth déconnecté (intent AUDIO_BECOMING_NOISY). */
    AUDIO_BECOMING_NOISY,

    /**
     * Interruption TRANSITOIRE : appel téléphonique, alarme... Media3 garde
     * `playWhenReady = true` mais SUPPRIME la lecture (`playbackSuppressionReason`).
     * Ce n'est pas une pause utilisateur : l'utilisateur veut toujours regarder.
     */
    INTERRUPTION,

    /** Sortie audio inadaptée (ex : haut-parleur interne d'une montre Wear OS). */
    UNSUITABLE_OUTPUT,

    OTHER,
}

/** Pourquoi une session de mesure se termine. */
enum class SessionEndReason {
    /** Fin naturelle du contenu (STATE_ENDED, ou une boucle en mode repeat). */
    COMPLETED,

    /** Passage à un autre contenu (playlist). */
    CONTENT_CHANGED,

    /** Erreur fatale du player (licence DRM refusée, réseau...). */
    ERROR,

    /** Le player a été arrêté (`player.stop()`) sans erreur. */
    STOPPED,

    /** Le player est libéré (sortie de l'écran, `onCleared`). */
    RELEASED,
}
