package com.devsparkles.media3sample.player.engine.ads

import androidx.annotation.OptIn
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.common.AdViewProvider
import androidx.media3.exoplayer.source.ads.AdsLoader
import androidx.media3.exoplayer.source.ads.AdsMediaSource
import com.devsparkles.media3sample.core.domain.model.AdBreak
import com.devsparkles.media3sample.core.domain.model.AdBreakEvent
import com.devsparkles.media3sample.core.domain.model.AdSchedule
import com.devsparkles.media3sample.core.domain.model.AdTrackingEvent
import com.devsparkles.media3sample.core.domain.model.LinearAd
import com.devsparkles.media3sample.core.domain.repository.AdTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * RÔLE : implémentation maison de l'interface Media3 `AdsLoader`, pilotée par notre VMAP.
 *
 * C'est le pont entre la chaîne pub (domaine) et ExoPlayer. Cycle de vie, appelé par
 * AdsMediaSource sur le MAIN THREAD :
 *
 *   setPlayer(player)          <- avant prepare()
 *   start(..., adTagDataSpec)  <- au prepare() : on télécharge/parse le VMAP, puis on publie
 *                                 un AdPlaybackState via eventListener.onAdPlaybackState().
 *                                 TANT QU'AUCUN ÉTAT N'EST PUBLIÉ, LE CONTENU NE DÉMARRE PAS
 *                                 (le player doit savoir s'il y a un pre-roll) -> timeout global.
 *   handlePrepareComplete()    <- le fichier d'une pub est prêt
 *   handlePrepareError()       <- le fichier d'une pub est illisible -> on la marque en erreur
 *                                 (ExoPlayer la saute) + ping d'erreur VAST 405.
 *   stop()                     <- la source est libérée (changement de média, erreur, release)
 *   release()                  <- le loader n'est plus utilisé
 *
 * En parallèle, on écoute le Player pour le TRACKING (impression, quartiles, complete, skip,
 * pause/resume, mute) et on expose l'état de la pub en cours à l'UI (`currentAd`).
 *
 * Référence : l'implémentation officielle ImaAdsLoader suit exactement ce contrat :
 * https://github.com/androidx/media/tree/release/libraries/exoplayer_ima
 */
@OptIn(UnstableApi::class)
class VmapAdsLoader(
    private val loadAdSchedule: suspend (adTagUrl: String) -> AdSchedule,
    private val tracker: AdTracker,
    private val scheduleTimeoutMs: Long = 10_000,
    private val progressIntervalMs: Long = 200,
) : AdsLoader {

    // Main.immediate : toutes les interactions avec le Player doivent se faire sur le thread
    // de l'application (player.applicationLooper = main ici). SupervisorJob : l'échec d'une
    // coroutine fille n'annule pas les autres.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: Player? = null
    private var eventListener: AdsLoader.EventListener? = null
    private var adsId: Any? = null
    private var adPlaybackState: AdPlaybackState? = null
    /** Breaks dans l'ordre des "ad groups" de l'AdPlaybackState (même index). */
    private var breaks: List<AdBreak> = emptyList()
    private var loadJob: Job? = null
    private var progressJob: Job? = null
    private var playingAd: PlayingAd? = null

    private val _currentAd = MutableStateFlow<AdUiInfo?>(null)

    /** État de la pub en cours, observé par le ViewModel (compteur, bouton "Passer"...). */
    val currentAd: StateFlow<AdUiInfo?> = _currentAd.asStateFlow()

    // ------------------------------------------------------------------------------------
    // Contrat AdsLoader
    // ------------------------------------------------------------------------------------

    override fun setPlayer(player: Player?) {
        this.player = player
    }

    /** Types de contenu supportés (DASH, HLS...). On n'impose aucune restriction. */
    override fun setSupportedContentTypes(vararg contentTypes: Int) = Unit

    override fun start(
        adsMediaSource: AdsMediaSource,
        adTagDataSpec: DataSpec,
        adsId: Any,
        adViewProvider: AdViewProvider,
        eventListener: AdsLoader.EventListener,
    ) {
        this.eventListener = eventListener
        player?.addListener(playerListener)
        startProgressPolling()

        // Même contenu (ex : re-prepare après une erreur réseau) : on republie l'état existant,
        // les pubs déjà vues restent marquées PLAYED et ne sont pas rejouées.
        if (adsId == this.adsId) {
            adPlaybackState?.let { eventListener.onAdPlaybackState(it) }
            if (adPlaybackState != null || loadJob?.isActive == true) return
        }
        this.adsId = adsId
        adPlaybackState = null

        loadJob = scope.launch {
            // Règle d'or : la pub ne doit JAMAIS bloquer le contenu. Si l'Ad Proxy ne répond
            // pas à temps, on publie un état sans pub et le film démarre.
            val schedule = withTimeoutOrNull(scheduleTimeoutMs) {
                loadAdSchedule(adTagDataSpec.uri.toString())
            } ?: AdSchedule.EMPTY
            val (orderedBreaks, state) = AdPlaybackStateMapper.map(adsId, schedule)
            breaks = orderedBreaks
            publish(state)
        }
    }

    override fun stop(adsMediaSource: AdsMediaSource, eventListener: AdsLoader.EventListener) {
        player?.removeListener(playerListener)
        progressJob?.cancel()
        this.eventListener = null
        _currentAd.value = null
    }

    override fun handlePrepareComplete(adsMediaSource: AdsMediaSource, adGroupIndex: Int, adIndexInAdGroup: Int) {
        // Rien à faire : la pub est prête. (IMA s'en sert pour notifier son SDK.)
    }

    override fun handlePrepareError(
        adsMediaSource: AdsMediaSource,
        adGroupIndex: Int,
        adIndexInAdGroup: Int,
        exception: IOException,
    ) {
        adAt(adGroupIndex, adIndexInAdGroup)?.let { tracker.track(it.errorUrls, VAST_ERROR_MEDIA_PLAYBACK) }
        // Marquer la pub en erreur -> ExoPlayer la saute et passe à la suivante / au contenu.
        adPlaybackState?.let { publish(it.withAdLoadError(adGroupIndex, adIndexInAdGroup)) }
    }

    override fun release() {
        scope.cancel()
        player = null
        eventListener = null
        adPlaybackState = null
        adsId = null
    }

    // ------------------------------------------------------------------------------------
    // Actions utilisateur (appelées depuis l'UI via le ViewModel)
    // ------------------------------------------------------------------------------------

    /** Bouton "Passer la pub". Ne fait rien si la pub n'est pas (encore) skippable. */
    fun skipCurrentAd() {
        val player = player ?: return
        if (!player.isPlayingAd) return
        val group = player.currentAdGroupIndex
        val index = player.currentAdIndexInAdGroup
        val ad = adAt(group, index) ?: return
        val skipOffset = ad.skipOffsetMs ?: return
        if (player.currentPosition < skipOffset) return

        tracker.track(ad.trackingEvents[AdTrackingEvent.SKIP].orEmpty())
        playingAd = null
        // Marquer SKIPPED dans l'AdPlaybackState suffit : ExoPlayer quitte immédiatement la pub.
        adPlaybackState?.let { publish(it.withSkippedAd(group, index)) }
        if (index == breaks[group].ads.lastIndex) trackBreak(group, AdBreakEvent.BREAK_END)
    }

    /** Clic sur la pub : ping ClickTracking et renvoie l'URL annonceur à ouvrir. */
    fun onAdClicked(): String? {
        val player = player ?: return null
        val ad = adAt(player.currentAdGroupIndex, player.currentAdIndexInAdGroup) ?: return null
        tracker.track(ad.trackingEvents[AdTrackingEvent.CLICK_TRACKING].orEmpty())
        eventListener?.onAdClicked()
        return ad.clickThroughUrl
    }

    // ------------------------------------------------------------------------------------
    // Tracking
    // ------------------------------------------------------------------------------------

    /**
     * Player.Listener : interface de callbacks d'état du player (lecture, position, erreurs...).
     * https://developer.android.com/media/media3/exoplayer/listening-to-player-events
     */
    private val playerListener = object : Player.Listener {

        /**
         * Discontinuité = la position "saute" : fin d'une pub -> contenu, pub -> pub suivante,
         * seek... AUTO_TRANSITION signifie "la période précédente s'est terminée normalement".
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && oldPosition.adGroupIndex != C.INDEX_UNSET) {
                onAdCompleted(oldPosition.adGroupIndex, oldPosition.adIndexInAdGroup)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Un post-roll se termine par STATE_ENDED (pas de discontinuité vers du contenu).
            if (playbackState == Player.STATE_ENDED) playingAd?.let { onAdCompleted(it.group, it.index) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val current = playingAd ?: return
            val event = if (playWhenReady) AdTrackingEvent.RESUME else AdTrackingEvent.PAUSE
            tracker.track(current.ad.trackingEvents[event].orEmpty())
        }

        override fun onVolumeChanged(volume: Float) {
            val current = playingAd ?: return
            val event = if (volume == 0f) AdTrackingEvent.MUTE else AdTrackingEvent.UNMUTE
            tracker.track(current.ad.trackingEvents[event].orEmpty())
        }
    }

    /**
     * Le Player n'émet pas d'événement "position changée" (ce serait trop fréquent) : on
     * l'interroge périodiquement. Media3 propose aussi `player.createMessage(...)` pour être
     * notifié à une position précise, utile pour des quartiles très précis.
     */
    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                onProgressTick()
                delay(progressIntervalMs)
            }
        }
    }

    private fun onProgressTick() {
        val player = player ?: return
        if (!player.isPlayingAd) {
            _currentAd.value = null
            return
        }
        val group = player.currentAdGroupIndex
        val index = player.currentAdIndexInAdGroup
        val ad = adAt(group, index) ?: return

        // L'impression ne part que quand la pub JOUE vraiment (et pas pendant le buffering
        // initial) : c'est ce que les régies facturent, et l'IAB/MRC parle de "1re frame rendue".
        if (playingAd?.matches(group, index) != true && player.isPlaying) {
            playingAd = PlayingAd(group, index, ad, AdProgressTracker())
            if (index == 0) trackBreak(group, AdBreakEvent.BREAK_START)
            tracker.track(ad.impressionUrls)
        }

        // Pendant une pub, player.duration / currentPosition concernent LA PUB (pas le contenu).
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: ad.durationMs
        val positionMs = player.currentPosition
        playingAd?.takeIf { it.matches(group, index) }?.progress
            ?.onProgress(positionMs, durationMs)
            ?.forEach { event -> tracker.track(ad.trackingEvents[event].orEmpty()) }

        val skipOffsetMs = ad.skipOffsetMs
        _currentAd.value = AdUiInfo(
            adNumber = index + 1,
            adCount = breaks[group].ads.size,
            remainingMs = (durationMs - positionMs).coerceAtLeast(0),
            skipOffsetMs = skipOffsetMs,
            canSkip = skipOffsetMs != null && positionMs >= skipOffsetMs,
            secondsBeforeSkip = skipOffsetMs?.let { ((it - positionMs).coerceAtLeast(0) + 999) / 1000 },
            hasClickThrough = ad.clickThroughUrl != null,
        )
    }

    private fun onAdCompleted(group: Int, index: Int) {
        val ad = adAt(group, index) ?: return
        val tracked = playingAd?.takeIf { it.matches(group, index) }
        if (tracked == null || tracked.progress.markComplete()) {
            tracker.track(ad.trackingEvents[AdTrackingEvent.COMPLETE].orEmpty())
        }
        playingAd = null
        // PLAYED : si l'utilisateur revient en arrière avant ce mid-roll, il ne sera pas rejoué.
        adPlaybackState?.let { publish(it.withPlayedAd(group, index)) }
        if (index == breaks[group].ads.lastIndex) trackBreak(group, AdBreakEvent.BREAK_END)
    }

    private fun trackBreak(group: Int, event: AdBreakEvent) {
        tracker.track(breaks.getOrNull(group)?.trackingEvents?.get(event).orEmpty())
    }

    private fun publish(state: AdPlaybackState) {
        adPlaybackState = state
        eventListener?.onAdPlaybackState(state)
    }

    private fun adAt(group: Int, index: Int): LinearAd? = breaks.getOrNull(group)?.ads?.getOrNull(index)

    private class PlayingAd(val group: Int, val index: Int, val ad: LinearAd, val progress: AdProgressTracker) {
        fun matches(group: Int, index: Int) = this.group == group && this.index == index
    }

    private companion object {
        /** VAST : "Problem displaying MediaFile". */
        const val VAST_ERROR_MEDIA_PLAYBACK = 405
    }
}

/** État d'affichage de la pub en cours, consommé par l'UI. */
data class AdUiInfo(
    val adNumber: Int,
    val adCount: Int,
    val remainingMs: Long,
    val skipOffsetMs: Long?,
    val canSkip: Boolean,
    val secondsBeforeSkip: Long?,
    val hasClickThrough: Boolean,
)
