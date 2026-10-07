package com.devsparkles.media3sample.core.domain.model

/**
 * RÔLE : informations connues par le PLAYER au moment d'un événement de tracking,
 * nécessaires pour remplacer certaines macros VAST dans les URLs.
 *
 * Exemples :
 *  - erreur 405 sur une pub    -> TrackingContext(errorCode = 405, assetUri = url du mp4)
 *  - quartile à 7,5 s de la pub -> TrackingContext(adPlayheadMs = 7_500, assetUri = ...)
 *
 * Ce qui ne dépend PAS du player (heure, nombre aléatoire anti-cache) est ajouté par la
 * couche data au moment de l'envoi (voir MacroExpander).
 */
data class TrackingContext(
    /** Code d'erreur VAST -> [ERRORCODE]. */
    val errorCode: Int? = null,
    /** Position dans la pub en ms -> [ADPLAYHEAD]. */
    val adPlayheadMs: Long? = null,
    /** URL du fichier vidéo de la pub en cours -> [ASSETURI]. */
    val assetUri: String? = null,
)
