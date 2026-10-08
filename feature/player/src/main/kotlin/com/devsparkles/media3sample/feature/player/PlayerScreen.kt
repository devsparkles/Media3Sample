package com.devsparkles.media3sample.feature.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.devsparkles.media3sample.core.domain.model.PlayerError
import com.devsparkles.media3sample.player.engine.ads.AdUiInfo

/**
 * RÔLE : écran de lecture.
 *
 * Choix technique : PlayerView (View classique) intégrée via AndroidView.
 *  - PlayerView gère : la Surface vidéo, les contrôles, les sous-titres, l'indicateur de
 *    buffering, les marqueurs de pubs sur la timebar, et sert de conteneur pub (AdViewProvider).
 *  - Alternative 100 % Compose : media3-ui-compose (PlayerSurface + états de boutons) ;
 *    plus flexible pour un design maison, mais il faut tout reconstruire.
 *    https://developer.android.com/media/media3/ui/compose
 *  - SurfaceView (défaut de PlayerView) > TextureView pour la vidéo : moins de batterie, et
 *    OBLIGATOIRE pour le contenu DRM sécurisé (Widevine L1 refuse TextureView, l'image
 *    serait noire). https://developer.android.com/media/media3/ui/playerview#surfacetype
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    // collectAsStateWithLifecycle : arrête la collecte quand l'écran n'est plus visible.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ON_STOP (et pas ON_PAUSE) : en multi-fenêtre / PiP l'activité est "paused" mais visible,
    // la vidéo doit continuer. https://developer.android.com/media/media3/exoplayer/hello-world
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.player
                    keepScreenOn = true // empêche la mise en veille pendant la lecture
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    viewModel.attachAdViewProvider(this)
                }
            },
            // Quand la vue quitte la composition : on détache le player de la Surface,
            // sinon fuite de la vue (et le player continuerait à y rendre).
            onRelease = { view ->
                view.player = null
                viewModel.attachAdViewProvider(null)
            },
        )

        // Bouton Cast (Compose, media3-cast) : ouvre la liste des appareils, puis le contrôleur
        // de la session. Masqué tant qu'aucun appareil n'est découvert sur le Wi-Fi.
        // Alternative View : playerView.setMediaRouteButtonViewProvider(MediaRouteButtonViewProvider())
        // le met dans les contrôles de la PlayerView, mais exige une FragmentActivity.
        MediaRouteButton(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp))

        state.castDevice?.let { device ->
            // En Cast, la PlayerView n'a plus de vidéo (c'est la TV qui décode) : elle reste
            // une télécommande (play/pause/seek passent par le CastPlayer).
            Text(
                text = "Lecture sur $device",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        state.ad?.let { ad ->
            AdOverlay(
                ad = ad,
                onSkip = viewModel::skipAd,
                onLearnMore = {
                    viewModel.onAdClicked()?.let { url ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: ActivityNotFoundException) {
                            // Pas de navigateur : on ignore.
                        }
                    }
                },
                modifier = Modifier.safeDrawingPadding(),
            )
        }

        state.error?.let { error ->
            ErrorOverlay(error = error, onRetry = viewModel::retry, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/**
 * Overlay pub : "Publicité 1/2 · 12 s", bouton "En savoir plus" et bouton "Passer".
 * Ces boutons recouvrent la vidéo : avec OMID il faudrait les déclarer comme
 * "friendly obstructions" (AdOverlayInfo, voir DeferredAdViewProvider).
 */
@Composable
private fun AdOverlay(ad: AdUiInfo, onSkip: () -> Unit, onLearnMore: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = "Publicité ${ad.adNumber}/${ad.adCount} · ${ad.remainingMs / 1000} s",
            color = Color.White,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (ad.hasClickThrough) OutlinedButton(onClick = onLearnMore) { Text("En savoir plus", color = Color.White) }
            else Box(Modifier)
            when {
                ad.canSkip -> Button(onClick = onSkip) { Text("Passer ▸") }
                ad.secondsBeforeSkip != null -> OutlinedButton(onClick = {}, enabled = false) {
                    Text("Passer dans ${ad.secondsBeforeSkip} s", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ErrorOverlay(error: PlayerError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val message = when (error) {
        is PlayerError.Network -> "Problème de connexion."
        is PlayerError.Drm -> "Ce contenu protégé ne peut pas être lu sur cet appareil."
        is PlayerError.Decoder -> "Format vidéo non supporté par cet appareil."
        is PlayerError.Source -> "Contenu indisponible."
        is PlayerError.Unknown -> "Une erreur est survenue."
    }
    Column(
        modifier.background(Color.Black.copy(alpha = 0.8f)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium)
        // Le détail technique (code ExoPlayer) : utile en debug/QA, on le masquerait en prod.
        Text(error.detail, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        if (error.isRetryable) Button(onClick = onRetry) { Text("Réessayer") }
    }
}
