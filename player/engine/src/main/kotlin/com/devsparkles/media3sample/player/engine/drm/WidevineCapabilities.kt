package com.devsparkles.media3sample.player.engine.drm

import android.media.MediaDrm
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi

/**
 * RÔLE : interroger le CDM Widevine de l'appareil (diagnostic / choix de qualité).
 *
 *  - securityLevel "L1" : déchiffrement ET décodage dans le TEE (zone sécurisée du SoC).
 *    Exigé par la plupart des ayants droit pour la HD/4K.
 *  - "L3" : tout en logiciel (émulateurs, appareils non certifiés, root) -> souvent SD max.
 *  - maxHdcpLevel : protection de la sortie HDMI (pertinent pour TV / Cast / écran externe).
 *
 * Usage typique : envoyer ces infos au backend pour qu'il renvoie un manifest adapté, ou
 * limiter la résolution côté TrackSelector (setMaxVideoSize) si L3.
 * https://developer.android.com/reference/android/media/MediaDrm
 */
object WidevineCapabilities {

    data class Info(val securityLevel: String, val maxHdcpLevel: String?, val systemId: String?)

    @OptIn(UnstableApi::class)
    fun read(): Info? = runCatching {
        val mediaDrm = MediaDrm(C.WIDEVINE_UUID)
        try {
            Info(
                securityLevel = mediaDrm.getPropertyString("securityLevel"),
                maxHdcpLevel = runCatching { mediaDrm.getPropertyString("maxHdcpLevel") }.getOrNull(),
                systemId = runCatching { mediaDrm.getPropertyString("systemId") }.getOrNull(),
            )
        } finally {
            if (Build.VERSION.SDK_INT >= 28) mediaDrm.close() else @Suppress("DEPRECATION") mediaDrm.release()
        }
    }.getOrNull()
}
