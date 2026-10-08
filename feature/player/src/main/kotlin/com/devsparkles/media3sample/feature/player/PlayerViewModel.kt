package com.devsparkles.media3sample.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.AdViewProvider
import androidx.media3.common.DeviceInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.devsparkles.media3sample.core.domain.model.PlayerError
import com.devsparkles.media3sample.core.domain.usecase.GetPlayableContentUseCase
import com.devsparkles.media3sample.player.engine.PlayerFactory
import com.devsparkles.media3sample.player.engine.ads.AdUiInfo
import com.devsparkles.media3sample.player.engine.error.toPlayerError
import com.devsparkles.media3sample.player.engine.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * État unique de l'écran (pattern UDF : Unidirectional Data Flow).
 * L'UI ne fait que l'afficher ; elle envoie des événements au ViewModel (retry, skip...).
 * https://developer.android.com/topic/architecture/ui-layer
 */
data class PlayerUiState(
    val title: String = "",
    val isBuffering: Boolean = true,
    val ad: AdUiInfo? = null,
    val error: PlayerError? = null,
    /** Nom de l'appareil Cast (« Salon TV ») quand la lecture a lieu dessus, sinon null. */
    val castDevice: String? = null,
)

/**
 * RÔLE : posséder le player et exposer l'état de l'écran.
 *
 * CHOIX : le player vit dans le ViewModel.
 *  + il survit à la rotation (pas de rebuffering, pas de nouvelle requête de licence DRM) ;
 *  + release() garanti dans onCleared().
 *  - il ne survit pas à la sortie de l'écran : pour la lecture en arrière-plan / notification /
 *    Android Auto, on déplacerait le player dans un MediaSessionService (media3-session) et
 *    l'UI s'y connecterait via un MediaController.
 *    https://developer.android.com/media/media3/session/background-playback
 *
 * Note : ExoPlayer doit être utilisé depuis un seul thread (ici le main thread, car créé dessus).
 * https://developer.android.com/media/media3/exoplayer/hello-world#a-note-on-threading
 */
class PlayerViewModel(
    private val contentId: String,
    private val getPlayableContent: GetPlayableContentUseCase,
    playerFactory: PlayerFactory,
) : ViewModel() {

    private val session = playerFactory.create()

    /**
     * Exposé en tant que `Player` (interface) : l'UI n'a pas besoin des API ExoPlayer.
     * Avec le Cast, c'est un CastPlayer : la PlayerView pilote le téléphone OU la TV sans le savoir.
     */
    val player: Player get() = session.player

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var contentLoaded = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Cas particulier du LIVE : si on a trop longtemps été en pause, la position est
            // sortie de la fenêtre DVR -> on se recale sur le direct au lieu d'afficher une erreur.
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                session.player.seekToDefaultPosition()
                session.player.prepare()
                return
            }
            _uiState.update { it.copy(error = error.toPlayerError(), isBuffering = false) }
        }

        // Émis par le CastPlayer quand il change de player actif (local <-> TV).
        // https://developer.android.com/media/media3/cast/create-castplayer
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = updateCastDevice()
    }

    private fun updateCastDevice() {
        _uiState.update { it.copy(castDevice = session.remoteDeviceName()) }
    }

    init {
        session.player.addListener(playerListener)
        updateCastDevice() // une session Cast peut déjà être ouverte (on revient sur l'écran)
        viewModelScope.launch {
            session.adsLoader.currentAd.collect { ad -> _uiState.update { it.copy(ad = ad) } }
        }
        loadContent()
    }

    private fun loadContent() {
        viewModelScope.launch {
            try {
                val content = getPlayableContent(contentId)
                _uiState.update { it.copy(title = content.title) }
                // setMediaItem -> prepare -> playWhenReady : la séquence classique.
                // prepare() lance le chargement (manifest, licence, VMAP) ; playWhenReady = true
                // démarre la lecture dès que le buffer est suffisant (STATE_READY).
                session.player.setMediaItem(content.toMediaItem())
                session.player.prepare()
                session.player.playWhenReady = true
                contentLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = PlayerError.Source(e.message.orEmpty()), isBuffering = false) }
            }
        }
    }

    fun retry() {
        _uiState.update { it.copy(error = null, isBuffering = true) }
        // Après une erreur fatale le player est en STATE_IDLE mais garde sa playlist et sa
        // position : prepare() suffit pour reprendre là où on en était.
        if (contentLoaded) session.player.prepare() else loadContent()
    }

    fun skipAd() = session.adsLoader.skipCurrentAd()

    /** @return l'URL annonceur à ouvrir, ou null. */
    fun onAdClicked(): String? = session.adsLoader.onAdClicked()

    /** L'UI branche/débranche la PlayerView comme conteneur des pubs (OMID/SIMID). */
    fun attachAdViewProvider(provider: AdViewProvider?) {
        session.adViewProvider.delegate = provider
    }

    /**
     * App en arrière-plan (ON_STOP) : on met en pause (pas de lecture en background dans ce sample),
     * SAUF en Cast : la vidéo tourne sur la TV, quitter l'app ne doit pas l'interrompre.
     * Mesure d'audience : la pause SUSPEND la session (Nielsen : `stop()`, d'après les
     * « interruption scenarios » : « Call stop as soon as the app goes to background »), elle
     * ne la TERMINE pas. La fin (`end()`) a lieu à la libération du player (onCleared).
     */
    fun onBackground() {
        session.onAppBackground()
        if (!session.isRemote) session.player.pause()
    }

    /** Retour au premier plan (ON_START). La lecture reste en pause : l'utilisateur relance. */
    fun onForeground() {
        session.onAppForeground()
    }

    /**
     * Fermeture de session garantie ici, une seule fois : session.release() termine la mesure
     * d'audience AVANT de libérer le player (le traducteur est idempotent).
     * Les autres fins de session (erreur, y compris BEHIND_LIVE_WINDOW, fin du contenu) sont
     * détectées automatiquement par le traducteur : rien à appeler depuis ce ViewModel.
     */
    override fun onCleared() {
        session.player.removeListener(playerListener)
        session.release()
    }

    companion object {
        /**
         * Factory manuelle (sans Hilt) : le module :app fournit les dépendances.
         * Avec Hilt on écrirait @HiltViewModel + @Inject constructor et @AssistedInject
         * pour le contentId. Choix fait ici pour garder le sample lisible et sans kapt/ksp.
         */
        fun factory(
            contentId: String,
            getPlayableContent: GetPlayableContentUseCase,
            playerFactory: PlayerFactory,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PlayerViewModel(contentId, getPlayableContent, playerFactory) }
        }
    }
}
