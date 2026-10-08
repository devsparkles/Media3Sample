package com.devsparkles.media3sample.player.engine.tracking

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * RÔLE : marquer les `MediaItem` TECHNIQUES, qui ne sont pas du contenu à mesurer.
 *
 * Cas réel (« bug du one pixel ») : pour démarrer la playlist d'ExoPlayer quand il n'y a pas
 * de pre-roll, on place en tête un `MediaItem` d'une vidéo d'un pixel, très courte. Pour le
 * player, c'est un média comme un autre : sans marquage, le tracking ouvre une session de mesure
 * sur ce faux asset (Nielsen : play + loadMetadata), puis, au passage au vrai programme, émet
 * un changement de contenu (Nielsen : end, play, loadMetadata). La mesure est polluée par un
 * asset fantôme, et la session du vrai programme ne commence pas proprement.
 *
 * Correction : le traducteur ne voit pas les items techniques (content = null) : aucune session
 * ne s'ouvre dessus, et la session du programme démarre normalement sur le premier VRAI item.
 * Le marquage est porté par le MediaItem lui-même (extras de MediaMetadata), donc il voyage
 * avec l'item (playlist, MediaSession, Cast) au lieu de dépendre d'un index de playlist.
 */
object TechnicalMediaItem {

    const val EXTRA_TECHNICAL = "com.devsparkles.media3sample.TECHNICAL_ITEM"

    /** Construit un item technique (ex : la vidéo « one pixel »). */
    fun create(uri: String, mediaId: String = "technical:$uri"): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(mediaId)
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putBoolean(EXTRA_TECHNICAL, true) }).build())
        .build()

    fun isTechnical(item: MediaItem): Boolean = item.mediaMetadata.extras?.getBoolean(EXTRA_TECHNICAL) == true
}
