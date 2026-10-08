package com.devsparkles.media3sample

import android.app.Application
import com.devsparkles.media3sample.di.AppContainer
import com.devsparkles.media3sample.player.engine.cast.CastSupport

/**
 * RÔLE : créer le conteneur de dépendances une seule fois pour tout le process.
 * (Avec Hilt : @HiltAndroidApp sur cette classe.)
 */
class MediaSampleApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Google Cast : à initialiser ici, AVANT tout CastPlayer ou bouton Cast (doc de
        // androidx.media3.cast.Cast.initialize). Receiver par défaut = Default Media Receiver.
        CastSupport.initialize(this)
        container = AppContainer(this)
    }
}
