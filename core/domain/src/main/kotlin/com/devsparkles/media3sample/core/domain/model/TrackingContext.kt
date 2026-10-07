package com.devsparkles.media3sample.core.domain.model

/**
 * RÔLE : informations connues par le PLAYER au moment d'un événement de tracking,
 * nécessaires pour remplacer certaines macros VAST dans les URLs.
 *
 * Exemples :
 *  - erreur 405 sur une pub    -> TrackingContext(errorCode = 405, assetUri = url du mp4)
 *  - quartile à 7,5 s de la pub -> TrackingContext(adPlayheadMs = 7_500, assetUri = ...)
 *
 * Ce qui dépend du moment de la REQUÊTE HTTP ([TIMESTAMP], [CACHEBUSTING]) n'est PAS ici :
 * la spec VAST 4.1 (§6.2) définit [TIMESTAMP] comme « l'heure à laquelle l'URI est appelée ».
 * Ces valeurs sont donc calculées par MacroExpander au moment de l'envoi.
 */
data class TrackingContext(
    /** Code d'erreur VAST -> [ERRORCODE]. */
    val errorCode: Int? = null,
    /** Position dans la pub en ms -> [ADPLAYHEAD]. */
    val adPlayheadMs: Long? = null,
    /** URL du fichier vidéo de la pub en cours -> [ASSETURI]. */
    val assetUri: String? = null,
    /** Position du break (pre/mid/post) -> [BREAKPOSITION]. */
    val breakPosition: AdBreakPosition? = null,
    /** Position dans le CONTENU (pas la pub) en ms -> [MEDIAPLAYHEAD] (et [CONTENTPLAYHEAD], déprécié). */
    val contentPlayheadMs: Long? = null,
)
