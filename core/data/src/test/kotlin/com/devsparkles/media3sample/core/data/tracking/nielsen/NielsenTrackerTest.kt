package com.devsparkles.media3sample.core.data.tracking.nielsen

import com.devsparkles.media3sample.core.domain.tracking.AdBreakKind
import com.devsparkles.media3sample.core.domain.tracking.PauseReason
import com.devsparkles.media3sample.core.domain.tracking.Playhead
import com.devsparkles.media3sample.core.domain.tracking.SessionEndReason
import com.devsparkles.media3sample.core.domain.tracking.TrackedAd
import com.devsparkles.media3sample.core.domain.tracking.TrackedAdBreak
import com.devsparkles.media3sample.core.domain.tracking.TrackedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Règles PROPRES à Nielsen (en plus de la suite de contrat). Chaque test cite la règle.
 */
class NielsenTrackerTest {

    private val sdk = RecordingNielsenSdk()
    private val tracker = NielsenTracker(sdk)

    private val movie = TrackedContent("movie", "Film", 60_000, isLive = false)
    private val episode = TrackedContent("episode", "Épisode", 30_000, isLive = false)
    private val preRoll = TrackedAdBreak(0, AdBreakKind.PREROLL, adCount = 2)
    private fun ad(index: Int) = TrackedAd(preRoll, index, id = "ad-$index", durationMs = 5_000)
    private fun playhead(ms: Long, isAd: Boolean = false) = Playhead(ms, isAd, isLive = false, unixTimeMs = 0)

    private fun callsAfter(block: () -> Unit): List<String> {
        val from = sdk.calls.size
        block()
        return sdk.calls.drop(from)
    }

    @Test
    fun `start = play then loadMetadata content`() {
        tracker.onSessionStart(movie, 0)
        assertEquals(listOf("play", "loadMetadata(content)"), sdk.calls)
        assertEquals(mapOf("type" to "content", "assetid" to "movie", "program" to "Film", "title" to "Film", "length" to "60"), sdk.metadata.single())
    }

    @Test
    fun `playhead every tick in seconds, withheld while paused`() {
        tracker.onSessionStart(movie, 0)
        assertEquals(listOf("setPlayheadPosition(1)"), callsAfter { tracker.onPlayheadTick(playhead(1_000)) })
        tracker.onPause(PauseReason.USER, playhead(1_000))
        assertEquals(emptyList<String>(), callsAfter { tracker.onPlayheadTick(playhead(1_000)) })
    }

    @Test
    fun `pause = final playhead then stop, resume = play then loadMetadata`() {
        tracker.onSessionStart(movie, 0)
        tracker.onPlayheadTick(playhead(4_000))
        assertEquals(listOf("setPlayheadPosition(5)", "stop"), callsAfter { tracker.onPause(PauseReason.USER, playhead(5_200)) })
        assertEquals(listOf("play", "loadMetadata(content)"), callsAfter { tracker.onResume(playhead(5_200)) })
    }

    @Test
    fun `final playhead is not resent when already up to date`() {
        tracker.onSessionStart(movie, 0)
        tracker.onPlayheadTick(playhead(5_000))
        assertEquals(listOf("stop"), callsAfter { tracker.onPause(PauseReason.USER, playhead(5_400)) })
    }

    @Test
    fun `every interruption cause is a stop for Nielsen`() {
        PauseReason.entries.forEach { reason ->
            val sdk = RecordingNielsenSdk()
            NielsenTracker(sdk).apply {
                onSessionStart(movie, 0)
                onPause(reason, playhead(0))
            }
            assertEquals("$reason", "stop", sdk.calls.last())
        }
    }

    @Test
    fun `stop is never called on an idle SDK`() {
        tracker.onSessionStart(movie, 0)
        tracker.onPause(PauseReason.USER, playhead(1_000))
        assertEquals(emptyList<String>(), callsAfter { tracker.onPause(PauseReason.INTERRUPTION, playhead(1_000)) })
    }

    @Test
    fun `pre-roll with two ads then back to content`() {
        tracker.onSessionStart(movie, 0)
        tracker.onAdBreakStart(preRoll)
        assertEquals(listOf("stop", "loadMetadata(preroll)"), callsAfter { tracker.onAdStart(ad(0)) })
        tracker.onPlayheadTick(playhead(1_000, isAd = true))
        // « stop() : call [...] at the end of each Ad »
        assertEquals(listOf("stop", "loadMetadata(preroll)"), callsAfter { tracker.onAdStart(ad(1)) })
        // « Once adbreak is complete, call stop and loadMetadata(content) »
        assertEquals(listOf("stop", "loadMetadata(content)"), callsAfter { tracker.onAdBreakEnd(preRoll, resumesContent = true) })
    }

    @Test
    fun `ad and content metadata are never mixed`() {
        tracker.onSessionStart(movie, 0)
        tracker.onAdStart(ad(0))
        val (content, adMetadata) = sdk.metadata
        assertEquals("content", content["type"])
        assertEquals(mapOf("type" to "preroll", "assetid" to "ad-0", "title" to "ad-0"), adMetadata)
        assertFalse(adMetadata.containsKey("program"))
    }

    @Test
    fun `post-roll then end calls end without reloading content metadata`() {
        val postRoll = TrackedAdBreak(1, AdBreakKind.POSTROLL, adCount = 1)
        tracker.onSessionStart(movie, 0)
        tracker.onAdStart(TrackedAd(postRoll, 0, "post", 5_000))
        val calls = callsAfter {
            tracker.onAdBreakEnd(postRoll, resumesContent = false)
            tracker.onSessionEnd(SessionEndReason.COMPLETED, playhead(5_000, isAd = true))
        }
        assertEquals(listOf("setPlayheadPosition(5)", "end"), calls)
    }

    @Test
    fun `live playhead is the UTC time in seconds`() {
        tracker.onSessionStart(movie.copy(isLive = true), 0)
        assertEquals(listOf("setPlayheadPosition(1791378000)"), callsAfter { tracker.onPlayheadTick(Playhead(42_000, false, true, 1_791_378_000_999)) })
    }

    // --- Le flush : unique endroit qui ferme une session restée ouverte -------------------------

    @Test
    fun `content change ends the previous content through the flush`() {
        tracker.onSessionStart(movie, 0)
        assertEquals(listOf("end", "play", "loadMetadata(content)"), callsAfter { tracker.onContentChange(movie, episode, 0) })
        assertEquals("episode", sdk.metadata.last()["assetid"])
    }

    @Test
    fun `a new session on a different content while processing flushes with end`() {
        tracker.onSessionStart(movie, 0)
        assertEquals(listOf("end", "play", "loadMetadata(content)"), callsAfter { tracker.onSessionStart(episode, 0) })
    }

    @Test
    fun `a new session on the same content while processing flushes with stop`() {
        tracker.onSessionStart(movie, 0)
        assertEquals(listOf("stop", "play", "loadMetadata(content)"), callsAfter { tracker.onSessionStart(movie, 0) })
    }

    @Test
    fun `a session already ended is not flushed again`() {
        tracker.onSessionStart(movie, 0)
        tracker.onSessionEnd(SessionEndReason.COMPLETED, null)
        assertEquals(listOf("play", "loadMetadata(content)"), callsAfter { tracker.onSessionStart(movie, 0) })
    }

    @Test
    fun `releasing while paused still ends the session`() {
        tracker.onSessionStart(movie, 0)
        tracker.onPause(PauseReason.USER, playhead(1_000))
        assertEquals(listOf("end"), callsAfter { tracker.onSessionEnd(SessionEndReason.RELEASED, playhead(1_000)) })
    }

    @Test
    fun `app close ends the session, closes the SDK, then ignores everything`() {
        tracker.onSessionStart(movie, 0)
        assertEquals(listOf("end", "close"), callsAfter { tracker.onAppClose() })
        assertEquals(emptyList<String>(), callsAfter {
            tracker.onSessionStart(movie, 0)
            tracker.onAppBackground()
            tracker.onAppClose()
        })
    }
}
