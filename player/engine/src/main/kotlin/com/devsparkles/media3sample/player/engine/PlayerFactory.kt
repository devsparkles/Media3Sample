package com.devsparkles.media3sample.player.engine

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.EventLogger
import com.devsparkles.media3sample.core.domain.repository.AdTracker
import com.devsparkles.media3sample.core.domain.tracking.CompositeTracker
import com.devsparkles.media3sample.core.domain.tracking.PlaybackTracker
import com.devsparkles.media3sample.core.domain.tracking.dispatchTo
import com.devsparkles.media3sample.core.domain.usecase.LoadAdScheduleUseCase
import com.devsparkles.media3sample.player.engine.ads.DeferredAdViewProvider
import com.devsparkles.media3sample.player.engine.ads.VmapAdsLoader
import com.devsparkles.media3sample.player.engine.analytics.PlaybackAnalyticsLogger
import com.devsparkles.media3sample.player.engine.tracking.PlayerEventTranslator

/**
 * RÔLE : construire et configurer une instance d'ExoPlayer prête pour DASH + Widevine + pubs.
 *
 * C'est LE fichier à savoir expliquer en entretien. Vue d'ensemble de l'architecture ExoPlayer :
 *
 *   MediaItem ──► MediaSourceFactory ──► MediaSource (DashMediaSource, AdsMediaSource...)
 *                        │                     │  télécharge via DataSource (HTTP)
 *                        │                     ▼
 *                        │              Timeline + MediaPeriods (les "morceaux" à lire)
 *                        ▼                     │
 *                 DrmSessionManager            ▼
 *                 (licences Widevine)    LoadControl : QUAND charger (taille du buffer)
 *                                        TrackSelector : QUELLE qualité/langue charger
 *                                              │
 *                                              ▼
 *                                        Renderers (vidéo, audio, texte) -> MediaCodec -> Surface
 *
 * Doc d'ensemble : https://developer.android.com/media/media3/exoplayer/customization
 *
 * @OptIn(UnstableApi::class) : beaucoup d'API avancées de Media3 sont marquées "unstable"
 * (elles peuvent changer entre versions mineures). C'est normal d'en utiliser dans un player
 * pro, mais l'opt-in rend ce choix explicite.
 * https://developer.android.com/media/media3/exoplayer/troubleshooting#unstable-api
 */
@OptIn(UnstableApi::class)
class PlayerFactory(
    private val context: Context,
    private val loadAdSchedule: LoadAdScheduleUseCase,
    private val adTracker: AdTracker,
    private val userAgent: String,
    /** Outils de mesure d'audience, construits dans AppContainer (DI manuelle). */
    private val playbackTrackers: List<PlaybackTracker> = emptyList(),
    private val enableDebugLogs: Boolean = true,
) {

    fun create(): PlayerSession {
        // --- 1. DataSource : COMMENT on télécharge (HTTP) ------------------------------------
        // DefaultHttpDataSource = HttpURLConnection. Alternatives : OkHttpDataSource (extension
        // media3-datasource-okhttp) pour partager le client OkHttp de l'app, ou CronetDataSource
        // (HTTP/3, QUIC) très utilisé par les gros acteurs du streaming.
        // DefaultDataSource enveloppe le HTTP et gère aussi file://, asset://, content://...
        // https://developer.android.com/media/media3/exoplayer/network-stacks
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true) // CDN qui redirige http -> https
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        // --- 2. DRM ----------------------------------------------------------------------------
        // DefaultDrmSessionManagerProvider crée, pour chaque MediaItem qui a une
        // DrmConfiguration, un DefaultDrmSessionManager qui :
        //   - ouvre une session MediaDrm (API Android du CDM Widevine),
        //   - envoie la requête de licence en POST via `httpDataSourceFactory` (même stack HTTP,
        //     donc mêmes timeouts/headers/logs que les segments vidéo).
        // https://developer.android.com/media/media3/exoplayer/drm
        val drmSessionManagerProvider = DefaultDrmSessionManagerProvider().apply {
            setDrmHttpDataSourceFactory(httpDataSourceFactory)
        }

        // --- 3. Pubs (insertion côté client, "CSAI") --------------------------------------------
        // Notre VmapAdsLoader est branché via setLocalAdInsertionComponents : pour tout MediaItem
        // ayant une AdsConfiguration, DefaultMediaSourceFactory enveloppe la MediaSource du
        // contenu dans une AdsMediaSource qui interroge notre AdsLoader.
        // (L'alternative clé-en-main est l'extension IMA : media3-exoplayer-ima / ImaAdsLoader,
        //  qui parse VAST/VMAP et fait OMID pour vous. Ici on le fait à la main pour comprendre
        //  et pour garder la main sur un Ad Proxy maison.)
        // https://developer.android.com/media/media3/exoplayer/ad-insertion
        val adsLoader = VmapAdsLoader(
            loadAdSchedule = { url -> loadAdSchedule(url) },
            tracker = adTracker,
        )
        val adViewProvider = DeferredAdViewProvider()

        // --- 4. MediaSourceFactory : MediaItem -> MediaSource ----------------------------------
        // DefaultMediaSourceFactory choisit la bonne MediaSource selon le mimeType/l'extension :
        // DashMediaSource (.mpd), HlsMediaSource (.m3u8), ProgressiveMediaSource (.mp4)...
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setDrmSessionManagerProvider(drmSessionManagerProvider)
            .setLocalAdInsertionComponents({ adsLoader }, adViewProvider)
            // Nombre de retries sur erreur de chargement d'un segment avant de remonter
            // une PlaybackException (défaut : 3). En mobilité (métro...), on est plus tolérant.
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 5))

        // --- 5. LoadControl : politique de BUFFERING --------------------------------------------
        //  minBufferMs           : en dessous, on charge toujours.
        //  maxBufferMs           : au-dessus, on arrête de charger (économie data/mémoire).
        //  bufferForPlaybackMs   : buffer nécessaire pour DÉMARRER la lecture (latence de start).
        //  bufferForPlaybackAfterRebufferMs : buffer nécessaire pour REPARTIR après un rebuffer
        //                          (plus grand, pour éviter d'enchaîner les micro-coupures).
        // Compromis : start rapide vs. risque de rebuffering. Sur TV (réseau stable, RAM
        // limitée) on baisserait maxBufferMs ; sur mobile 4G on garde de la marge.
        // https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .build()

        // --- 6. TrackSelector : choix des pistes (qualité vidéo, langue audio, sous-titres) ----
        // L'ABR (adaptive bitrate) est fait par AdaptiveTrackSelection : il estime la bande
        // passante (DefaultBandwidthMeter) et choisit la Representation DASH adaptée.
        // On peut contraindre : setMaxVideoSizeSd() en data mobile, setForceHighestSupportedBitrate...
        // https://developer.android.com/media/media3/exoplayer/track-selection
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage("fr")
                    .setPreferredTextLanguage("fr"),
            )
        }

        // --- 7. Le player ------------------------------------------------------------------
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            // handleAudioFocus = true : ExoPlayer se met en pause/baisse le son quand une autre
            // app (appel, GPS...) prend le focus audio. Obligatoire pour une app vidéo propre.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pause automatique quand on débranche le casque (intent AUDIO_BECOMING_NOISY).
            .setHandleAudioBecomingNoisy(true)
            // Garde le CPU + Wi-Fi éveillés pendant la lecture (lecture en arrière-plan).
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        // L'AdsLoader DOIT connaître le player avant prepare() (il écoute ses événements pour
        // le tracking). Règle imposée par AdsMediaSource.
        adsLoader.setPlayer(player)

        // --- 8. Observabilité -------------------------------------------------------------
        // AnalyticsListener reçoit TOUS les événements internes (chargements, formats, DRM,
        // frames perdues...) avec un EventTime. EventLogger = implémentation de debug fournie
        // par Media3 qui logue tout dans Logcat (tag "EventLogger") : premier réflexe en debug.
        // https://developer.android.com/media/media3/exoplayer/analytics
        player.addAnalyticsListener(PlaybackAnalyticsLogger())
        if (enableDebugLogs) player.addAnalyticsListener(EventLogger())

        // --- 9. Mesure d'audience (Nielsen, outil maison...) ----------------------------------
        // DISTINCT du tracking pub (AdTracker / pixels VAST, branché dans VmapAdsLoader).
        // Le traducteur écoute le player, la state machine du domaine produit des événements
        // normalisés, et le CompositeTracker les diffuse à chaque outil, isolé des autres.
        val audience = CompositeTracker(playbackTrackers) { tracker, error ->
            Log.e(TRACKING_TAG, "tracker ${tracker.name} en erreur (ignorée, la lecture continue)", error)
        }
        val translator = PlayerEventTranslator(
            player = player,
            adIdProvider = adsLoader::adIdAt,
            log = if (enableDebugLogs) { message -> Log.d(TRACKING_TAG, message) } else null,
            sink = { event -> event.dispatchTo(audience) },
        )

        return PlayerSession(player, adsLoader, adViewProvider, translator, audience)
    }

    companion object {
        /** Logcat : timeline lisible « callback Media3 → événement normalisé → appel SDK ». */
        const val TRACKING_TAG = "PlaybackTracking"
    }
}

/**
 * RÔLE : regrouper ce qui vit et meurt avec un player (player + ads loader + vue pub),
 * pour libérer le tout proprement en un appel.
 *
 * Pourquoi release() est critique : un ExoPlayer non libéré garde des décodeurs matériels
 * (MediaCodec) et des sessions DRM ouverts. Ces ressources sont LIMITÉES (parfois 1 seul
 * décodeur sécurisé Widevine L1 sur une TV) -> le player suivant échouera.
 */
class PlayerSession internal constructor(
    val player: ExoPlayer,
    val adsLoader: VmapAdsLoader,
    val adViewProvider: DeferredAdViewProvider,
    private val tracking: PlayerEventTranslator,
    private val audience: PlaybackTracker,
) {
    private var inBackground = false

    /**
     * App en arrière-plan. Nielsen : `appInBackground()` (cf. NielsenTracker). La pause du player
     * qui suit (ViewModel.onBackground) produit l'événement Paused -> `stop()` côté Nielsen.
     */
    fun onAppBackground() {
        if (inBackground) return
        inBackground = true
        audience.onAppBackground()
    }

    fun onAppForeground() {
        if (!inBackground) return
        inBackground = false
        audience.onAppForeground()
    }

    fun release() {
        // AVANT player.release() : la fin de session lit la dernière position du player.
        tracking.release()
        adsLoader.setPlayer(null)
        player.release()
        adsLoader.release()
    }
}
