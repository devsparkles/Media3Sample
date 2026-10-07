package com.devsparkles.media3sample.core.data.tracking

import com.devsparkles.media3sample.core.domain.tracking.AdBreakKind
import com.devsparkles.media3sample.core.domain.tracking.PauseReason
import com.devsparkles.media3sample.core.domain.tracking.PlaybackTracker
import com.devsparkles.media3sample.core.domain.tracking.Playhead
import com.devsparkles.media3sample.core.domain.tracking.SessionEndReason
import com.devsparkles.media3sample.core.domain.tracking.TrackedAd
import com.devsparkles.media3sample.core.domain.tracking.TrackedAdBreak
import com.devsparkles.media3sample.core.domain.tracking.TrackedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * SUITE DE TESTS DE CONTRAT : ce que TOUT [PlaybackTracker] doit garantir, quelle que soit sa
 * cible (SDK Nielsen, backend maison...). Chaque implémentation l'hérite et fournit :
 *  - [newTracker] : une instance branchée sur un faux transport qui enregistre ;
 *  - [outputs] : ce qui a été émis vers ce transport, traduit en catégories génériques.
 *
 * Invariants vérifiés :
 *  1. pas d'appel « hors session » : aucune activité avant l'ouverture ni après la fermeture ;
 *  2. pas de double fin : une session se ferme au plus une fois, même si on insiste ;
 *  3. ordre : Ouverture, activité..., Fermeture (les événements d'app sont libres) ;
 *  4. robustesse : aucune exception, même sur une séquence d'appels aberrante.
 *
 * Même idée que les « contract tests » de Media3 (ex : PlayerAudioFocusContractTest dans
 * media3-test-utils-robolectric) : une spécification exécutable partagée.
 */
abstract class PlaybackTrackerContractTest {

    /** Catégories génériques des sorties d'un tracker. */
    sealed interface Output {
        data object Open : Output
        data object Close : Output
        data class Activity(val name: String) : Output
        data class AppLifecycle(val name: String) : Output
    }

    protected abstract fun newTracker(): PlaybackTracker

    protected abstract fun outputs(): List<Output>

    private lateinit var tracker: PlaybackTracker

    private val movie = TrackedContent("movie", "Film", 60_000, isLive = false)
    private val episode = TrackedContent("episode", "Épisode", 30_000, isLive = false)
    private val midRoll = TrackedAdBreak(groupIndex = 1, kind = AdBreakKind.MIDROLL, adCount = 1)
    private val ad = TrackedAd(midRoll, indexInBreak = 0, id = "ad-1", durationMs = 15_000)
    private fun playhead(ms: Long, isAd: Boolean = false) = Playhead(ms, isAd, isLive = false, unixTimeMs = 1_791_378_000_000 + ms)

    @Before
    fun setUpTracker() {
        tracker = newTracker()
    }

    /** Vérifie les invariants 1 à 3 sur la séquence de sorties. */
    private fun assertWellFormed(outputs: List<Output> = outputs()) {
        var open = false
        outputs.forEachIndexed { i, output ->
            when (output) {
                Output.Open -> { assertFalse("session ouverte deux fois (#$i) dans $outputs", open); open = true }
                Output.Close -> { assertTrue("fin sans session ouverte (#$i) dans $outputs", open); open = false }
                is Output.Activity -> assertTrue("appel hors session : $output (#$i) dans $outputs", open)
                is Output.AppLifecycle -> Unit
            }
        }
    }

    private fun sessionOutputs() = outputs().filter { it !is Output.AppLifecycle }

    @Test
    fun `nothing is emitted before a session starts`() {
        tracker.onPlayheadTick(playhead(1_000))
        tracker.onPause(PauseReason.USER, playhead(1_000))
        tracker.onResume(playhead(1_000))
        tracker.onSeek(0, 10_000)
        tracker.onAdBreakStart(midRoll)
        tracker.onAdStart(ad)
        tracker.onAdBreakEnd(midRoll, resumesContent = true)
        tracker.onSessionEnd(SessionEndReason.COMPLETED, playhead(1_000))
        assertEquals(emptyList<Output>(), sessionOutputs())
    }

    @Test
    fun `a full session opens first, closes last, exactly once`() {
        tracker.onSessionStart(movie, 0)
        tracker.onPlayheadTick(playhead(1_000))
        tracker.onPause(PauseReason.USER, playhead(1_500))
        tracker.onResume(playhead(1_500))
        tracker.onSeek(1_500, 20_000)
        tracker.onAdBreakStart(midRoll)
        tracker.onAdStart(ad)
        tracker.onPlayheadTick(playhead(1_000, isAd = true))
        tracker.onAdBreakEnd(midRoll, resumesContent = true)
        tracker.onPlayheadTick(playhead(21_000))
        tracker.onSessionEnd(SessionEndReason.COMPLETED, playhead(60_000))

        assertWellFormed()
        val session = sessionOutputs()
        assertEquals(Output.Open, session.first())
        assertEquals(Output.Close, session.last())
        assertEquals(1, session.count { it == Output.Close })
    }

    @Test
    fun `ending twice closes only once`() {
        tracker.onSessionStart(movie, 0)
        tracker.onSessionEnd(SessionEndReason.ERROR, playhead(3_000))
        tracker.onSessionEnd(SessionEndReason.RELEASED, playhead(3_000))
        assertWellFormed()
        assertEquals(1, outputs().count { it == Output.Close })
    }

    @Test
    fun `calls after the end are ignored`() {
        tracker.onSessionStart(movie, 0)
        tracker.onSessionEnd(SessionEndReason.COMPLETED, playhead(60_000))
        val before = sessionOutputs()
        tracker.onPlayheadTick(playhead(61_000))
        tracker.onPause(PauseReason.INTERRUPTION, playhead(61_000))
        tracker.onResume(playhead(61_000))
        tracker.onAdStart(ad)
        tracker.onAdBreakEnd(midRoll, resumesContent = true)
        assertEquals(before, sessionOutputs())
    }

    @Test
    fun `content change closes the previous session before opening the next one`() {
        tracker.onSessionStart(movie, 0)
        tracker.onPlayheadTick(playhead(1_000))
        tracker.onContentChange(movie, episode, 0)
        tracker.onPlayheadTick(playhead(1_000))
        tracker.onSessionEnd(SessionEndReason.RELEASED, playhead(1_000))
        assertWellFormed()
        assertEquals(2, outputs().count { it == Output.Open })
        assertEquals(2, outputs().count { it == Output.Close })
    }

    @Test
    fun `repeat restarts a session on the same content`() {
        tracker.onSessionStart(movie, 0)
        tracker.onSessionEnd(SessionEndReason.COMPLETED, playhead(60_000))
        tracker.onSessionStart(movie, 0)
        tracker.onSessionEnd(SessionEndReason.RELEASED, playhead(2_000))
        assertWellFormed()
        assertEquals(2, outputs().count { it == Output.Open })
    }

    @Test
    fun `app lifecycle calls are accepted at any time`() {
        tracker.onAppBackground()
        tracker.onAppForeground()
        tracker.onSessionStart(movie, 0)
        tracker.onAppBackground()
        tracker.onAppForeground()
        tracker.onSessionEnd(SessionEndReason.RELEASED, null)
        assertWellFormed()
    }

    @Test
    fun `never throws and stays well formed on a random call sequence`() {
        val random = Random(42)
        val calls: List<PlaybackTracker.() -> Unit> = listOf(
            { onSessionStart(if (random.nextBoolean()) movie else episode, 0) },
            { onPause(PauseReason.entries[random.nextInt(PauseReason.entries.size)], playhead(random.nextInt(60_000).toLong())) },
            { onResume(playhead(1_000)) },
            { onSeek(0, random.nextInt(60_000).toLong()) },
            { onContentChange(movie, episode, 0) },
            { onAdBreakStart(midRoll) },
            { onAdStart(ad) },
            { onAdBreakEnd(midRoll, random.nextBoolean()) },
            { onSessionEnd(SessionEndReason.entries[random.nextInt(SessionEndReason.entries.size)], playhead(2_000)) },
            { onPlayheadTick(playhead(random.nextInt(60_000).toLong(), isAd = random.nextBoolean())) },
            { onAppBackground() },
            { onAppForeground() },
        )
        repeat(500) { calls[random.nextInt(calls.size)](tracker) }
        assertWellFormed()
    }
}
