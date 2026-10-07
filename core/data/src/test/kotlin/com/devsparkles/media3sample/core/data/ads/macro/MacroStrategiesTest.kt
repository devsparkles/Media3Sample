package com.devsparkles.media3sample.core.data.ads.macro

import com.devsparkles.media3sample.core.domain.model.AdBreakPosition
import com.devsparkles.media3sample.core.domain.model.TrackingContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/**
 * Tests UNITAIRES de chaque stratégie, en isolation : c'est le bénéfice direct du pattern
 * Strategy. On construit un MacroContext à la main : pas d'horloge réelle, pas de réseau,
 * aléatoire contrôlé.
 */
class MacroStrategiesTest {

    /** 2026-10-07T13:00:00.123Z (UTC). */
    private val accessTimeMs = 1_791_378_000_123L

    private fun context(tracking: TrackingContext = TrackingContext()) =
        MacroContext(tracking = tracking, accessTimeMs = accessTimeMs, random = SequenceRandom())

    private fun known(raw: String) = MacroValue.Known(raw)

    // --- [TIMESTAMP] : §6.2, ISO 8601 avec .mmm avant le fuseau ----------------------------

    @Test
    fun `timestamp is ISO 8601 with milliseconds in UTC`() {
        assertEquals(known("2026-10-07T13:00:00.123Z"), TimestampMacro(TimeZone.getTimeZone("UTC")).resolve(context()))
    }

    @Test
    fun `timestamp puts the timezone offset after the milliseconds (Paris summer time)`() {
        assertEquals(known("2026-10-07T15:00:00.123+02:00"), TimestampMacro(TimeZone.getTimeZone("Europe/Paris")).resolve(context()))
    }

    @Test
    fun `timestamp uses the access time of the context, never the real clock`() {
        val macro = TimestampMacro(TimeZone.getTimeZone("UTC"))
        assertEquals(macro.resolve(context()), macro.resolve(context()))
    }

    // --- [CACHEBUSTING] : §6.2, « random 8-digit number » ----------------------------------

    @Test
    fun `cachebusting draws a new 8-digit number at each call`() {
        val ctx = context()
        assertEquals(known("11111111"), CacheBustingMacro.resolve(ctx))
        assertEquals(known("22222222"), CacheBustingMacro.resolve(ctx))
    }

    // --- [ERRORCODE] : §6.9 -----------------------------------------------------------------

    @Test
    fun `errorcode returns the vast error code`() {
        assertEquals(known("405"), ErrorCodeMacro.resolve(context(TrackingContext(errorCode = 405))))
    }

    @Test
    fun `errorcode is unknown when there is no error`() {
        assertEquals(MacroValue.Unknown, ErrorCodeMacro.resolve(context()))
    }

    // --- [ADPLAYHEAD] / [MEDIAPLAYHEAD] / [CONTENTPLAYHEAD] : timecode HH:MM:SS.mmm --------

    @Test
    fun `adplayhead is formatted as a timecode`() {
        assertEquals(known("00:00:11.355"), AdPlayheadMacro.resolve(context(TrackingContext(adPlayheadMs = 11_355))))
        assertEquals(known("01:02:03.004"), AdPlayheadMacro.resolve(context(TrackingContext(adPlayheadMs = 3_723_004))))
    }

    @Test
    fun `adplayhead is unknown outside of an ad`() {
        assertEquals(MacroValue.Unknown, AdPlayheadMacro.resolve(context()))
    }

    @Test
    fun `mediaplayhead and deprecated contentplayhead read the content position`() {
        val ctx = context(TrackingContext(contentPlayheadMs = 321_123))
        assertEquals(known("00:05:21.123"), MediaPlayheadMacro().resolve(ctx))
        assertEquals(known("00:05:21.123"), MediaPlayheadMacro(name = "CONTENTPLAYHEAD").resolve(ctx))
    }

    // --- [BREAKPOSITION] : §6.3, 1 pre / 2 mid / 3 post --------------------------------------

    @Test
    fun `breakposition follows the spec codes`() {
        fun resolve(position: AdBreakPosition?) = BreakPositionMacro.resolve(context(TrackingContext(breakPosition = position)))
        assertEquals(known("1"), resolve(AdBreakPosition.PreRoll))
        assertEquals(known("2"), resolve(AdBreakPosition.MidRoll(15_000)))
        assertEquals(known("3"), resolve(AdBreakPosition.PostRoll))
        assertEquals(MacroValue.Unknown, resolve(null))
    }

    // --- [ASSETURI] -------------------------------------------------------------------------

    @Test
    fun `asseturi returns the raw media url (encoding is the expander's job)`() {
        val url = "https://cdn.test/ad 720p.mp4?a=1&b=2"
        assertEquals(known(url), AssetUriMacro.resolve(context(TrackingContext(assetUri = url))))
    }
}
