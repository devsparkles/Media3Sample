package com.devsparkles.media3sample.player.engine.ads

import com.devsparkles.media3sample.core.domain.model.AdTrackingEvent

/**
 * RÔLE : décider QUAND envoyer les événements de progression d'UNE pub (start, quartiles,
 * complete), en garantissant que chaque événement part UNE SEULE fois.
 *
 * Volontairement sans dépendance Android/Media3 -> testable en JVM (voir AdProgressTrackerTest).
 * Le VmapAdsLoader l'alimente avec la position du player toutes les ~200 ms.
 *
 * Pourquoi c'est subtil :
 *  - un seek ou un saut de position peut faire franchir plusieurs seuils d'un coup
 *    -> on renvoie TOUS les événements franchis, dans l'ordre ;
 *  - un retour en arrière ne doit pas les renvoyer -> ensemble `fired`.
 */
class AdProgressTracker {

    private val fired = mutableSetOf<AdTrackingEvent>()

    fun onProgress(positionMs: Long, durationMs: Long): List<AdTrackingEvent> {
        if (durationMs <= 0) return emptyList()
        val progress = positionMs.toDouble() / durationMs
        return THRESHOLDS
            .filter { (event, threshold) -> progress >= threshold && event !in fired }
            .map { (event, _) -> event.also { fired += it } }
    }

    /** @return true si "complete" n'avait pas encore été envoyé. */
    fun markComplete(): Boolean = fired.add(AdTrackingEvent.COMPLETE)

    private companion object {
        val THRESHOLDS = listOf(
            AdTrackingEvent.START to 0.0,
            AdTrackingEvent.FIRST_QUARTILE to 0.25,
            AdTrackingEvent.MIDPOINT to 0.5,
            AdTrackingEvent.THIRD_QUARTILE to 0.75,
        )
    }
}
