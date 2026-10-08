package com.devsparkles.media3sample.core.domain.tracking

/**
 * RÔLE : diffuser chaque appel à PLUSIEURS trackers (fan-out), en les ISOLANT les uns des autres.
 *
 * Pattern Composite : le player ne voit qu'UN PlaybackTracker, quel que soit le nombre d'outils
 * branchés (Nielsen + outil maison + demain Médiamétrie). Ajouter un outil = une ligne dans
 * AppContainer, zéro modification du player.
 *
 * Isolation des exceptions : un SDK tiers qui plante (NPE dans son code, JSON mal formé...) ne
 * doit casser NI la lecture NI les autres mesures. On attrape `Exception` (pas `Throwable` :
 * une OutOfMemoryError doit continuer à remonter) et on la signale via [onTrackerError].
 *
 * Note : on n'a pas de coroutines ici, donc pas de CancellationException à relancer.
 */
class CompositeTracker(
    private val trackers: List<PlaybackTracker>,
    private val onTrackerError: (tracker: PlaybackTracker, error: Exception) -> Unit = { _, _ -> },
) : PlaybackTracker {

    override val name: String = "Composite(${trackers.joinToString { it.name }})"

    private inline fun forEachIsolated(call: PlaybackTracker.() -> Unit) {
        for (tracker in trackers) {
            try {
                tracker.call()
            } catch (e: Exception) {
                onTrackerError(tracker, e)
            }
        }
    }

    override fun onSessionStart(content: TrackedContent, positionMs: Long) = forEachIsolated { onSessionStart(content, positionMs) }
    override fun onPause(reason: PauseReason, playhead: Playhead) = forEachIsolated { onPause(reason, playhead) }
    override fun onResume(playhead: Playhead) = forEachIsolated { onResume(playhead) }
    override fun onSeek(fromMs: Long, toMs: Long) = forEachIsolated { onSeek(fromMs, toMs) }

    // Surchargé pour déléguer à la version de CHAQUE tracker (Nielsen a la sienne),
    // et non à l'implémentation par défaut de l'interface.
    override fun onContentChange(previous: TrackedContent, next: TrackedContent, positionMs: Long, exitPlayhead: Playhead?) =
        forEachIsolated { onContentChange(previous, next, positionMs, exitPlayhead) }

    override fun onAdBreakStart(adBreak: TrackedAdBreak) = forEachIsolated { onAdBreakStart(adBreak) }
    override fun onAdStart(ad: TrackedAd, exitPlayhead: Playhead?) = forEachIsolated { onAdStart(ad, exitPlayhead) }
    override fun onAdBreakEnd(adBreak: TrackedAdBreak, resumesContent: Boolean, exitPlayhead: Playhead?) =
        forEachIsolated { onAdBreakEnd(adBreak, resumesContent, exitPlayhead) }
    override fun onSessionEnd(reason: SessionEndReason, finalPlayhead: Playhead?) = forEachIsolated { onSessionEnd(reason, finalPlayhead) }
    override fun onPlayheadTick(playhead: Playhead) = forEachIsolated { onPlayheadTick(playhead) }
    override fun onAppBackground() = forEachIsolated { onAppBackground() }
    override fun onAppForeground() = forEachIsolated { onAppForeground() }
    override fun onAppClose() = forEachIsolated { onAppClose() }
}
