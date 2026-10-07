package com.devsparkles.media3sample.core.data.ads

import com.devsparkles.media3sample.core.data.network.HttpClient
import com.devsparkles.media3sample.core.domain.model.TrackingContext
import com.devsparkles.media3sample.core.domain.repository.AdTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Conformité de la chaîne Wrapper avec VAST 4.1 :
 *  - §6.1 : les macros des URLs de REQUÊTE sont remplacées par le player ;
 *  - §2.3.6.3 : 300 (erreur générale), 301 (timeout), 100 (XML invalide).
 * `runTest` utilise un temps virtuel : le `delay(10_000)` du faux serveur s'exécute instantanément.
 */
class AdRepositoryWrapperErrorsTest {

    private class ScriptedHttpClient(private val routes: Map<String, suspend () -> String>) : HttpClient {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String): String {
            requested += url
            val key = url.substringBefore('?')
            return routes[key]?.invoke() ?: throw IOException("404 $url")
        }
        override suspend fun fire(url: String) = Unit
    }

    private class RecordingTracker : AdTracker {
        val errorCodes = mutableListOf<Int?>()
        override fun track(urls: List<String>, context: TrackingContext) {
            if (urls.isNotEmpty()) errorCodes += context.errorCode
        }
    }

    private fun wrapperTo(uri: String) = Fixtures.WRAPPER.replace("https://ads.test/inline.xml", uri)

    @Test
    fun `macros in a wrapper VASTAdTagURI are replaced before the request`() = runTest {
        val http = ScriptedHttpClient(
            mapOf(
                "https://ads.test/wrapper.xml" to { wrapperTo("https://ads.test/inline.xml?cb=[CACHEBUSTING]&api=[APIFRAMEWORKS]") },
                "https://ads.test/inline.xml" to { Fixtures.INLINE },
            ),
        )
        AdRepositoryImpl(http, RecordingTracker()).loadAdSchedule("https://ads.test/wrapper.xml")

        val inlineRequest = http.requested.last()
        assertFalse(inlineRequest, inlineRequest.contains("["))
        assertTrue(inlineRequest, inlineRequest.matches(Regex(""".*\?cb=\d{8}&api=-1""")))
    }

    @Test
    fun `wrapper timeout sends error 301`() = runTest {
        val http = ScriptedHttpClient(
            mapOf(
                "https://ads.test/wrapper.xml" to { Fixtures.WRAPPER },
                "https://ads.test/inline.xml" to { delay(10_000); Fixtures.INLINE },
            ),
        )
        val tracker = RecordingTracker()
        AdRepositoryImpl(http, tracker, wrapperTimeoutMs = 1_000).loadAdSchedule("https://ads.test/wrapper.xml")
        assertTrue(tracker.errorCodes.toString(), AdRepositoryImpl.VAST_ERROR_WRAPPER_TIMEOUT in tracker.errorCodes)
    }

    @Test
    fun `wrapper http failure sends general wrapper error 300`() = runTest {
        val http = ScriptedHttpClient(mapOf("https://ads.test/wrapper.xml" to { Fixtures.WRAPPER })) // inline -> 404
        val tracker = RecordingTracker()
        AdRepositoryImpl(http, tracker).loadAdSchedule("https://ads.test/wrapper.xml")
        assertTrue(tracker.errorCodes.toString(), AdRepositoryImpl.VAST_ERROR_WRAPPER_GENERAL in tracker.errorCodes)
    }

    @Test
    fun `wrapper pointing to invalid xml sends error 100`() = runTest {
        val http = ScriptedHttpClient(
            mapOf(
                "https://ads.test/wrapper.xml" to { Fixtures.WRAPPER },
                "https://ads.test/inline.xml" to { "<html>not vast</html>" },
            ),
        )
        val tracker = RecordingTracker()
        AdRepositoryImpl(http, tracker).loadAdSchedule("https://ads.test/wrapper.xml")
        assertEquals(AdRepositoryImpl.VAST_ERROR_XML_PARSING, tracker.errorCodes.first())
    }
}
