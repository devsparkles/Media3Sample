package com.devsparkles.media3sample.core.domain.usecase

import com.devsparkles.media3sample.core.domain.model.MediaFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests JVM purs : pas d'émulateur, exécution en quelques ms (`./gradlew :core:domain:test`).
 * C'est un des gros bénéfices d'avoir un domaine sans Android.
 */
class MediaFileSelectorTest {

    private fun file(mime: String, kbps: Int?, h: Int = 720) =
        MediaFile(url = "https://cdn/$mime/$kbps", mimeType = mime, width = h * 16 / 9, height = h, bitrateKbps = kbps)

    @Test
    fun `picks highest bitrate under target`() {
        val result = MediaFileSelector.select(
            listOf(file("video/mp4", 500), file("video/mp4", 1_800), file("video/mp4", 4_000)),
            targetBitrateKbps = 2_000,
        )
        assertEquals(1_800, result?.bitrateKbps)
    }

    @Test
    fun `falls back to lowest bitrate when all exceed target`() {
        val result = MediaFileSelector.select(listOf(file("video/mp4", 3_000), file("video/mp4", 5_000)), 2_000)
        assertEquals(3_000, result?.bitrateKbps)
    }

    @Test
    fun `ignores unsupported VPAID files`() {
        assertNull(MediaFileSelector.select(listOf(file("application/javascript", 100))))
    }
}
