package com.devsparkles.media3sample.core.domain.model

/**
 * RÔLE : représentation MÉTIER d'un planning publicitaire, issue du parsing VMAP + VAST.
 *
 * Rappel des formats IAB (Interactive Advertising Bureau) :
 *  - VAST (Video Ad Serving Template) : décrit UNE pub ou un "pod" de pubs : fichiers vidéo,
 *    durée, URLs de tracking (impression, quartiles...), clic, skip.
 *    Spec : https://iabtechlab.com/standards/vast/
 *  - VMAP (Video Multiple Ad Playlist) : décrit QUAND jouer des pubs dans un contenu :
 *    une liste d'AdBreak (pre-roll à "start", mid-roll à "00:10:00", post-roll à "end"),
 *    chacun pointant vers un VAST.
 *    Spec : https://iabtechlab.com/standards/vmap/
 *
 * Chaîne typique : App -> Ad Proxy (backend maison) -> renvoie un VMAP -> chaque AdBreak
 * pointe vers un VAST (souvent un "Wrapper" qui redirige vers un autre VAST "InLine").
 */
data class AdSchedule(val breaks: List<AdBreak>) {
    companion object {
        val EMPTY = AdSchedule(emptyList())
    }
}

/**
 * Une coupure publicitaire = un "ad pod" (1..n pubs jouées à la suite).
 * @property trackingEvents tracking propre au break (VMAP breakStart / breakEnd / error)
 */
data class AdBreak(
    val id: String,
    val position: AdBreakPosition,
    val ads: List<LinearAd>,
    val trackingEvents: Map<AdBreakEvent, List<String>> = emptyMap(),
)

/** Position du break dans le contenu. */
sealed interface AdBreakPosition {
    /** Avant le contenu (VMAP timeOffset="start"). */
    data object PreRoll : AdBreakPosition

    /** Pendant le contenu (VMAP timeOffset="HH:MM:SS.mmm"). */
    data class MidRoll(val offsetMs: Long) : AdBreakPosition

    /** Après le contenu (VMAP timeOffset="end"). */
    data object PostRoll : AdBreakPosition
}

enum class AdBreakEvent { BREAK_START, BREAK_END, ERROR }

/**
 * Une pub vidéo "linéaire" (elle interrompt le contenu, par opposition aux pubs
 * "non linéaires" en overlay, ou aux "pause ads" affichées quand l'utilisateur met en pause).
 *
 * @property skipOffsetMs à partir de quand le bouton "Passer" est disponible (VAST skipoffset).
 *           null = pub non skippable.
 * @property impressionUrls à appeler dès que la 1re frame est affichée (c'est ce qui est facturé !)
 * @property errorUrls à appeler en cas d'erreur, avec la macro [ERRORCODE] remplacée.
 */
data class LinearAd(
    val id: String,
    val sequence: Int,
    val durationMs: Long,
    val mediaFile: MediaFile,
    val skipOffsetMs: Long?,
    val clickThroughUrl: String?,
    val impressionUrls: List<String>,
    val errorUrls: List<String>,
    val trackingEvents: Map<AdTrackingEvent, List<String>>,
)

/** Un fichier vidéo candidat dans <MediaFiles>. Un VAST en propose plusieurs (bitrates, formats). */
data class MediaFile(
    val url: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bitrateKbps: Int?,
)

/**
 * Événements de tracking VAST qu'un player doit remonter. C'est la base de la facturation
 * et du reporting côté régie : une impression non envoyée = de l'argent perdu,
 * une impression envoyée deux fois = de la fraude involontaire. D'où l'importance des tests.
 */
enum class AdTrackingEvent(val vastName: String) {
    START("start"),
    FIRST_QUARTILE("firstQuartile"),
    MIDPOINT("midpoint"),
    THIRD_QUARTILE("thirdQuartile"),
    COMPLETE("complete"),
    SKIP("skip"),
    PAUSE("pause"),
    RESUME("resume"),
    MUTE("mute"),
    UNMUTE("unmute"),
    CLICK_TRACKING("clickTracking");

    companion object {
        fun fromVastName(name: String): AdTrackingEvent? = entries.firstOrNull { it.vastName == name }
    }
}
