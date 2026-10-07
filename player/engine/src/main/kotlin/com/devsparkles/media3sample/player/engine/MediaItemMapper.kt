package com.devsparkles.media3sample.player.engine

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.devsparkles.media3sample.core.domain.model.DrmConfig
import com.devsparkles.media3sample.core.domain.model.DrmScheme
import com.devsparkles.media3sample.core.domain.model.StreamType
import com.devsparkles.media3sample.core.domain.model.VideoContent

/**
 * RÔLE : traduire le modèle métier VideoContent en MediaItem Media3.
 *
 * MediaItem = DESCRIPTION de ce qu'on veut lire (URI, type, DRM, pubs, métadonnées, sous-titres).
 * Ce n'est PAS l'objet qui lit : c'est la MediaSourceFactory qui en fabrique une MediaSource.
 * https://developer.android.com/media/media3/exoplayer/media-items
 */
fun VideoContent.toMediaItem(): MediaItem {
    val builder = MediaItem.Builder()
        .setMediaId(id)
        .setUri(streamUrl)
        // Le mimeType évite à ExoPlayer de deviner le format via l'extension de l'URL
        // (indispensable quand l'URL du manifest n'a pas d'extension, ex : /manifest?id=123).
        .setMimeType(
            when (streamType) {
                StreamType.DASH -> MimeTypes.APPLICATION_MPD
                StreamType.HLS -> MimeTypes.APPLICATION_M3U8
                StreamType.PROGRESSIVE -> null
            },
        )
        // Les métadonnées servent à la PlayerView, à la MediaSession (notification,
        // Android Auto, Google Assistant) et au Cast.
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())

    drm?.let { builder.setDrmConfiguration(it.toDrmConfiguration()) }

    adTagUrl?.let { url ->
        // AdsConfiguration -> DefaultMediaSourceFactory enveloppe la source dans une
        // AdsMediaSource qui appellera notre VmapAdsLoader.start(adTagDataSpec = url).
        // adsId : identifie l'état des pubs. Même adsId = ExoPlayer réutilise le même
        // AdPlaybackState (ex : les pubs déjà vues ne sont pas rejouées après un retry).
        builder.setAdsConfiguration(
            MediaItem.AdsConfiguration.Builder(Uri.parse(url))
                .setAdsId(id)
                .build(),
        )
    }
    return builder.build()
}

/**
 * DrmConfiguration Media3. Points clés :
 *  - UUID du schéma : C.WIDEVINE_UUID (edef8ba9-...), identifiant standard du système DRM,
 *    le même que dans le <ContentProtection schemeIdUri="urn:uuid:edef8ba9-..."> du MPD.
 *  - licenseUri : si absent, ExoPlayer utilise l'URL éventuellement présente dans le manifest.
 *  - setMultiSession : nécessaire en cas de rotation de clés / clés différentes par piste.
 *  - setPlayClearContentWithoutKey(true) : permet de démarrer la lecture des parties en clair
 *    (ex : intro non chiffrée) pendant que la licence arrive -> démarrage plus rapide.
 *  - Licences hors-ligne (téléchargement) : setKeySetId(...) + OfflineLicenseHelper.
 * https://developer.android.com/reference/androidx/media3/common/MediaItem.DrmConfiguration
 */
private fun DrmConfig.toDrmConfiguration(): MediaItem.DrmConfiguration {
    val uuid = when (scheme) {
        DrmScheme.WIDEVINE -> C.WIDEVINE_UUID
        DrmScheme.PLAYREADY -> C.PLAYREADY_UUID
        DrmScheme.CLEARKEY -> C.CLEARKEY_UUID
    }
    return MediaItem.DrmConfiguration.Builder(uuid)
        .setLicenseUri(licenseUrl)
        .setLicenseRequestHeaders(requestHeaders)
        .setMultiSession(multiSession)
        .setPlayClearContentWithoutKey(true)
        .build()
}
