package com.devsparkles.media3sample.core.data.ads

import com.devsparkles.media3sample.core.data.network.HttpClient
import com.devsparkles.media3sample.core.domain.model.AdBreakPosition
import com.devsparkles.media3sample.core.domain.model.AdSchedule
import com.devsparkles.media3sample.core.domain.model.AdTrackingEvent
import com.devsparkles.media3sample.core.domain.model.TrackingContext
import com.devsparkles.media3sample.core.domain.repository.AdTracker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Test d'intégration de la chaîne VMAP -> VAST Wrapper -> VAST InLine, sans réseau,
 * grâce à un FakeHttpClient (c'est tout l'intérêt de l'interface HttpClient).
 * `runTest` (kotlinx-coroutines-test) exécute les suspend fun avec un temps virtuel.
 */
class AdRepositoryImplTest {

    private class FakeHttpClient(private val responses: Map<String, String>) : HttpClient {
        override suspend fun get(url: String) = responses[url] ?: throw IOException("404 $url")
        override suspend fun fire(url: String) = Unit
    }

    private class RecordingTracker : AdTracker {
        val calls = mutableListOf<Pair<List<String>, Int?>>()
        override fun track(urls: List<String>, context: TrackingContext) {
            calls += urls to context.errorCode
        }
    }

    private val http = FakeHttpClient(
        mapOf(
            "https://ads.test/vmap.xml" to Fixtures.VMAP,
            "https://ads.test/wrapper.xml" to Fixtures.WRAPPER,
            "https://ads.test/inline.xml" to Fixtures.INLINE,
            "https://ads.test/empty.xml" to Fixtures.EMPTY_VAST,
        ),
    )

    @Test
    fun `resolves vmap into pre-roll and mid-roll, drops empty post-roll`() = runTest {
        val tracker = RecordingTracker()
        val schedule = AdRepositoryImpl(http, tracker).loadAdSchedule("https://ads.test/vmap.xml")

        assertEquals(
            listOf(AdBreakPosition.PreRoll, AdBreakPosition.MidRoll(15_000)),
            schedule.breaks.map { it.position },
        )
        // Le post-roll "no fill" a déclenché le pixel d'erreur 303.
        assertTrue(tracker.calls.any { it.second == AdRepositoryImpl.VAST_ERROR_NO_ADS })
    }

    @Test
    fun `wrapper tracking is merged with inline tracking`() = runTest {
        val preRoll = AdRepositoryImpl(http, RecordingTracker())
            .loadAdSchedule("https://ads.test/vmap.xml").breaks.first()
        val ad = preRoll.ads.single()

        assertEquals(
            listOf("https://track.test/wrapper/impression", "https://track.test/inline/impression"),
            ad.impressionUrls,
        )
        assertEquals(2, ad.trackingEvents[AdTrackingEvent.COMPLETE]?.size)
        assertEquals("https://cdn.test/720.mp4", ad.mediaFile.url) // bitrate <= 2000 kbps
        assertEquals(5_000L, ad.skipOffsetMs)
    }

    @Test
    fun `network failure on vmap never throws and returns empty schedule`() = runTest {
        val schedule = AdRepositoryImpl(FakeHttpClient(emptyMap()), RecordingTracker())
            .loadAdSchedule("https://ads.test/down.xml")
        assertEquals(AdSchedule.EMPTY, schedule)
    }

    @Test
    fun `plain vast response is treated as a pre-roll`() = runTest {
        val schedule = AdRepositoryImpl(http, RecordingTracker()).loadAdSchedule("https://ads.test/inline.xml")
        assertEquals(AdBreakPosition.PreRoll, schedule.breaks.single().position)
    }
}
