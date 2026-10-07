import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:domain = module Kotlin/JVM PUR. Aucune dépendance Android, aucune dépendance Media3.
// C'est le cœur de la Clean Architecture : les règles métier ne dépendent d'aucun framework.
// Conséquence pratique : si demain on remplace ExoPlayer par un autre player, ce module
// ne bouge pas. Et ses tests tournent en millisecondes sur la JVM.
// Doc : https://developer.android.com/topic/architecture/domain-layer
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    // Seule dépendance : les coroutines (suspend fun dans les interfaces). C'est accepté
    // car les coroutines sont une lib Kotlin, pas un framework Android.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
