package com.devsparkles.media3sample.core.data.ads.macro

import com.devsparkles.media3sample.core.domain.model.TrackingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.TimeZone

/**
 * Tests du "contexte" du pattern Strategy, règle par règle de la spec VAST 4.1 §6.1.
 */
class MacroExpanderTest {

    private var now = 1_791_378_000_123L // 2026-10-07T13:00:00.123Z

    private fun expander(vararg strategies: MacroStrategy = arrayOf(TimestampMacro(TimeZone.getTimeZone("UTC")), CacheBustingMacro, ErrorCodeMacro, AdPlayheadMacro, AssetUriMacro)) =
        MacroExpander(strategies = strategies.toList(), clock = { now }, random = SequenceRandom())

    // --- Encodage : « apply encodeURIComponent to any value » -------------------------------

    @Test
    fun `values are always percent-encoded, including the timestamp`() {
        val url = expander().expand("https://t.test/err?code=[ERRORCODE]&t=[TIMESTAMP]", TrackingContext(errorCode = 405))
        // Exemple de la spec : 2016-01-17T8%3A15%3A07.127-05 -> les ":" sont encodés.
        assertEquals("https://t.test/err?code=405&t=2026-10-07T13%3A00%3A00.123Z", url)
    }

    @Test
    fun `plus sign of a positive offset is encoded`() {
        val url = MacroExpander(listOf(TimestampMacro(TimeZone.getTimeZone("Europe/Paris"))), clock = { now }).expand("t=[TIMESTAMP]")
        assertEquals("t=2026-10-07T15%3A00%3A00.123%2B02%3A00", url)
    }

    @Test
    fun `encoding matches javascript encodeURIComponent`() {
        // encodeURIComponent("a b!'()*~/?&=") === "a%20b!'()*~%2F%3F%26%3D"
        assertEquals("a%20b!'()*~%2F%3F%26%3D", MacroExpander.encodeUriComponent("a b!'()*~/?&="))
    }

    @Test
    fun `asset uri is fully encoded`() {
        val url = expander().expand("https://t.test/?asset=[ASSETURI]", TrackingContext(assetUri = "https://cdn.test/ad 720p.mp4?a=1&b=2"))
        assertEquals("https://t.test/?asset=https%3A%2F%2Fcdn.test%2Fad%20720p.mp4%3Fa%3D1%26b%3D2", url)
    }

    // --- [TIMESTAMP] : « the date and time at which the URI […] is accessed » ---------------

    @Test
    fun `each url gets its own access time`() {
        val expander = expander()
        val first = expander.expand("t=[TIMESTAMP]")
        now += 5_000 // la deuxième URL est appelée 5 s plus tard
        val second = expander.expand("t=[TIMESTAMP]")
        assertEquals("t=2026-10-07T13%3A00%3A00.123Z", first)
        assertEquals("t=2026-10-07T13%3A00%3A05.123Z", second)
    }

    @Test
    fun `occurrences of timestamp inside one url share the same access time`() {
        val url = expander().expand("a=[TIMESTAMP]&b=[TIMESTAMP]")
        assertEquals("a=2026-10-07T13%3A00%3A00.123Z&b=2026-10-07T13%3A00%3A00.123Z", url)
    }

    // --- [CACHEBUSTING] : une valeur par occurrence ------------------------------------------

    @Test
    fun `each cachebusting occurrence gets a different value`() {
        assertEquals("cb=11111111&cb2=22222222", expander().expand("cb=[CACHEBUSTING]&cb2=[CACHEBUSTING]"))
    }

    @Test
    fun `two identical tracker urls (same tracker on two wrapper levels) produce two distinct requests`() {
        val expander = expander()
        val tracker = "https://same.test/imp?cb=[CACHEBUSTING]"
        assertNotEquals(expander.expand(tracker), expander.expand(tracker))
    }

    // --- -1 / -2 et macros inconnues : §6.1 « Marking Macro Values as Unknown » ---------------

    @Test
    fun `implemented macro without value is replaced by -1`() {
        assertEquals("code=-1&pos=-1", expander().expand("code=[ERRORCODE]&pos=[ADPLAYHEAD]"))
    }

    @Test
    fun `restricted value is replaced by -2`() {
        val consentRestricted = object : MacroStrategy {
            override val name = "IFA"
            override fun resolve(context: MacroContext) = MacroValue.Restricted
        }
        assertEquals("ifa=-2", expander(consentRestricted).expand("ifa=[IFA]"))
    }

    @Test
    fun `spec macro not implemented here is replaced by -1`() {
        // [APIFRAMEWORKS], [TRANSACTIONID] sont dans la spec mais pas implémentés -> -1.
        assertEquals("api=-1&tid=-1", expander().expand("api=[APIFRAMEWORKS]&tid=[TRANSACTIONID]"))
    }

    @Test
    fun `non-spec macros are left untouched`() {
        // « do not replace all unknown macros with -1 » : [AD_MT] (Google) n'est pas dans la spec.
        assertEquals("mt=[AD_MT]&x=%5BUACH%5D", expander().expand("mt=[AD_MT]&x=%5BUACH%5D"))
    }

    // --- Robustesse ---------------------------------------------------------------------

    @Test
    fun `percent-encoded brackets are recognized`() {
        assertEquals("c=303&c2=303", expander().expand("c=%5BERRORCODE%5D&c2=%5berrorcode%5d", TrackingContext(errorCode = 303)))
    }

    @Test
    fun `a substituted value is never re-scanned for macros`() {
        val url = expander().expand("a=[ASSETURI]", TrackingContext(assetUri = "x[TIMESTAMP]"))
        assertEquals("a=x%5BTIMESTAMP%5D", url) // reste littéral (et encodé), pas remplacé
    }

    @Test(expected = IllegalArgumentException::class)
    fun `two strategies with the same name are rejected`() {
        MacroExpander(strategies = listOf(ErrorCodeMacro, ErrorCodeMacro))
    }
}
