package com.devsparkles.media3sample.player.engine.cast

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.devsparkles.media3sample.player.engine.tracking.TechnicalMediaItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le transfert téléphone <-> TV, sans appareil Cast : un CastPlayer.TransferCallback ne fait que
 * lire un Player source et écrire dans un Player cible. Deux ExoPlayer de test suffisent pour
 * vérifier ce qu'arriverait à la TV (ou au téléphone au retour).
 */
@RunWith(AndroidJUnit4::class)
class AdAwareTransferCallbackTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val players = mutableListOf<ExoPlayer>()

    @After
    fun tearDown() = players.forEach { it.release() }

    private fun newPlayer(): ExoPlayer =
        TestExoPlayerBuilder(context).setClock(FakeClock(/* isAutoAdvancing= */ true)).build().also { players += it }

    private fun item(id: String, withAds: Boolean = false): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri("https://cdn.example/$id.mpd")
        .apply { if (withAds) setAdsConfiguration(MediaItem.AdsConfiguration.Builder(Uri.parse("https://ads.example/vmap")).build()) }
        .build()

    private fun plan(
        items: List<MediaItem>,
        currentIndex: Int = 0,
        positionMs: Long = 42_000,
        playingAd: Boolean = false,
        contentPositionMs: Long = positionMs,
        originals: List<MediaItem> = emptyList(),
    ) = planTransfer(items, currentIndex, positionMs, playingAd, contentPositionMs, originals, TechnicalMediaItem::isTechnical)

    // --- Logique pure ---------------------------------------------------------------------------

    @Test
    fun `content keeps its position`() {
        val result = plan(listOf(item("movie")))
        assertEquals(42_000L, result.positionMs)
        assertEquals(0, result.currentIndex)
    }

    /** Piège n°1 : pendant une pub, currentPosition est DANS la pub. La TV doit reprendre le film. */
    @Test
    fun `during an ad the content position is sent, not the ad position`() {
        val result = plan(listOf(item("movie")), positionMs = 5_000, playingAd = true, contentPositionMs = 600_000)
        assertEquals(600_000L, result.positionMs)
    }

    /** Piège n°2 : au retour, le receiver a perdu l'AdsConfiguration ; on reprend l'item d'origine. */
    @Test
    fun `back to the phone restores the original item with its ads`() {
        val fromTv = item("movie") // reconstruit par DefaultMediaItemConverter : sans pubs
        val result = plan(listOf(fromTv), originals = listOf(item("movie", withAds = true)))
        assertNotNull(result.mediaItems.single().localConfiguration!!.adsConfiguration)
    }

    @Test
    fun `one-pixel technical item is not sent to the TV and the index follows`() {
        val onePixel = TechnicalMediaItem.create("asset:///one_pixel.mp4", mediaId = "one-pixel")
        val result = plan(listOf(onePixel, item("movie")), currentIndex = 1)
        assertEquals(listOf("movie"), result.mediaItems.map { it.mediaId })
        assertEquals(0, result.currentIndex)
        assertEquals(42_000L, result.positionMs)
    }

    @Test
    fun `current item removed restarts the next one at zero`() {
        val onePixel = TechnicalMediaItem.create("asset:///one_pixel.mp4", mediaId = "one-pixel")
        val result = plan(listOf(onePixel, item("movie")), currentIndex = 0, positionMs = 300)
        assertEquals(0, result.currentIndex)
        assertEquals(0L, result.positionMs)
    }

    @Test
    fun `nothing playable gives an empty playlist`() {
        val result = plan(listOf(MediaItem.Builder().setMediaId("no-uri").build()))
        assertTrue(result.mediaItems.isEmpty())
        assertEquals(C.INDEX_UNSET, result.currentIndex)
    }

    // --- Avec de vrais players ------------------------------------------------------------------

    /**
     * Un vrai ExoPlayer arrêté sur un mid-roll (pub à 5 s) : la « TV » (ici un 2e ExoPlayer)
     * reçoit le film à 5 s, pas à la position dans la pub.
     */
    @Test
    fun `real player stopped in a mid-roll hands over the content position`() {
        val phone = newPlayer()
        val tv = newPlayer()
        val ads = FakeTimeline.createAdPlaybackState(/* adsPerAdGroup= */ 1, 5_000_000L)
        val window = FakeTimeline.TimelineWindowDefinition.Builder()
            .setUid("movie")
            .setDurationUs(10_000_000)
            .setWindowPositionInFirstPeriodUs(0)
            .setMediaItem(item("movie"))
            .setAdPlaybackStates(listOf(ads))
            .build()
        phone.setMediaSource(FakeMediaSource(FakeTimeline(window), ExoPlayerTestRunner.VIDEO_FORMAT, ExoPlayerTestRunner.AUDIO_FORMAT))
        phone.prepare()
        phone.play()
        // Joue jusqu'à être DANS la pub, un peu après son début.
        runMainLooperUntil { phone.isPlayingAd && phone.currentPosition >= 500 }
        phone.pause()

        val adPositionMs = phone.currentPosition
        AdAwareTransferCallback(localPlayer = phone).transferState(phone, tv)

        assertEquals("movie", tv.currentMediaItem!!.mediaId)
        assertEquals(5_000L, tv.currentPosition)
        assertTrue("position dans la pub=$adPositionMs", adPositionMs != tv.currentPosition)
    }

    /** Sens TV -> téléphone : l'ExoPlayer local, arrêté, avait gardé sa playlist d'origine. */
    @Test
    fun `real local player gets its original item back`() {
        val phone = newPlayer()
        val tv = newPlayer()
        phone.setMediaItem(item("movie", withAds = true))
        phone.stop() // ce que fait CastPlayerImpl au passage sur la TV : la playlist reste
        tv.setMediaItem(item("movie"), /* startPositionMs= */ 120_000)

        AdAwareTransferCallback(localPlayer = phone).transferState(tv, phone)

        assertNotNull(phone.currentMediaItem!!.localConfiguration!!.adsConfiguration)
        assertEquals(120_000L, phone.currentPosition)
        assertNull(tv.currentMediaItem!!.localConfiguration!!.adsConfiguration)
    }
}
