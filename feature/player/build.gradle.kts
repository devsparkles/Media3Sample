// :feature:player = couche PRÉSENTATION : écrans Compose + ViewModels.
// Ne connaît pas :core:data (elle reçoit des use cases déjà construits).
// Pour la TV on créerait :feature:player-tv (Compose for TV, navigation D-pad, focus)
// en réutilisant :player:engine et :core:domain sans modification.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.devsparkles.media3sample.feature.player"
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":player:engine"))

    // media3-ui : PlayerView (contrôles, sous-titres, marqueurs de pubs sur la timebar,
    // overlay pub). Media3 propose aussi media3-ui-compose (PlayerSurface 100 % Compose),
    // mais PlayerView reste la plus complète (et implémente AdViewProvider).
    implementation(libs.androidx.media3.ui)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
