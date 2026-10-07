import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:data = implémentations des interfaces du domaine (réseau, parsing XML, tracking).
// Également en Kotlin/JVM pur : le parsing VMAP/VAST utilise javax.xml (DOM), disponible
// À LA FOIS sur la JVM et sur Android -> le parser est testable sans émulateur ni Robolectric.
// (Alternative Android : XmlPullParser, plus rapide/streaming, mais ses classes sont des stubs
//  dans les tests JVM. Pour des documents de quelques Ko comme un VAST, DOM suffit largement.)
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
    // `api` (et non `implementation`) : les types du domaine apparaissent dans l'API publique
    // de ce module (ex : AdRepositoryImpl : AdRepository), donc les consommateurs en ont besoin.
    api(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
