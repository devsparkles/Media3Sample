package com.devsparkles.media3sample.core.data.tracking.inhouse

import com.devsparkles.media3sample.core.domain.tracking.PauseReason
import com.devsparkles.media3sample.core.domain.tracking.Playhead
import com.devsparkles.media3sample.core.domain.tracking.TrackedContent
import org.junit.Assert.assertEquals
import org.junit.Test

class InHouseTrackerTest {

    private val beacons = mutableListOf<InHouseBeacon>()
    private var nextId = 0
    private val tracker = InHouseTracker(send = { beacons += it }, heartbeatEveryTicks = 10, newSessionId = { "s${++nextId}" })
    private val movie = TrackedContent("movie", "Film", 60_000, isLive = false)
    private fun playhead(ms: Long) = Playhead(ms, isAd = false, isLive = false, unixTimeMs = 0)

    @Test
    fun `heartbeat every ten ticks, not every second`() {
        tracker.onSessionStart(movie, 0)
        (1..25).forEach { tracker.onPlayheadTick(playhead(it * 1_000L)) }
        assertEquals(listOf(10_000L, 20_000L), beacons.filter { it.type == "heartbeat" }.map { it.fields["positionMs"] })
    }

    @Test
    fun `the pause cause is kept, unlike Nielsen`() {
        tracker.onSessionStart(movie, 0)
        tracker.onPause(PauseReason.INTERRUPTION, playhead(3_000))
        assertEquals("INTERRUPTION", beacons.last().fields["reason"])
    }

    @Test
    fun `each session gets its own id`() {
        tracker.onSessionStart(movie, 0)
        tracker.onContentChange(movie, movie.copy(id = "next"), 0)
        assertEquals(listOf("s1", "s1", "s2"), beacons.map { it.sessionId })
    }
}
