package com.devsparkles.media3sample.core.domain.tracking

import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent.AdBreakEnded
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent.AdBreakStarted
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent.AdStarted
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent.ContentChanged
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent.Ended
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent.Paused
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent.Released
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent.Resumed
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent.Seeked
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent.SessionStarted
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la state machine en JVM pure : on rejoue des suites de snapshots comme le ferait
 * Media3, et on vérifie la séquence EXACTE d'événements normalisés.
 */
class PlaybackSessionStateMachineTest {

    private val movie = TrackedContent(id = "movie", title = "Film", durationMs = 60_000, isLive = false)
    private val episode = TrackedContent(id = "episode", title = "Épisode", durationMs = 30_000, isLive = false)

    private val preRoll = TrackedAdBreak(groupIndex = 0, kind = AdBreakKind.PREROLL, adCount = 2)
    private val postRoll = TrackedAdBreak(groupIndex = 1, kind = AdBreakKind.POSTROLL, adCount = 1)

    private val machine = PlaybackSessionStateMachine()

    private fun snapshot(
        status: PlaybackStatus = PlaybackStatus.READY,
        playWhenReady: Boolean = true,
        suppression: Suppression = Suppression.NONE,
        content: TrackedContent? = movie,
        ad: TrackedAd? = null,
        positionMs: Long = 0,
    ): PlayerSnapshot {
        val isPlaying = status == PlaybackStatus.READY && playWhenReady && suppression == Suppression.NONE
        return PlayerSnapshot(
            content = content,
            status = status,
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            suppression = suppression,
            ad = ad,
            contentPositionMs = if (ad == null) positionMs else 0,
            playhead = Playhead(positionMs, isAd = ad != null, isLive = false, unixTimeMs = 0),
        )
    }

    private fun ad(adBreak: TrackedAdBreak, index: Int) = TrackedAd(adBreak, index, id = "ad-${adBreak.groupIndex}-$index", durationMs = 5_000)

    /** Démarre une session normale à la position 0 et renvoie les événements produits. */
    private fun start() = machine.onSnapshot(snapshot())

    private inline fun <reified T> List<PlaybackEvent>.ofType() = filterIsInstance<T>()

    // --- Démarrage --------------------------------------------------------------------------

    @Test
    fun `no session while preparing or buffering, it starts on the first READY with playWhenReady`() {
        assertEquals(emptyList<PlaybackEvent>(), machine.onSnapshot(snapshot(status = PlaybackStatus.IDLE)))
        assertEquals(emptyList<PlaybackEvent>(), machine.onSnapshot(snapshot(status = PlaybackStatus.BUFFERING)))
        assertEquals(listOf(SessionStarted(movie, 0)), machine.onSnapshot(snapshot()))
    }

    @Test
    fun `a prepared but not played media does not open a session`() {
        assertEquals(emptyList<PlaybackEvent>(), machine.onSnapshot(snapshot(playWhenReady = false)))
    }

    // --- Pause / reprise / interruption -----------------------------------------------------

    @Test
    fun `pause then resume`() {
        start()
        val pause = machine.onSnapshot(snapshot(playWhenReady = false, positionMs = 4_000), SnapshotHints(pauseReason = PauseReason.USER))
        assertEquals(listOf(Paused(PauseReason.USER, Playhead(4_000, false, false, 0))), pause)
        assertEquals(1, machine.onSnapshot(snapshot(positionMs = 4_000)).ofType<Resumed>().size)
    }

    @Test
    fun `a repeated identical snapshot emits nothing`() {
        start()
        machine.onSnapshot(snapshot(playWhenReady = false))
        assertEquals(emptyList<PlaybackEvent>(), machine.onSnapshot(snapshot(playWhenReady = false)))
    }

    @Test
    fun `buffering is not a pause`() {
        start()
        assertEquals(emptyList<PlaybackEvent>(), machine.onSnapshot(snapshot(status = PlaybackStatus.BUFFERING, positionMs = 3_000)))
        assertEquals(emptyList<PlaybackEvent>(), machine.onSnapshot(snapshot(positionMs = 3_000)))
    }

    @Test
    fun `transient audio focus loss is an interruption, not a user pause`() {
        start()
        val events = machine.onSnapshot(snapshot(suppression = Suppression.TRANSIENT_AUDIO_FOCUS_LOSS))
        assertEquals(PauseReason.INTERRUPTION, events.ofType<Paused>().single().reason)
        assertEquals(1, machine.onSnapshot(snapshot()).ofType<Resumed>().size)
    }

    @Test
    fun `scrubbing suppression is not an interruption`() {
        start()
        assertEquals(emptyList<PlaybackEvent>(), machine.onSnapshot(snapshot(suppression = Suppression.SCRUBBING)))
    }

    // --- Seek ---------------------------------------------------------------------------------

    @Test
    fun `seek only emits Seeked, never an implicit pause or end`() {
        start()
        val events = machine.onSnapshot(snapshot(status = PlaybackStatus.BUFFERING, positionMs = 30_000), SnapshotHints(seek = SnapshotHints.Seek(5_000, 30_000)))
        assertEquals(listOf(Seeked(5_000, 30_000)), events)
    }

    @Test
    fun `seek while paused keeps the session paused`() {
        start()
        machine.onSnapshot(snapshot(playWhenReady = false))
        val events = machine.onSnapshot(snapshot(playWhenReady = false, positionMs = 10_000), SnapshotHints(seek = SnapshotHints.Seek(0, 10_000)))
        assertEquals(listOf(Seeked(0, 10_000)), events)
    }

    @Test
    fun `seek to the end closes the session once`() {
        start()
        val events = machine.onSnapshot(snapshot(status = PlaybackStatus.ENDED, positionMs = 60_000), SnapshotHints(seek = SnapshotHints.Seek(5_000, 60_000)))
        assertEquals(listOf(Ended(SessionEndReason.COMPLETED, Playhead(60_000, false, false, 0))), events)
    }

    // --- Fin -----------------------------------------------------------------------------------

    @Test
    fun `end of content closes the session exactly once`() {
        start()
        assertEquals(1, machine.onSnapshot(snapshot(status = PlaybackStatus.ENDED)).ofType<Ended>().size)
        assertEquals(emptyList<PlaybackEvent>(), machine.onSnapshot(snapshot(status = PlaybackStatus.ENDED)))
        assertEquals(listOf(Released), machine.release(snapshot(status = PlaybackStatus.ENDED)))
    }

    @Test
    fun `replaying after the end opens a new session`() {
        start()
        machine.onSnapshot(snapshot(status = PlaybackStatus.ENDED))
        machine.onSnapshot(snapshot(status = PlaybackStatus.BUFFERING), SnapshotHints(seek = SnapshotHints.Seek(60_000, 0)))
        assertEquals(listOf(SessionStarted(movie, 0)), machine.onSnapshot(snapshot()))
    }

    @Test
    fun `release ends the session then emits Released, and is idempotent`() {
        start()
        val events = machine.release(snapshot(positionMs = 7_000))
        assertEquals(listOf(Ended(SessionEndReason.RELEASED, Playhead(7_000, false, false, 0)), Released), events)
        assertEquals(emptyList<PlaybackEvent>(), machine.release(snapshot()))
        assertEquals(emptyList<PlaybackEvent>(), machine.onSnapshot(snapshot()))
    }

    @Test
    fun `player stop closes the session`() {
        start()
        assertEquals(SessionEndReason.STOPPED, machine.onSnapshot(snapshot(status = PlaybackStatus.IDLE)).ofType<Ended>().single().reason)
    }

    // --- Erreur / interruption fatale -----------------------------------------------------------

    @Test
    fun `a fatal error ends the session, a retry opens a new one`() {
        start()
        val error = machine.onSnapshot(snapshot(status = PlaybackStatus.IDLE), SnapshotHints(errorOccurred = true))
        assertEquals(listOf(SessionEndReason.ERROR), error.ofType<Ended>().map { it.reason })
        // Bouton "Réessayer" : prepare() -> BUFFERING -> READY.
        assertEquals(emptyList<PlaybackEvent>(), machine.onSnapshot(snapshot(status = PlaybackStatus.BUFFERING)))
        assertEquals(listOf(SessionStarted(movie, 0)), machine.onSnapshot(snapshot()))
    }

    @Test
    fun `behind live window recovery ends the session even if prepare was already called`() {
        start()
        // Le ViewModel a appelé prepare() DANS onPlayerError : l'état consolidé est déjà BUFFERING.
        val events = machine.onSnapshot(snapshot(status = PlaybackStatus.BUFFERING), SnapshotHints(errorOccurred = true))
        assertEquals(1, events.ofType<Ended>().size)
        assertEquals(listOf(SessionStarted(movie, 0)), machine.onSnapshot(snapshot()))
    }

    // --- Changement de contenu / repeat ---------------------------------------------------------

    @Test
    fun `content change emits a single ContentChanged`() {
        start()
        val events = machine.onSnapshot(snapshot(content = episode))
        assertEquals(listOf(ContentChanged(movie, episode, 0, exitPlayhead = Playhead(0, false, false, 0))), events)
        assertEquals(emptyList<PlaybackEvent>(), machine.onSnapshot(snapshot(content = episode)))
    }

    @Test
    fun `repeat mode closes the session and opens a new one on the same content`() {
        start()
        val events = machine.onSnapshot(snapshot(positionMs = 0), SnapshotHints(repeated = true))
        assertEquals(listOf(Ended::class, SessionStarted::class), events.map { it::class })
        assertEquals(SessionEndReason.COMPLETED, events.ofType<Ended>().single().reason)
    }

    // --- Pubs ----------------------------------------------------------------------------------

    @Test
    fun `pre-roll with two ads then content`() {
        val first = machine.onSnapshot(snapshot(ad = ad(preRoll, 0)))
        assertEquals(listOf(SessionStarted(movie, 0), AdBreakStarted(preRoll), AdStarted(ad(preRoll, 0))), first)
        // Sans position exacte de Media3, l'exitPlayhead est le dernier playhead observé dans l'asset quitté.
        val adExit = Playhead(0, isAd = true, isLive = false, unixTimeMs = 0)
        assertEquals(listOf(AdStarted(ad(preRoll, 1), adExit)), machine.onSnapshot(snapshot(ad = ad(preRoll, 1))))
        assertEquals(listOf(AdBreakEnded(preRoll, resumesContent = true, adExit)), machine.onSnapshot(snapshot()))
    }

    @Test
    fun `transitions carry the exact exit position given by Media3`() {
        val midRoll = TrackedAdBreak(groupIndex = 1, kind = AdBreakKind.MIDROLL, adCount = 1)
        val mid = TrackedAd(midRoll, 0, id = "mid", durationMs = 5_000)
        start()
        machine.onTick(snapshot(positionMs = 24_000))
        // Content -> pub : position du contenu au point de coupure (et non le dernier tick, 24 s).
        val toAd = machine.onSnapshot(snapshot(ad = mid), SnapshotHints(exitPositionMs = 25_300)).ofType<AdStarted>().single()
        assertEquals(Playhead(25_300, isAd = false, isLive = false, unixTimeMs = 0), toAd.exitPlayhead)
        // Pub -> contenu : position de fin de la pub.
        val back = machine.onSnapshot(snapshot(positionMs = 25_300), SnapshotHints(exitPositionMs = 5_000)).ofType<AdBreakEnded>().single()
        assertEquals(Playhead(5_000, isAd = true, isLive = false, unixTimeMs = 0), back.exitPlayhead)
    }

    @Test
    fun `a pre-roll at session start has no content exit playhead`() {
        val first = machine.onSnapshot(snapshot(ad = ad(preRoll, 0))).ofType<AdStarted>().single()
        assertEquals(null, first.exitPlayhead)
    }

    @Test
    fun `post-roll followed by the end does not resume content`() {
        start()
        machine.onSnapshot(snapshot(ad = ad(postRoll, 0)))
        val events = machine.onSnapshot(snapshot(status = PlaybackStatus.ENDED, ad = ad(postRoll, 0)))
        assertEquals(listOf(AdBreakEnded::class, Ended::class), events.map { it::class })
        assertEquals(false, events.ofType<AdBreakEnded>().single().resumesContent)
    }

    @Test
    fun `pausing during an ad reports the ad playhead`() {
        machine.onSnapshot(snapshot(ad = ad(preRoll, 0)))
        val pause = machine.onSnapshot(snapshot(playWhenReady = false, ad = ad(preRoll, 0), positionMs = 2_000)).ofType<Paused>().single()
        assertTrue(pause.playhead.isAd)
    }

    // --- Ticks ---------------------------------------------------------------------------------

    @Test
    fun `ticks only while a session is playing`() {
        assertEquals(emptyList<PlaybackEvent>(), machine.onTick(snapshot()))
        start()
        assertEquals(1, machine.onTick(snapshot(positionMs = 1_000)).ofType<Tick>().size)
        assertEquals(emptyList<PlaybackEvent>(), machine.onTick(snapshot(status = PlaybackStatus.BUFFERING)))
        machine.onSnapshot(snapshot(playWhenReady = false))
        assertEquals(emptyList<PlaybackEvent>(), machine.onTick(snapshot(playWhenReady = false)))
    }
}
