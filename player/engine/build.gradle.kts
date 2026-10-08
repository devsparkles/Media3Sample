// :player:engine = la SEULE couche qui connaît ExoPlayer/Media3.
// Elle traduit le monde métier (VideoContent, AdSchedule) en objets Media3 (MediaItem,
// AdPlaybackState) et inversement (PlaybackException -> PlayerError).
// Pour TV/tablette, ce module serait réutilisé tel quel : seule l'UI change.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.devsparkles.media3sample.player.engine"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Robolectric : les tests JVM qui instancient un vrai ExoPlayer ont besoin des ressources
    // Android simulées. https://developer.android.com/training/testing/local-tests/robolectric
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:domain"))

    // `api` : ExoPlayer/Player apparaissent dans l'API publique (PlayerSession.player)
    // et le module UI en a besoin pour brancher la PlayerView.
    api(libs.androidx.media3.exoplayer)
    // DASH : il suffit que le module soit dans le classpath. DefaultMediaSourceFactory le
    // détecte par réflexion et crée une DashMediaSource quand mimeType = application/dash+xml.
    implementation(libs.androidx.media3.exoplayer.dash)
    api(libs.androidx.media3.cast)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Test du PlayerEventTranslator avec un VRAI ExoPlayer (TestExoPlayerBuilder + FakeClock),
    // exécuté sous Robolectric (Looper Android simulé, sans émulateur).
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
    testImplementation(libs.androidx.junit)
}
