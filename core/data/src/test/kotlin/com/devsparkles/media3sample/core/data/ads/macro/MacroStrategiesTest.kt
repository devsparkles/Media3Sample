package com.devsparkles.media3sample.core.data.ads.macro

import com.devsparkles.media3sample.core.domain.model.TrackingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/**
 * Tests UNITAIRES de chaque stratégie, en isolation : c'est le bénéfice direct du pattern
 * Strategy. Chaque stratégie est une fonction pure de MacroContext, donc il suffit de
 * construire un contexte à la main, sans horloge, sans réseau, sans aléatoire.
 */
class MacroStrategiesTest {

    /** 2026-10-07T13:00:00.123Z (UTC). */
    private val fixedTimeMs = 1_791_378_000_123L

    private fun context(tracking: TrackingContext = TrackingContext()) =
        MacroContext(timestampMs = fixedTimeMs, cacheBuster = "12345678", tracking = tracking)

    // --- [TIMESTAMP] -----------------------------------------------------------------------

    @Test
    fun `timestamp is ISO 8601 with milliseconds in UTC`() {
        val macro = TimestampMacro(TimeZone.getTimeZone("UTC"))
        assertEquals("2026-10-07T13:00:00.123Z", macro.resolve(context()))
    }

    @Test
    fun `timestamp includes the timezone offset (Paris summer time)`() {
        val macro = TimestampMacro(TimeZone.getTimeZone("Europe/Paris"))
        assertEquals("2026-10-07T15:00:00.123+02:00", macro.resolve(context()))
    }

    @Test
    fun `timestamp only depends on the context, never on the current clock`() {
        val macro = TimestampMacro(TimeZone.getTimeZone("UTC"))
        // Deux appels sur le même instantané = même valeur, même si du temps s'écoule entre les deux.
        assertEquals(macro.resolve(context()), macro.resolve(context()))
    }

    // --- [CACHEBUSTING] --------------------------------------------------------------------

    @Test
    fun `cachebusting returns the snapshot value`() {
        assertEquals("12345678", CacheBustingMacro.resolve(context()))
    }

    // --- [ERRORCODE] -----------------------------------------------------------------------

    @Test
    fun `errorcode returns the vast error code`() {
        assertEquals("405", ErrorCodeMacro.resolve(context(TrackingContext(errorCode = 405))))
    }

    @Test
    fun `errorcode is unknown when there is no error`() {
        assertNull(ErrorCodeMacro.resolve(context()))
    }

    // --- [ADPLAYHEAD] ----------------------------------------------------------------------

    @Test
    fun `adplayhead is formatted as HH MM SS mmm`() {
        assertEquals("00:00:07.500", AdPlayheadMacro.resolve(context(TrackingContext(adPlayheadMs = 7_500))))
        assertEquals("01:02:03.004", AdPlayheadMacro.resolve(context(TrackingContext(adPlayheadMs = 3_723_004))))
    }

    @Test
    fun `adplayhead is unknown outside of an ad`() {
        assertNull(AdPlayheadMacro.resolve(context()))
    }

    // --- [ASSETURI] ------------------------------------------------------------------------

    @Test
    fun `asseturi returns the raw media url (encoding is the expander's job)`() {
        val url = "https://cdn.test/ad 720p.mp4?a=1&b=2"
        assertEquals(url, AssetUriMacro.resolve(context(TrackingContext(assetUri = url))))
    }
}
