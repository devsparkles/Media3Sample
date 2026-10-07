package com.devsparkles.media3sample.player.engine.analytics

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.drm.KeyRequestInfo
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/**
 * RÔLE : OBSERVABILITÉ / QoE (Quality of Experience) du player.
 *
 * Les KPIs qu'une équipe Player suit en prod (et qu'on vous demandera sûrement) :
 *  - Time To First Frame (TTFF)  : délai entre prepare() et la 1re image.
 *  - Rebuffering ratio           : temps passé en BUFFERING alors que l'utilisateur veut lire.
 *  - Bitrate moyen / switches    : qualité perçue, stabilité de l'ABR.
 *  - Frames perdues              : appareil trop faible, décodeur saturé.
 *  - Taux d'erreur par code      : DRM, réseau, décodeur...
 *
 * AnalyticsListener : chaque callback reçoit un EventTime (position, période concernée, pub ou
 * contenu...). Media3 fournit aussi PlaybackStatsListener qui agrège ces métriques tout seul.
 * https://developer.android.com/media/media3/exoplayer/analytics
 *
 * Ici on logue dans Logcat ; en prod on enverrait vers Firebase / Datadog / Conviva / Mux.
 */
@OptIn(UnstableApi::class)
class PlaybackAnalyticsLogger : AnalyticsListener {

    private val createdAtMs = SystemClock.elapsedRealtime()
    private var rebufferStartMs: Long? = null
    private var totalRebufferMs = 0L

    private var firstFrameLogged = false

    /**
     * Appelé à CHAQUE première frame après un changement de période (pub -> contenu, seek...),
     * pas seulement au démarrage. Le TTFF ne se mesure donc qu'une fois.
     */
    override fun onRenderedFirstFrame(eventTime: EventTime, output: Any, renderTimeMs: Long) {
        if (!firstFrameLogged) {
            firstFrameLogged = true
            log("first frame rendered, TTFF=${renderTimeMs - createdAtMs} ms")
        } else {
            log("first frame after transition (pub/contenu, seek)")
        }
    }

    override fun onPlaybackStateChanged(eventTime: EventTime, state: Int) {
        val now = SystemClock.elapsedRealtime()
        if (state == Player.STATE_BUFFERING) {
            rebufferStartMs = now
        } else {
            rebufferStartMs?.let { totalRebufferMs += now - it }
            rebufferStartMs = null
        }
        log("state=${stateName(state)} totalBuffering=${totalRebufferMs} ms")
    }

    /** Changement de qualité vidéo (ABR) : on voit l'adaptation à la bande passante. */
    override fun onVideoInputFormatChanged(
        eventTime: EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        log("video format ${format.width}x${format.height} @ ${format.bitrate / 1000} kbps (${format.sampleMimeType})")
    }

    // L'estimation de bande passante (qui pilote l'ABR) s'observe via
    // DefaultBandwidthMeter.addEventListener(...) (l'ancien onBandwidthEstimate est déprécié).

    override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) {
        log("dropped $droppedFrames frames in $elapsedMs ms")
    }

    // --- DRM -----------------------------------------------------------------------------
    override fun onDrmSessionAcquired(eventTime: EventTime, state: Int) = log("DRM session acquired (state=$state)")

    /** Licence reçue : loadInfos contient la/les requête(s) HTTP vers le serveur de licence (durée...). */
    override fun onDrmKeysLoaded(eventTime: EventTime, keyRequestInfo: KeyRequestInfo) {
        val licenceMs = keyRequestInfo.loadInfos.sumOf { it.loadDurationMs }
        log("DRM keys loaded (licence OK en $licenceMs ms)")
    }

    override fun onDrmSessionManagerError(eventTime: EventTime, error: Exception) {
        Log.e(TAG, "DRM error", error)
    }

    // --- Erreurs ---------------------------------------------------------------------------
    /** Erreur de chargement d'UN segment : ExoPlayer va réessayer, ce n'est pas encore fatal. */
    override fun onLoadError(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        Log.w(TAG, "load error ${loadEventInfo.uri} : ${error.message}")
    }

    /** Erreur FATALE : le player passe en STATE_IDLE, il faudra prepare() pour réessayer. */
    override fun onPlayerError(eventTime: EventTime, error: PlaybackException) {
        Log.e(TAG, "player error ${error.errorCodeName}", error)
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "?"
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "PlaybackQoE"
    }
}
