package com.devsparkles.media3sample.player.engine.tracking

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import com.devsparkles.media3sample.core.domain.tracking.AdBreakKind
import com.devsparkles.media3sample.core.domain.tracking.PauseReason
import com.devsparkles.media3sample.core.domain.tracking.PlaybackEvent
import com.devsparkles.media3sample.core.domain.tracking.PlaybackSessionStateMachine
import com.devsparkles.media3sample.core.domain.tracking.PlaybackStatus
import com.devsparkles.media3sample.core.domain.tracking.PlayerSnapshot
import com.devsparkles.media3sample.core.domain.tracking.Playhead
import com.devsparkles.media3sample.core.domain.tracking.SnapshotHints
import com.devsparkles.media3sample.core.domain.tracking.Suppression
import com.devsparkles.media3sample.core.domain.tracking.TrackedAd
import com.devsparkles.media3sample.core.domain.tracking.TrackedAdBreak
import com.devsparkles.media3sample.core.domain.tracking.TrackedContent

/**
 * RÔLE : le SEUL code du tracking d'audience qui connaît Media3. Il lit l'état du player,
 * le traduit en [PlayerSnapshot] (modèle du domaine) et confie la logique de session à
 * [PlaybackSessionStateMachine] (Kotlin pur, testée en JVM).
 *
 * POINT D'ENTRÉE : `Player.Listener.onEvents(player, events)`.
 * Doc Media3 : « State changes and events that happen within one Looper message queue iteration
 * are reported together and only after all individual callbacks were triggered. »
 * https://developer.android.com/media/media3/exoplayer/listening-to-player-events#individual-callbacks-vs-onevents
 * On lit donc l'état CONSOLIDÉ (playbackState, playWhenReady, isPlaying, isPlayingAd,
 * currentMediaItem, currentPosition) une fois par itération, au lieu de reconstituer l'état à
 * partir d'une cascade de callbacks dont l'ordre relatif n'est pas un contrat.
 *
 * Les callbacks individuels ne servent qu'à mémoriser les RAISONS, que l'état seul ne donne pas
 * ([SnapshotHints]) : raison d'une discontinuité (seek ?), d'une transition (repeat ?), d'une
 * pause (focus audio ?). Elles sont consommées au onEvents suivant.
 *
 * PIÈGES MEDIA3 TRAITÉS :
 *  - onMediaItemTransition et onPositionDiscontinuity(AUTO_TRANSITION) arrivent dans la MÊME
 *    itération lors d'un passage au média suivant : un seul onEvents -> une seule transition.
 *  - isPlaying ≠ playWhenReady : isPlaying = playWhenReady && READY && pas de suppression.
 *    Une perte TRANSITOIRE de focus audio (appel) laisse playWhenReady à true mais met
 *    playbackSuppressionReason à TRANSIENT_AUDIO_FOCUS_LOSS.
 *  - Erreur : si le code applicatif relance prepare() DANS onPlayerError (recalage
 *    BEHIND_LIVE_WINDOW du ViewModel), l'état consolidé n'est déjà plus IDLE. On se fie donc à
 *    `events.contains(EVENT_PLAYER_ERROR)`, pas seulement à `player.playerError`.
 *  - Pubs (AdsMediaSource + VmapAdsLoader) : elles sont jouées par le MÊME player ; on compare
 *    isPlayingAd / currentAdGroupIndex / currentAdIndexInAdGroup d'un onEvents à l'autre.
 *    Le passage pub -> contenu produit onPositionDiscontinuity(AUTO_TRANSITION) suivi de onEvents.
 *    Comportement vérifié par PlayerEventTranslatorTest (pre + mid + post-roll).
 *
 * THREAD : tout s'exécute sur `player.applicationLooper` (callbacks Media3 ET ticker 1 s, via un
 * HandlerWrapper créé par [clock] sur ce looper). Lire currentPosition depuis un autre thread
 * est interdit : https://developer.android.com/media/media3/exoplayer/hello-world#a-note-on-threading
 *
 * @param clock horloge Media3 : `Clock.DEFAULT` en prod, `FakeClock` en test (ticker déterministe)
 * @param adIdProvider identifiant VAST de la pub (groupe, index) ; null -> identifiant de repli
 * @param log timeline de debug (« callback Media3 → événement normalisé »), null = pas de log
 * @param sink reçoit chaque événement normalisé (en prod : dispatch vers le CompositeTracker)
 */
@OptIn(UnstableApi::class)
class PlayerEventTranslator(
    private val player: Player,
    private val clock: Clock = Clock.DEFAULT,
    private val adIdProvider: (adGroupIndex: Int, adIndexInAdGroup: Int) -> String? = { _, _ -> null },
    private val log: ((String) -> Unit)? = null,
    private val tickIntervalMs: Long = 1_000,
    private val sink: (PlaybackEvent) -> Unit,
) : Player.Listener {

    private val machine = PlaybackSessionStateMachine()
    private val handler = clock.createHandler(player.applicationLooper, /* callback= */ null)
    private val period = Timeline.Period()

    private val tickRunnable = Runnable { onTick() }
    private var tickScheduled = false
    private var released = false

    // Raisons collectées pendant l'itération courante, consommées dans onEvents.
    private var pendingSeek: SnapshotHints.Seek? = null
    private var pendingRepeat = false
    private var pendingPauseReason: PauseReason? = null
    private val pendingCallbacks = mutableListOf<String>()

    init {
        player.addListener(this)
    }

    // --- Callbacks individuels : on ne garde que les RAISONS (et les noms, pour le log) -------

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        pendingCallbacks += "onPositionDiscontinuity(${discontinuityName(reason)})"
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            // Plusieurs seeks dans la même itération : on garde le point de départ du premier.
            pendingSeek = SnapshotHints.Seek(pendingSeek?.fromMs ?: oldPosition.contentPositionMs, newPosition.contentPositionMs)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        pendingCallbacks += "onMediaItemTransition(${transitionName(reason)})"
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) pendingRepeat = true
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        pendingCallbacks += "onPlayWhenReadyChanged($playWhenReady)"
        if (!playWhenReady) pendingPauseReason = pauseReason(reason)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        pendingCallbacks += "onPlaybackStateChanged(${statusOf(playbackState)})"
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        pendingCallbacks += "onIsPlayingChanged($isPlaying)"
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        pendingCallbacks += "onPlaybackSuppressionReasonChanged(${suppressionOf(playbackSuppressionReason)})"
    }

    override fun onPlayerError(error: PlaybackException) {
        pendingCallbacks += "onPlayerError(${error.errorCodeName})"
    }

    // --- Point d'entrée : état consolidé -------------------------------------------------------

    override fun onEvents(player: Player, events: Player.Events) {
        if (released) return
        val hints = SnapshotHints(
            errorOccurred = events.contains(Player.EVENT_PLAYER_ERROR) || player.playerError != null,
            seek = pendingSeek,
            repeated = pendingRepeat,
            pauseReason = pendingPauseReason,
        )
        val trigger = "onEvents[${pendingCallbacks.joinToString()}]"
        pendingSeek = null
        pendingRepeat = false
        pendingPauseReason = null
        pendingCallbacks.clear()

        emit(trigger, machine.onSnapshot(snapshot(), hints))
        updateTicker()
    }

    // --- Ticker 1 s (playhead) -----------------------------------------------------------------

    /**
     * Le ticker n'est armé que si une session est active : pas de réveil inutile du CPU en pause.
     * Les ticks eux-mêmes ne partent que si `isPlaying` (pas pendant le buffering).
     */
    private fun updateTicker() {
        val shouldTick = machine.isSessionActive && !released
        if (shouldTick && !tickScheduled) {
            tickScheduled = true
            handler.postDelayed(tickRunnable, tickIntervalMs)
        } else if (!shouldTick && tickScheduled) {
            tickScheduled = false
            handler.removeCallbacksAndMessages(null)
        }
    }

    private fun onTick() {
        tickScheduled = false
        if (released) return
        emit("tick", machine.onTick(snapshot()))
        updateTicker()
    }

    // --- Cycle de vie (appelé par PlayerSession) ------------------------------------------------

    /**
     * À appeler AVANT player.release() : on lit encore la position finale. Idempotent.
     * Garantit la fermeture de session sur onCleared du ViewModel.
     */
    fun release() {
        if (released) return
        handler.removeCallbacksAndMessages(null)
        tickScheduled = false
        emit("release()", machine.release(snapshot()))
        released = true
        player.removeListener(this)
    }

    // --- Traduction Media3 -> domaine ----------------------------------------------------------

    private fun emit(trigger: String, events: List<PlaybackEvent>) {
        for (event in events) {
            if (event !is PlaybackEvent.Tick) log?.invoke("$trigger → $event")
            sink(event)
        }
    }

    private fun snapshot(): PlayerSnapshot {
        val isPlayingAd = player.isPlayingAd
        val isLive = player.isCurrentMediaItemLive
        return PlayerSnapshot(
            content = player.currentMediaItem?.let { contentOf(it, isLive) },
            status = statusOf(player.playbackState),
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            suppression = suppressionOf(player.playbackSuppressionReason),
            ad = if (isPlayingAd) currentAd() else null,
            contentPositionMs = player.contentPosition,
            // Pendant une pub, currentPosition = position DANS la pub ; sinon dans le contenu.
            playhead = Playhead(player.currentPosition, isPlayingAd, isLive, clock.currentTimeMillis()),
        )
    }

    private fun contentOf(item: MediaItem, isLive: Boolean): TrackedContent {
        val id = item.mediaId.ifEmpty { item.localConfiguration?.uri?.toString().orEmpty() }
        return TrackedContent(
            id = id,
            title = item.mediaMetadata.title?.toString() ?: id,
            // Pendant une pub, getContentDuration() renvoie bien la durée du CONTENU.
            durationMs = player.contentDuration.takeIf { it != C.TIME_UNSET },
            isLive = isLive,
        )
    }

    /**
     * Pub en cours, déduite de l'AdPlaybackState porté par la période courante.
     * Pre/mid/post-roll : `getAdGroupTimeUs` = position du break dans la PÉRIODE ;
     * `C.TIME_END_OF_SOURCE` = post-roll ; on corrige du décalage fenêtre/période
     * (`positionInWindowUs`) pour reconnaître un pre-roll.
     */
    private fun currentAd(): TrackedAd? {
        val group = player.currentAdGroupIndex
        val index = player.currentAdIndexInAdGroup
        if (group == C.INDEX_UNSET || index == C.INDEX_UNSET) return null
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        timeline.getPeriod(player.currentPeriodIndex, period)
        val groupTimeUs = period.getAdGroupTimeUs(group)
        val kind = when {
            groupTimeUs == C.TIME_END_OF_SOURCE -> AdBreakKind.POSTROLL
            groupTimeUs + period.positionInWindowUs <= 0 -> AdBreakKind.PREROLL
            else -> AdBreakKind.MIDROLL
        }
        val adBreak = TrackedAdBreak(group, kind, adCount = period.getAdCountInAdGroup(group))
        return TrackedAd(
            adBreak = adBreak,
            indexInBreak = index,
            id = adIdProvider(group, index) ?: "ad-$group-$index",
            // Pendant une pub, getDuration() renvoie la durée de la PUB.
            durationMs = player.duration.takeIf { it != C.TIME_UNSET },
        )
    }

    private companion object {
        fun statusOf(state: Int) = when (state) {
            Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
            Player.STATE_READY -> PlaybackStatus.READY
            Player.STATE_ENDED -> PlaybackStatus.ENDED
            else -> PlaybackStatus.IDLE
        }

        fun suppressionOf(reason: Int) = when (reason) {
            Player.PLAYBACK_SUPPRESSION_REASON_NONE -> Suppression.NONE
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> Suppression.TRANSIENT_AUDIO_FOCUS_LOSS
            Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING -> Suppression.SCRUBBING
            // UNSUITABLE_AUDIO_OUTPUT (et l'ancienne constante dépréciée UNSUITABLE_AUDIO_ROUTE).
            else -> Suppression.UNSUITABLE_OUTPUT
        }

        /** Raison de `playWhenReady = false` (Player.PLAY_WHEN_READY_CHANGE_REASON_*). */
        fun pauseReason(reason: Int) = when (reason) {
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> PauseReason.USER
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> PauseReason.AUDIO_FOCUS_LOSS
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> PauseReason.AUDIO_BECOMING_NOISY
            // Suppression transitoire qui a trop duré : Media3 transforme l'interruption en pause.
            Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> PauseReason.INTERRUPTION
            else -> PauseReason.OTHER
        }

        fun discontinuityName(reason: Int) = when (reason) {
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
            Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
            Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
            Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
            Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
            Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
            else -> "reason=$reason"
        }

        fun transitionName(reason: Int) = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            else -> "reason=$reason"
        }
    }
}
