package com.devsparkles.media3sample.core.domain.tracking

/**
 * RÔLE : les événements NORMALISÉS produits par [PlaybackSessionStateMachine].
 *
 * Pourquoi une couche d'événements entre le player et les trackers ?
 *  - les callbacks Media3 sont nombreux, arrivent groupés et dans un ordre qui n'est pas un
 *    contrat : on les consolide UNE fois, ici, plutôt que dans chaque tracker ;
 *  - ces événements sont des valeurs (data class) : faciles à logger, à comparer dans un test
 *    (« la séquence attendue est [SessionStarted, AdBreakStarted, ...] »), à rejouer.
 */
sealed interface PlaybackEvent {
    data class SessionStarted(val content: TrackedContent, val positionMs: Long) : PlaybackEvent
    data class Paused(val reason: PauseReason, val playhead: Playhead) : PlaybackEvent
    data class Resumed(val playhead: Playhead) : PlaybackEvent
    data class Seeked(val fromMs: Long, val toMs: Long) : PlaybackEvent
    /*
     * `exitPlayhead` (transitions entre assets) : dernière position de l'asset QU'ON QUITTE
     * (le contenu avant une pub, la pub avant la suivante ou avant le retour au contenu).
     * Nielsen l'exige avant stop/end/loadMetadata ; null si inconnue.
     */
    data class ContentChanged(
        val previous: TrackedContent,
        val next: TrackedContent,
        val positionMs: Long,
        val exitPlayhead: Playhead? = null,
    ) : PlaybackEvent
    data class AdBreakStarted(val adBreak: TrackedAdBreak) : PlaybackEvent
    data class AdStarted(val ad: TrackedAd, val exitPlayhead: Playhead? = null) : PlaybackEvent
    data class AdBreakEnded(val adBreak: TrackedAdBreak, val resumesContent: Boolean, val exitPlayhead: Playhead? = null) : PlaybackEvent
    data class Ended(val reason: SessionEndReason, val finalPlayhead: Playhead?) : PlaybackEvent

    /** Le player est libéré : plus aucun événement après celui-ci. */
    data object Released : PlaybackEvent
    data class Tick(val playhead: Playhead) : PlaybackEvent
}

/**
 * Traduit un événement normalisé en appel de [PlaybackTracker]. Un seul `when` exhaustif :
 * si on ajoute un événement, le compilateur oblige à décider comment le dispatcher.
 */
fun PlaybackEvent.dispatchTo(tracker: PlaybackTracker) {
    when (this) {
        is PlaybackEvent.SessionStarted -> tracker.onSessionStart(content, positionMs)
        is PlaybackEvent.Paused -> tracker.onPause(reason, playhead)
        is PlaybackEvent.Resumed -> tracker.onResume(playhead)
        is PlaybackEvent.Seeked -> tracker.onSeek(fromMs, toMs)
        is PlaybackEvent.ContentChanged -> tracker.onContentChange(previous, next, positionMs, exitPlayhead)
        is PlaybackEvent.AdBreakStarted -> tracker.onAdBreakStart(adBreak)
        is PlaybackEvent.AdStarted -> tracker.onAdStart(ad, exitPlayhead)
        is PlaybackEvent.AdBreakEnded -> tracker.onAdBreakEnd(adBreak, resumesContent, exitPlayhead)
        is PlaybackEvent.Ended -> tracker.onSessionEnd(reason, finalPlayhead)
        is PlaybackEvent.Tick -> tracker.onPlayheadTick(playhead)
        // La fin de session a déjà été émise (Ended) juste avant : rien de plus pour les trackers.
        PlaybackEvent.Released -> Unit
    }
}
