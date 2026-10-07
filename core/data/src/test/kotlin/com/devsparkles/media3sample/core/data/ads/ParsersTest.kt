package com.devsparkles.media3sample.core.data.ads

import com.devsparkles.media3sample.core.data.ads.parser.TimeParser
import com.devsparkles.media3sample.core.data.ads.parser.VastParser
import com.devsparkles.media3sample.core.data.ads.parser.VmapParser
import com.devsparkles.media3sample.core.domain.model.AdBreakEvent
import com.devsparkles.media3sample.core.domain.model.AdBreakPosition
import com.devsparkles.media3sample.core.domain.model.AdTrackingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    @Test
    fun `vmap parses three breaks with ad tag uri, inline vast and tracking`() {
        val doc = VmapParser().parse(Fixtures.VMAP)

        assertEquals(listOf("start", "00:00:15.000", "end"), doc.breaks.map { it.timeOffset })
        assertEquals("https://ads.test/wrapper.xml", doc.breaks[0].adTagUri)
        assertEquals(listOf("https://track.test/breakStart"), doc.breaks[0].trackingEvents[AdBreakEvent.BREAK_START])
        assertNotNull(doc.breaks[1].inlineVast)
    }

    @Test
    fun `vast inline exposes duration, skip offset, media files and tracking`() {
        val ad = VastParser().parse(Fixtures.INLINE).ads.single() as VastParser.VastAd.InLine

        assertEquals(20_500L, ad.durationMs)
        assertEquals("00:00:05", ad.skipOffset)
        assertEquals(3, ad.mediaFiles.size)
        assertEquals(listOf("https://track.test/inline/q1"), ad.tracking.events[AdTrackingEvent.FIRST_QUARTILE])
    }

    @Test
    fun `vast wrapper exposes redirect uri`() {
        val ad = VastParser().parse(Fixtures.WRAPPER).ads.single()
        assertTrue(ad is VastParser.VastAd.Wrapper)
        assertEquals("https://ads.test/inline.xml", (ad as VastParser.VastAd.Wrapper).vastAdTagUri)
    }

    @Test
    fun `time parser handles vmap offsets and percentages`() {
        assertEquals(AdBreakPosition.PreRoll, TimeParser.parseBreakPosition("start"))
        assertEquals(AdBreakPosition.PostRoll, TimeParser.parseBreakPosition("end"))
        assertEquals(AdBreakPosition.MidRoll(3_723_400), TimeParser.parseBreakPosition("01:02:03.4"))
        assertEquals(5_000L, TimeParser.parseOffsetMs("25%", 20_000))
    }
}
