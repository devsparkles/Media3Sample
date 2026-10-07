package com.devsparkles.media3sample.player.engine.error

import androidx.media3.common.PlaybackException
import com.devsparkles.media3sample.core.domain.model.PlayerError

/**
 * RÔLE : traduire une PlaybackException Media3 en PlayerError métier.
 *
 * Les codes d'erreur Media3 sont rangés par famille (numérotation) :
 *   1xxx : divers (1002 = BEHIND_LIVE_WINDOW : en live, on est sorti de la fenêtre DVR)
 *   2xxx : I/O (réseau, HTTP 4xx/5xx, fichier introuvable)
 *   3xxx : parsing (manifest DASH/HLS mal formé, conteneur illisible)
 *   4xxx : décodage (MediaCodec, format non supporté par l'appareil)
 *   5xxx : AudioTrack
 *   6xxx : DRM (licence refusée, appareil non provisionné, clés expirées...)
 * https://developer.android.com/reference/androidx/media3/common/PlaybackException
 *
 * En entretien : savoir lire une stack trace ExoPlayer, et savoir que `error.errorCodeName`
 * + `error.cause` sont ce qu'on remonte dans les logs/crash reporting (Crashlytics, Datadog...).
 */
fun PlaybackException.toPlayerError(): PlayerError {
    val detail = "$errorCodeName: ${cause?.message ?: message}"
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        -> PlayerError.Source(detail)

        in 2000..2999 -> PlayerError.Network(detail)
        in 3000..3999 -> PlayerError.Source(detail)
        in 4000..4999 -> PlayerError.Decoder(detail)

        // DRM : les plus fréquents en prod sont LICENSE_ACQUISITION_FAILED (token expiré,
        // droits géographiques) et DEVICE_REVOKED / PROVISIONING_FAILED (appareil).
        in 6000..6999 -> PlayerError.Drm(detail)
        else -> PlayerError.Unknown(detail)
    }
}
