package com.devsparkles.media3sample.core.data.ads

import com.devsparkles.media3sample.core.data.ads.macro.MacroExpander
import com.devsparkles.media3sample.core.data.ads.macro.TimestampMacro
import com.devsparkles.media3sample.core.data.network.HttpClient
import com.devsparkles.media3sample.core.domain.model.TrackingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() est marqué expérimental
class HttpAdTrackerTest {

    private class RecordingHttpClient(private val failWith: Exception? = null) : HttpClient {
        val fired = mutableListOf<String>()
        override suspend fun get(url: String) = error("not used")
        override suspend fun fire(url: String) {
            fired += url
            failWith?.let { throw it }
        }
    }

    /**
     * Spec §6.2 : [TIMESTAMP] = heure d'ACCÈS à l'URI. Si l'envoi est retardé, c'est l'heure
     * de l'envoi qui part, pas celle de l'appel à track().
     * runTest + StandardTestDispatcher : la coroutine ne s'exécute qu'à advanceUntilIdle().
     */
    @Test
    fun `timestamp is the time the pixel is actually requested`() = runTest {
        var now = 1_791_378_000_000L // 2026-10-07T13:00:00.000Z
        val http = RecordingHttpClient()
        val expander = MacroExpander(listOf(TimestampMacro(TimeZone.getTimeZone("UTC"))), clock = { now })
        HttpAdTracker(http, scope = this, macroExpander = expander)
            .track(listOf("https://a.test/?t=[TIMESTAMP]"), TrackingContext())

        now += 60_000 // envoi effectif une minute plus tard
        advanceUntilIdle()

        assertEquals(listOf("https://a.test/?t=2026-10-07T13%3A01%3A00.000Z"), http.fired)
    }

    @Test
    fun `error code from the context reaches the url`() = runTest {
        val http = RecordingHttpClient()
        HttpAdTracker(http, scope = this).track(listOf("https://e.test/?c=[ERRORCODE]"), TrackingContext(errorCode = 405))
        advanceUntilIdle()
        assertEquals(listOf("https://e.test/?c=405"), http.fired)
    }

    @Test
    fun `a failing pixel does not stop the next ones and is logged`() = runTest {
        val logs = mutableListOf<String>()
        val http = RecordingHttpClient(failWith = IOException("down"))
        HttpAdTracker(http, scope = this, logger = { logs += it }).track(listOf("https://a.test", "https://b.test"))
        advanceUntilIdle()
        assertEquals(2, http.fired.size)
        assertTrue(logs.all { it.startsWith("tracking FAILED") })
    }

    @Test
    fun `cancellation is not logged as a tracking failure`() = runTest {
        val logs = mutableListOf<String>()
        val http = RecordingHttpClient(failWith = CancellationException("screen closed"))
        HttpAdTracker(http, scope = this, logger = { logs += it }).track(listOf("https://a.test"))
        advanceUntilIdle()
        assertTrue(logs.isEmpty())
    }
}
