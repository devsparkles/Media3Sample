package com.devsparkles.media3sample.core.data.ads

import com.devsparkles.media3sample.core.data.ads.macro.MacroExpander
import com.devsparkles.media3sample.core.data.ads.macro.TimestampMacro
import com.devsparkles.media3sample.core.data.network.HttpClient
import com.devsparkles.media3sample.core.domain.model.TrackingContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() est marqué expérimental
class HttpAdTrackerTest {

    private class RecordingHttpClient : HttpClient {
        val fired = mutableListOf<String>()
        override suspend fun get(url: String) = error("not used")
        override suspend fun fire(url: String) {
            fired += url
        }
    }

    /**
     * Le timestamp doit être celui de l'ÉVÉNEMENT (appel à track), pas celui de l'envoi
     * effectif, qui a lieu plus tard dans une coroutine.
     * runTest + StandardTestDispatcher : la coroutine lancée ne s'exécute qu'à advanceUntilIdle(),
     * ce qui permet de simuler un envoi retardé.
     */
    @Test
    fun `timestamp is captured when the event happens, not when the pixel is sent`() = runTest {
        var now = 1_791_378_000_000L // 2026-10-07T13:00:00.000Z
        val http = RecordingHttpClient()
        val expander = MacroExpander(
            strategies = listOf(TimestampMacro(TimeZone.getTimeZone("UTC"))),
            clock = { now },
        )
        val tracker = HttpAdTracker(http, scope = this, macroExpander = expander)

        tracker.track(listOf("https://a.test/?t=[TIMESTAMP]", "https://b.test/?t=[TIMESTAMP]"), TrackingContext())
        now += 60_000 // l'envoi a lieu une minute plus tard (réseau lent, pool saturé...)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "https://a.test/?t=2026-10-07T13%3A00%3A00.000Z",
                "https://b.test/?t=2026-10-07T13%3A00%3A00.000Z",
            ),
            http.fired,
        )
    }

    @Test
    fun `error code from the context reaches the url`() = runTest {
        val http = RecordingHttpClient()
        HttpAdTracker(http, scope = this).track(listOf("https://e.test/?c=[ERRORCODE]"), TrackingContext(errorCode = 405))
        advanceUntilIdle()
        assertEquals(listOf("https://e.test/?c=405"), http.fired)
    }
}
