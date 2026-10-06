package com.devsparkles.media3sample

import android.app.Application
import com.devsparkles.media3sample.di.AppContainer

/**
 * RÔLE : créer le conteneur de dépendances une seule fois pour tout le process.
 * (Avec Hilt : @HiltAndroidApp sur cette classe.)
 */
class MediaSampleApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
