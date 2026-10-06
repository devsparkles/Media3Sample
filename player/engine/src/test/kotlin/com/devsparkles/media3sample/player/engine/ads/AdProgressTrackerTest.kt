package com.devsparkles.media3sample.player.engine.ads

import com.devsparkles.media3sample.core.domain.model.AdTrackingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdProgressTrackerTest {

    @Test
    fun `fires each quartile exactly once in order`() {
        val tracker = AdProgressTracker()

        assertEquals(listOf(AdTrackingEvent.START), tracker.onProgress(0, 20_000))
        assertEquals(emptyList<AdTrackingEvent>(), tracker.onProgress(1_000, 20_000))
        assertEquals(listOf(AdTrackingEvent.FIRST_QUARTILE), tracker.onProgress(5_000, 20_000))
        assertEquals(emptyList<AdTrackingEvent>(), tracker.onProgress(5_200, 20_000))
    }

    @Test
    fun `a jump fires every crossed threshold`() {
        val tracker = AdProgressTracker()
        assertEquals(
            listOf(AdTrackingEvent.START, AdTrackingEvent.FIRST_QUARTILE, AdTrackingEvent.MIDPOINT, AdTrackingEvent.THIRD_QUARTILE),
            tracker.onProgress(19_000, 20_000),
        )
    }

    @Test
    fun `complete is reported only once`() {
        val tracker = AdProgressTracker()
        assertTrue(tracker.markComplete())
        assertFalse(tracker.markComplete())
    }
}
