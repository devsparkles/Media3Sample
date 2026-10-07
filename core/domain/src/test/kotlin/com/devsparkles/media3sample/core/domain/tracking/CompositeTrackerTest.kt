package com.devsparkles.media3sample.core.domain.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeTrackerTest {

    private val content = TrackedContent("movie", "Film", 60_000, isLive = false)
    private val playhead = Playhead(1_000, isAd = false, isLive = false, unixTimeMs = 0)

    /** Tracker qui note les noms des méthodes appelées. */
    private class Recorder(override val name: String) : PlaybackTracker {
        val calls = mutableListOf<String>()
        override fun onSessionStart(content: TrackedContent, positionMs: Long) { calls += "start" }
        override fun onPause(reason: PauseReason, playhead: Playhead) { calls += "pause" }
        override fun onResume(playhead: Playhead) { calls += "resume" }
        override fun onSeek(fromMs: Long, toMs: Long) { calls += "seek" }
        override fun onAdBreakStart(adBreak: TrackedAdBreak) { calls += "adBreakStart" }
        override fun onAdStart(ad: TrackedAd) { calls += "adStart" }
        override fun onAdBreakEnd(adBreak: TrackedAdBreak, resumesContent: Boolean) { calls += "adBreakEnd" }
        override fun onSessionEnd(reason: SessionEndReason, finalPlayhead: Playhead?) { calls += "end" }
        override fun onPlayheadTick(playhead: Playhead) { calls += "tick" }
        override fun onAppBackground() { calls += "background" }
        override fun onAppForeground() { calls += "foreground" }
    }

    /** Tracker dont TOUTES les méthodes lèvent une exception (SDK tiers défaillant). */
    private class Crashing : PlaybackTracker {
        override val name = "Crashing"
        override fun onSessionStart(content: TrackedContent, positionMs: Long) = error("boom")
        override fun onPause(reason: PauseReason, playhead: Playhead) = error("boom")
        override fun onResume(playhead: Playhead) = error("boom")
        override fun onSeek(fromMs: Long, toMs: Long) = error("boom")
        override fun onAdBreakStart(adBreak: TrackedAdBreak) = error("boom")
        override fun onAdStart(ad: TrackedAd) = error("boom")
        override fun onAdBreakEnd(adBreak: TrackedAdBreak, resumesContent: Boolean) = error("boom")
        override fun onSessionEnd(reason: SessionEndReason, finalPlayhead: Playhead?) = error("boom")
        override fun onPlayheadTick(playhead: Playhead) = error("boom")
        override fun onAppBackground() = error("boom")
        override fun onAppForeground() = error("boom")
    }

    @Test
    fun `a crashing tracker breaks neither the caller nor the other trackers`() {
        val before = Recorder("before")
        val after = Recorder("after")
        val errors = mutableListOf<String>()
        val composite = CompositeTracker(listOf(before, Crashing(), after)) { tracker, e -> errors += "${tracker.name}:${e.message}" }

        composite.onSessionStart(content, 0)
        composite.onPlayheadTick(playhead)
        composite.onSessionEnd(SessionEndReason.COMPLETED, playhead)

        assertEquals(listOf("start", "tick", "end"), before.calls)
        assertEquals(listOf("start", "tick", "end"), after.calls)
        assertEquals(List(3) { "Crashing:boom" }, errors)
    }

    @Test
    fun `content change is delegated to each tracker's own implementation`() {
        val recorder = Recorder("r")
        CompositeTracker(listOf(recorder)).onContentChange(content, content.copy(id = "next"), 0)
        // Implémentation par défaut de l'interface : fin puis début.
        assertEquals(listOf("end", "start"), recorder.calls)
    }

    @Test
    fun `events are dispatched to the matching tracker method`() {
        val recorder = Recorder("r")
        listOf(
            PlaybackEvent.SessionStarted(content, 0),
            PlaybackEvent.Tick(playhead),
            PlaybackEvent.Paused(PauseReason.USER, playhead),
            PlaybackEvent.Resumed(playhead),
            PlaybackEvent.Seeked(0, 1),
            PlaybackEvent.Ended(SessionEndReason.RELEASED, playhead),
            PlaybackEvent.Released,
        ).forEach { it.dispatchTo(recorder) }
        assertEquals(listOf("start", "tick", "pause", "resume", "seek", "end"), recorder.calls)
    }
}
