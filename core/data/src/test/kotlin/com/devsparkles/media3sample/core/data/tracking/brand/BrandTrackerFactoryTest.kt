package com.devsparkles.media3sample.core.data.tracking.brand

import com.devsparkles.media3sample.core.data.tracking.nielsen.NielsenSdkGateway
import com.devsparkles.media3sample.core.data.tracking.nielsen.RecordingNielsenSdk
import com.devsparkles.media3sample.core.domain.tracking.CompositeTracker
import com.devsparkles.media3sample.core.domain.tracking.TrackedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrandTrackerFactoryTest {

    private var nielsenInstances = 0
    private val nielsenSdk = RecordingNielsenSdk()
    private val beacons = mutableListOf<String>()
    private val factory = BrandTrackerFactory(
        nielsenGateway = { nielsenInstances++; nielsenSdk },
        inHouseSend = { beacons += it.type },
    )

    private val movie = TrackedContent("movie", "Film", 60_000, isLive = false)

    @Test
    fun `each brand gets its own set of reporters`() {
        val measuredByNielsen = BrandTrackingConfig("brand-a", listOf(TrackerKind.NIELSEN, TrackerKind.IN_HOUSE))
        val inHouseOnly = BrandTrackingConfig("brand-b", listOf(TrackerKind.IN_HOUSE))

        assertEquals(listOf("Nielsen", "InHouse"), factory.create(measuredByNielsen).map { it.name })
        assertEquals(listOf("InHouse"), factory.create(inHouseOnly).map { it.name })
    }

    @Test
    fun `the Nielsen SDK is only created for brands that use it`() {
        factory.create(BrandTrackingConfig("brand-b", listOf(TrackerKind.IN_HOUSE)))
        assertEquals(0, nielsenInstances)
    }

    @Test
    fun `the same playback reaches every reporter of the brand`() {
        val composite = CompositeTracker(factory.create(BrandTrackingConfig("brand-a", listOf(TrackerKind.NIELSEN, TrackerKind.IN_HOUSE))))
        composite.onSessionStart(movie, 0)

        assertEquals(listOf("play", "loadMetadata(content)"), nielsenSdk.calls)
        assertEquals(listOf("session_start"), beacons)
    }

    @Test
    fun `the brand channel name is passed to Nielsen`() {
        val sdk = object : NielsenSdkGateway by RecordingNielsenSdk() {
            var channelName: String? = null
            override fun play(channelInfo: Map<String, String>) { channelName = channelInfo["channelName"] }
        }
        BrandTrackerFactory(nielsenGateway = { sdk }, inHouseSend = {})
            .create(BrandTrackingConfig("brand-a", listOf(TrackerKind.NIELSEN), nielsenChannelName = "Brand A"))
            .single()
            .onSessionStart(movie, 0)
        assertEquals("Brand A", sdk.channelName)
    }

    @Test
    fun `a reporter cannot be declared twice`() {
        assertThrows(IllegalArgumentException::class.java) {
            BrandTrackingConfig("brand-a", listOf(TrackerKind.NIELSEN, TrackerKind.NIELSEN))
        }
    }
}
