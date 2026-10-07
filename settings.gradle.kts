pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Media3Sample"

// =====================================================================================
// Découpage en modules (Clean Architecture). Le sens des dépendances est TOUJOURS
// vers l'intérieur (vers :core:domain) :
//
//   :app ──► :feature:player ──► :player:engine ──► :core:domain ◄── :core:data
//     └──────────────────────────────────────────────────────────────────┘
//
// - :core:domain   (Kotlin pur, PAS d'Android) : modèles métier, interfaces, use cases.
// - :core:data     (Kotlin pur) : implémentations (réseau, parsing VMAP/VAST, tracking).
// - :player:engine (Android lib) : tout ce qui touche à Media3/ExoPlayer.
// - :feature:player(Android lib + Compose) : écran, ViewModel, état UI.
// - :app           : point d'entrée, assemble les dépendances (DI manuelle).
//
// Pourquoi ? - compilation incrémentale plus rapide (un module modifié = moins à recompiler)
//            - frontières explicites : l'UI ne peut PAS parler directement au parser VAST
//            - :core:* testables en JVM pur (tests rapides, sans émulateur)
//            - pour TV/tablette : on ajouterait :feature:player-tv qui réutilise engine/domain.
// Doc : https://developer.android.com/topic/modularization
// =====================================================================================
include(":app")
include(":core:domain")
include(":core:data")
include(":player:engine")
include(":feature:player")
