package com.devsparkles.media3sample.player.engine.tracking

import android.content.Context
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test du traducteur avec un VRAI ExoPlayer (pas un mock) :
 *  - TestExoPlayerBuilder : ExoPlayer avec renderers factices (pas de MediaCodec) ;
 *  - FakeClock(auto-avançante) : le temps avance tout seul quand le player attend, d'où des
 *    tests déterministes qui « lisent » 10 s de vidéo en quelques millisecondes ;
 *  - FakeMediaSource + FakeTimeline : un média de durée connue, avec ou sans AdPlaybackState ;
 *  - TestPlayerRunHelper : fait tourner le Looper principal jusqu'à un état donné.
 * Robolectric fournit le Looper Android sur la JVM (media3-test-utils-robolectric 1.11.1 tire
 * Robolectric 4.16).
 *
 * On vérifie la séquence EXACTE d'événements normalisés pour chaque scénario.
 */
@RunWith(AndroidJUnit4::class)
class PlayerEventTranslatorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock(/* isAutoAdvancing= */ true)
    private val events = mutableListOf<PlaybackEvent>()
    private lateinit var player: ExoPlayer
    private lateinit var translator: PlayerEventTranslator

    @Before
    fun setUp() {
        player = TestExoPlayerBuilder(context).setClock(clock).build()
        translator = PlayerEventTranslator(player, clock = clock, sink = { events += it })
    }

    @After
    fun tearDown() {
        translator.release()
        player.release()
    }

    // --- Outils ---------------------------------------------------------------------------------

    private fun source(id: String, durationMs: Long = 10_000, ads: AdPlaybackState? = null): FakeMediaSource {
        val mediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(id).build())
            .build()
        val window = FakeTimeline.TimelineWindowDefinition.Builder()
            .setUid(id)
            .setDurationUs(durationMs * 1_000)
            // Fenêtre alignée sur la période : un break à 0 µs est bien un pre-roll.
            .setWindowPositionInFirstPeriodUs(0)
            .setMediaItem(mediaItem)
            .apply { if (ads != null) setAdPlaybackStates(listOf(ads)) }
            .build()
        return FakeMediaSource(FakeTimeline(window), ExoPlayerTestRunner.VIDEO_FORMAT, ExoPlayerTestRunner.AUDIO_FORMAT)
    }

    /** Le « one pixel » : un item technique très court, marqué comme tel. */
    private fun onePixel(durationMs: Long = 500): FakeMediaSource {
        val window = FakeTimeline.TimelineWindowDefinition.Builder()
            .setUid("one-pixel")
            .setDurationUs(durationMs * 1_000)
            .setWindowPositionInFirstPeriodUs(0)
            .setMediaItem(TechnicalMediaItem.create("asset:///one_pixel.mp4", mediaId = "one-pixel"))
            .build()
        return FakeMediaSource(FakeTimeline(window), ExoPlayerTestRunner.VIDEO_FORMAT, ExoPlayerTestRunner.AUDIO_FORMAT)
    }

    /** Forme compacte et stable des événements (sans les ticks). */
    private fun labels(): List<String> = events.mapNotNull { event ->
        when (event) {
            is PlaybackEvent.SessionStarted -> "SessionStarted(${event.content.id})"
            is PlaybackEvent.Paused -> "Paused(${event.reason})"
            is PlaybackEvent.Resumed -> "Resumed"
            is PlaybackEvent.Seeked -> "Seeked"
            is PlaybackEvent.ContentChanged -> "ContentChanged(${event.previous.id}->${event.next.id})"
            is PlaybackEvent.AdBreakStarted -> "AdBreakStarted(${event.adBreak.kind})"
            is PlaybackEvent.AdStarted -> "AdStarted(${event.ad.adBreak.kind}#${event.ad.indexInBreak})"
            is PlaybackEvent.AdBreakEnded -> "AdBreakEnded(${event.adBreak.kind}, resumesContent=${event.resumesContent})"
            is PlaybackEvent.Ended -> "Ended(${event.reason})"
            PlaybackEvent.Released -> "Released"
            is PlaybackEvent.Tick -> null
        }
    }

    private fun ticks() = events.filterIsInstance<PlaybackEvent.Tick>()

    /**
     * PIÈGE découvert par ces tests : `untilState(STATE_ENDED)` rend la main dès que
     * `player.playbackState` vaut ENDED, mais `onEvents` est livré à l'itération SUIVANTE du
     * Looper (ListenerSet poste un message « iteration finished »). Il faut donc vider la file
     * avant d'asserter, sinon le dernier événement normalisé n'est pas encore émis.
     */
    private fun playUntilEndedAndSettle() {
        run(player).untilState(Player.STATE_ENDED)
        run(player).untilPendingCommandsAreFullyHandled()
    }

    private fun prepareAndPlay(vararg sources: FakeMediaSource) {
        player.setMediaSources(sources.toList())
        player.prepare()
        player.play()
    }

    // --- Scénarios ------------------------------------------------------------------------------

    @Test
    fun `content only - start, one tick per second, end`() {
        prepareAndPlay(source("movie"))
        playUntilEndedAndSettle()

        assertEquals(listOf("SessionStarted(movie)", "Ended(COMPLETED)"), labels())
        // 10 s de contenu -> ~10 ticks, positions croissantes, jamais en pub.
        val positions = ticks().map { it.playhead.positionMs }
        assertTrue("ticks=$positions", ticks().size in 9..10)
        assertEquals(positions.sorted(), positions)
        assertTrue(ticks().none { it.playhead.isAd })
    }

    @Test
    fun `pause, seek while paused, resume - no implicit stop, then release closes nothing more`() {
        prepareAndPlay(source("movie"))
        play(player).untilPosition(0, 3_000)
        player.pause()
        run(player).untilPendingCommandsAreFullyHandled()
        val ticksWhilePlaying = ticks().size
        player.seekTo(6_000)
        run(player).untilPendingCommandsAreFullyHandled()
        assertEquals("aucun tick pendant la pause", ticksWhilePlaying, ticks().size)
        player.play()
        playUntilEndedAndSettle()
        translator.release()

        assertEquals(
            listOf("SessionStarted(movie)", "Paused(USER)", "Seeked", "Resumed", "Ended(COMPLETED)", "Released"),
            labels(),
        )
        val seek = events.filterIsInstance<PlaybackEvent.Seeked>().single()
        assertEquals(6_000L, seek.toMs)
    }

    @Test
    fun `releasing during playback ends the session exactly once`() {
        prepareAndPlay(source("movie"))
        play(player).untilPosition(0, 2_000)
        translator.release()
        translator.release()

        assertEquals(listOf("SessionStarted(movie)", "Ended(RELEASED)", "Released"), labels())
        // untilPosition s'arrête quand le THREAD DE LECTURE atteint 2 000 ms ; la position lue
        // côté thread applicatif est une estimation légèrement en retard (1 990 ms observés).
        val final = events.filterIsInstance<PlaybackEvent.Ended>().single().finalPlayhead!!
        assertTrue("position finale=${final.positionMs}", final.positionMs in 1_900..2_100)
    }

    @Test
    fun `player stop ends the session`() {
        prepareAndPlay(source("movie"))
        play(player).untilPosition(0, 1_000)
        player.stop()
        run(player).untilPendingCommandsAreFullyHandled()

        assertEquals(listOf("SessionStarted(movie)", "Ended(STOPPED)"), labels())
    }

    /** Le scénario du catalogue : pre-roll + mid-roll + post-roll, joués par le MÊME player. */
    @Test
    fun `pre-roll, mid-roll and post-roll`() {
        val ads = FakeTimeline.createAdPlaybackState(/* adsPerAdGroup= */ 1, 0L, 5_000_000L, C.TIME_END_OF_SOURCE)
        prepareAndPlay(source("movie", durationMs = 10_000, ads = ads))
        playUntilEndedAndSettle()

        assertEquals(
            listOf(
                "SessionStarted(movie)",
                "AdBreakStarted(PREROLL)", "AdStarted(PREROLL#0)", "AdBreakEnded(PREROLL, resumesContent=true)",
                "AdBreakStarted(MIDROLL)", "AdStarted(MIDROLL#0)", "AdBreakEnded(MIDROLL, resumesContent=true)",
                "AdBreakStarted(POSTROLL)", "AdStarted(POSTROLL#0)", "AdBreakEnded(POSTROLL, resumesContent=false)",
                "Ended(COMPLETED)",
            ),
            labels(),
        )
        // Les ticks continuent pendant les pubs (règle Nielsen), avec la position DANS la pub.
        assertTrue(ticks().any { it.playhead.isAd })
        assertTrue(ticks().any { !it.playhead.isAd })
    }

    /**
     * Le « dernier playhead » de chaque asset quitté vient de oldPosition (onPositionDiscontinuity),
     * pas du dernier tick : au mid-roll, c'est la position EXACTE du point de coupure (5 s).
     */
    @Test
    fun `transitions carry the exact exit position of the asset being left`() {
        val ads = FakeTimeline.createAdPlaybackState(/* adsPerAdGroup= */ 1, 5_000_000L)
        prepareAndPlay(source("movie", durationMs = 10_000, ads = ads))
        playUntilEndedAndSettle()

        val toAd = events.filterIsInstance<PlaybackEvent.AdStarted>().single().exitPlayhead!!
        assertEquals(false, toAd.isAd)
        assertEquals(5_000L, toAd.positionMs)

        val back = events.filterIsInstance<PlaybackEvent.AdBreakEnded>().single()
        val adExit = back.exitPlayhead!!
        assertTrue(adExit.isAd)
        assertTrue("sortie de pub=${adExit.positionMs}", adExit.positionMs > 0)
    }

    @Test
    fun `playlist transition emits a single ContentChanged`() {
        prepareAndPlay(source("first", durationMs = 3_000), source("second", durationMs = 3_000))
        playUntilEndedAndSettle()

        assertEquals(listOf("SessionStarted(first)", "ContentChanged(first->second)", "Ended(COMPLETED)"), labels())
    }

    @Test
    fun `repeat one - each loop is a new session`() {
        player.repeatMode = Player.REPEAT_MODE_ONE
        prepareAndPlay(source("movie", durationMs = 3_000))
        runMainLooperUntil { events.count { it is PlaybackEvent.SessionStarted } >= 2 }
        player.pause()
        advance(player).untilPendingCommandsAreFullyHandled()

        assertEquals(
            listOf("SessionStarted(movie)", "Ended(COMPLETED)", "SessionStarted(movie)", "Paused(USER)"),
            labels(),
        )
    }

    // --- Bug du « one pixel » ----------------------------------------------------------------------

    /** Reproduit le bug : sans marquage, l'item technique ouvre une session et pollue la mesure. */
    @Test
    fun `one pixel bug - an unmarked technical item opens a ghost session`() {
        translator.release()
        events.clear()
        translator = PlayerEventTranslator(player, clock = clock, isTechnical = { false }, sink = { events += it })
        prepareAndPlay(onePixel(), source("movie", durationMs = 3_000))
        playUntilEndedAndSettle()

        // Nielsen aurait reçu : play/loadMetadata(one-pixel), puis end, play, loadMetadata(movie).
        assertEquals(listOf("SessionStarted(one-pixel)", "ContentChanged(one-pixel->movie)", "Ended(COMPLETED)"), labels())
    }

    @Test
    fun `one pixel fix - a technical item is invisible, the session starts on the real content`() {
        prepareAndPlay(onePixel(), source("movie", durationMs = 3_000))
        playUntilEndedAndSettle()

        assertEquals(listOf("SessionStarted(movie)", "Ended(COMPLETED)"), labels())
        // Position dans le PROGRAMME (quelques ms observées), sans les 500 ms du one pixel.
        val start = events.filterIsInstance<PlaybackEvent.SessionStarted>().single().positionMs
        assertTrue("position de départ=$start", start < 100)
    }
}
