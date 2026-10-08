package com.devsparkles.media3sample.player.engine.cast

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastParams
import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.util.UnstableApi
import com.devsparkles.media3sample.player.engine.tracking.TechnicalMediaItem

/**
 * RÔLE : tout ce qui concerne Google Cast, isolé du reste du moteur.
 *
 * Modèle mental (doc : https://developer.android.com/media/media3/cast) :
 *
 *   PlayerView / ViewModel ──► CastPlayer (implémente Player, c'est un ForwardingPlayer)
 *                                 │  délègue au player ACTIF :
 *                                 ├── ExoPlayer local       (aucune session Cast)
 *                                 └── RemoteCastPlayer      (session Cast ouverte)
 *                                        │ commandes JSON via le Cast SDK
 *                                        ▼
 *                                 Receiver (app web sur la TV / Chromecast) : c'est LUI qui
 *                                 télécharge et décode le flux. Le téléphone n'est plus qu'une
 *                                 télécommande.
 *
 * Au basculement (CastPlayerImpl.updateActivePlayer, Media3 1.11.1) :
 *   1. transferCallback.transferState(ancien, nouveau)  -> copie playlist + position ;
 *   2. nouveau.prepare() si l'ancien n'était pas IDLE ;
 *   3. ancien.stop()  -> l'ExoPlayer local passe en IDLE (libère décodeurs et licence DRM).
 */
@OptIn(UnstableApi::class)
object CastSupport {

    /**
     * À appeler dans Application.onCreate(), sur le main thread, AVANT de créer un CastPlayer
     * ou d'afficher le bouton Cast. Initialisation ASYNCHRONE (le CastContext se charge en
     * arrière-plan) : sans Google Play Services (certains émulateurs), elle échoue silencieusement
     * et le bouton Cast ne trouve simplement aucun appareil.
     *
     * Préférée à la meta-data OPTIONS_PROVIDER_CLASS_NAME du manifest : d'après la KDoc de
     * Cast.initialize, les options du manifest activent la gestion automatique de la MediaSession
     * par le Cast SDK, incompatible avec celle de Media3.
     *
     * @param receiverAppId null = Default Media Receiver de Google (CC1AD845) : lit du DASH/HLS/MP4
     *  EN CLAIR, sans DRM ni pubs. Un player pro enregistre son propre receiver (CAF Web Receiver)
     *  sur https://cast.google.com/publish et met son ID ici.
     */
    fun initialize(context: Context, receiverAppId: String? = null) {
        val params = CastParams.Builder()
            .apply { receiverAppId?.let(::setReceiverApplicationId) }
            // Output Switcher Android 13+ : permet aussi de RAMENER la lecture de la TV vers le
            // téléphone depuis le panneau système (nécessite MediaTransferReceiver au manifest).
            .setRemoteToLocalEnabled(true)
            .build()
        Cast.getSingletonInstance(context).initialize(params)
    }

    /**
     * Enveloppe le player local dans un CastPlayer. Renvoie null si le Cast n'est pas utilisable
     * (initialize() pas appelé, process secondaire...) : l'app retombe alors sur l'ExoPlayer seul.
     */
    fun wrap(context: Context, localPlayer: Player, log: ((String) -> Unit)? = null): CastPlayer? =
        try {
            CastPlayer.Builder(context)
                .setLocalPlayer(localPlayer)
                .setTransferCallback(AdAwareTransferCallback(localPlayer, log))
                .build()
        } catch (e: IllegalStateException) {
            log?.invoke("Cast indisponible, lecture locale seulement : ${e.message}")
            null
        }

    /** Nom de la TV / enceinte (« Salon TV »), ou null si on lit en local. Main thread. */
    fun connectedDeviceName(context: Context): String? =
        Cast.getSingletonInstance(context).currentCastSession?.castDevice?.friendlyName

    /** Vrai si le player actif est distant : c'est l'information officielle (DeviceInfo). */
    fun isRemote(player: Player): Boolean = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
}

/**
 * RÔLE : copier l'état d'un player à l'autre au basculement local <-> Cast, en corrigeant
 * deux pièges du DefaultCastPlayerTransferCallback quand on fait de la pub côté client (CSAI) :
 *
 *  1. PENDANT UNE PUB, `currentPosition` est la position DANS LA PUB (ex : 5 s). Le défaut
 *     l'envoie telle quelle : la TV démarrerait le FILM à 5 s. On envoie `contentPosition`.
 *  2. RETOUR TV -> TÉLÉPHONE : les MediaItem reviennent du receiver reconstruits par
 *     DefaultMediaItemConverter, qui ne sérialise que uri/titre/mimeType/DRM. L'AdsConfiguration
 *     est perdue -> plus aucune pub. Or ExoPlayer.stop() GARDE sa playlist : on y récupère les
 *     MediaItem d'origine (même mediaId), avec leurs pubs et leurs headers DRM.
 *
 * Plus le filtre du défaut (items sans URI) et nos items techniques (« one pixel ») : ils
 * n'ont aucun sens sur une TV.
 */
@OptIn(UnstableApi::class)
internal class AdAwareTransferCallback(
    private val localPlayer: Player,
    private val log: ((String) -> Unit)? = null,
    private val isTechnical: (MediaItem) -> Boolean = TechnicalMediaItem::isTechnical,
) : CastPlayer.TransferCallback {

    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        val toLocal = targetPlayer === localPlayer
        val plan = planTransfer(
            items = sourcePlayer.mediaItems(),
            currentIndex = sourcePlayer.currentMediaItemIndex,
            positionMs = sourcePlayer.currentPosition,
            playingAd = sourcePlayer.isPlayingAd,
            contentPositionMs = sourcePlayer.contentPosition,
            originals = if (toLocal) targetPlayer.mediaItems() else emptyList(),
            isTechnical = isTechnical,
        )
        log?.invoke(
            "Cast : transfert ${if (toLocal) "TV -> téléphone" else "téléphone -> TV"}, " +
                "${plan.mediaItems.size} item(s), index ${plan.currentIndex}, position ${plan.positionMs} ms" +
                if (sourcePlayer.isPlayingAd) " (pub en cours : position du CONTENU)" else "",
        )
        PlayerTransferState.builderFromPlayer(sourcePlayer)
            .setMediaItems(plan.mediaItems)
            .setCurrentMediaItemIndex(plan.currentIndex)
            .setCurrentPosition(plan.positionMs)
            .build()
            .setToPlayer(targetPlayer)
    }

    private fun Player.mediaItems(): List<MediaItem> = List(mediaItemCount, ::getMediaItemAt)
}

internal data class TransferPlan(val mediaItems: List<MediaItem>, val currentIndex: Int, val positionMs: Long)

/**
 * Logique pure du transfert (testée sans Cast) : position, restauration, filtrage.
 * Même règle d'index que DefaultCastPlayerTransferCallback : si l'item courant est retiré, on
 * passe au suivant à la position 0 ; s'il ne reste rien, index = C.INDEX_UNSET (-1).
 */
internal fun planTransfer(
    items: List<MediaItem>,
    currentIndex: Int,
    positionMs: Long,
    playingAd: Boolean,
    contentPositionMs: Long,
    originals: List<MediaItem>,
    isTechnical: (MediaItem) -> Boolean,
): TransferPlan {
    val originalsById = originals.filter { it.mediaId != MediaItem.DEFAULT_MEDIA_ID }.associateBy { it.mediaId }
    var newIndex = currentIndex
    var currentRemoved = false
    val kept = mutableListOf<MediaItem>()
    items.forEachIndexed { i, item ->
        val uri = item.localConfiguration?.uri
        if (uri != null && uri != Uri.EMPTY && !isTechnical(item)) {
            kept += originalsById[item.mediaId] ?: item
        } else if (i < currentIndex) {
            newIndex--
        } else if (i == currentIndex) {
            currentRemoved = true
        }
    }
    if (kept.isEmpty()) return TransferPlan(emptyList(), C.INDEX_UNSET, 0)
    val position = when {
        currentRemoved -> 0
        playingAd -> contentPositionMs.coerceAtLeast(0)
        else -> positionMs
    }
    return TransferPlan(kept, newIndex.coerceIn(0, kept.lastIndex), position)
}
