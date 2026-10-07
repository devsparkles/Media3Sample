package com.devsparkles.media3sample.core.data.ads.macro

import com.devsparkles.media3sample.core.domain.model.TrackingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.random.Random

/**
 * Tests du "contexte" du pattern Strategy : repérage des macros, cohérence des valeurs,
 * encodage URL, macros inconnues.
 */
class MacroExpanderTest {

    private var now = 1_791_378_000_123L // 2026-10-07T13:00:00.123Z

    private val expander = MacroExpander(
        strategies = listOf(TimestampMacro(TimeZone.getTimeZone("UTC")), CacheBustingMacro, ErrorCodeMacro, AdPlayheadMacro, AssetUriMacro),
        clock = { now },
        random = Random(seed = 42),
    )

    @Test
    fun `replaces known macros with url-encoded values`() {
        val snapshot = expander.snapshot(TrackingContext(errorCode = 405))
        val url = expander.expand("https://t.test/err?code=[ERRORCODE]&t=[TIMESTAMP]", snapshot)
        // ":" est encodé en %3A : une valeur de macro ne doit jamais casser la structure de l'URL.
        assertEquals("https://t.test/err?code=405&t=2026-10-07T13%3A00%3A00.123Z", url)
    }

    /**
     * Test de non-régression du bug « chaque macro régénère son temps » : le même
     * instantané doit donner la même valeur partout, même si l'horloge avance entre-temps.
     */
    @Test
    fun `same snapshot gives identical timestamp and cachebuster across urls and occurrences`() {
        val snapshot = expander.snapshot(TrackingContext())
        val first = expander.expand("https://a.test/?t=[TIMESTAMP]&t2=[TIMESTAMP]&cb=[CACHEBUSTING]", snapshot)
        now += 5_000 // le temps passe pendant l'envoi des pixels
        val second = expander.expand("https://b.test/?t=[TIMESTAMP]&cb=[CACHEBUSTING]", snapshot)

        val timestamps = Regex("""t2?=([^&]+)""").findAll(first + "&" + second).map { it.groupValues[1] }.toSet()
        val cacheBusters = Regex("""cb=(\d+)""").findAll(first + "&" + second).map { it.groupValues[1] }.toSet()
        assertEquals(1, timestamps.size)
        assertEquals(1, cacheBusters.size)
    }

    @Test
    fun `a new event gets a new timestamp and cachebuster`() {
        val first = expander.expand("[TIMESTAMP]|[CACHEBUSTING]", expander.snapshot(TrackingContext()))
        now += 1_000
        val second = expander.expand("[TIMESTAMP]|[CACHEBUSTING]", expander.snapshot(TrackingContext()))
        assertNotEquals(first, second)
    }

    @Test
    fun `cachebuster always has 8 digits`() {
        repeat(1_000) {
            val value = expander.expand("[CACHEBUSTING]", expander.snapshot(TrackingContext()))
            assertTrue(value, value.matches(Regex("""\d{8}""")))
        }
    }

    @Test
    fun `known macro without value is replaced by -1`() {
        val url = expander.expand("https://t.test/?code=[ERRORCODE]&pos=[ADPLAYHEAD]", expander.snapshot(TrackingContext()))
        assertEquals("https://t.test/?code=-1&pos=-1", url)
    }

    @Test
    fun `unknown macros are left untouched`() {
        val url = expander.expand("https://t.test/?mt=[AD_MT]&x=%5BUACH%5D", expander.snapshot(TrackingContext()))
        assertEquals("https://t.test/?mt=[AD_MT]&x=%5BUACH%5D", url)
    }

    @Test
    fun `percent-encoded brackets are recognized`() {
        val url = expander.expand("https://t.test/?code=%5BERRORCODE%5D&c2=%5berrorcode%5d", expander.snapshot(TrackingContext(errorCode = 303)))
        assertEquals("https://t.test/?code=303&c2=303", url)
    }

    @Test
    fun `asset uri is fully encoded including spaces as percent20`() {
        val snapshot = expander.snapshot(TrackingContext(assetUri = "https://cdn.test/ad 720p.mp4?a=1&b=2"))
        val url = expander.expand("https://t.test/?asset=[ASSETURI]", snapshot)
        assertEquals("https://t.test/?asset=https%3A%2F%2Fcdn.test%2Fad%20720p.mp4%3Fa%3D1%26b%3D2", url)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `two strategies with the same name are rejected`() {
        MacroExpander(strategies = listOf(ErrorCodeMacro, ErrorCodeMacro))
    }
}
