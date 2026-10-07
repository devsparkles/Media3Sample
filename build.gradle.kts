// Fichier de build racine : on DÉCLARE les plugins (apply false) pour que chaque module
// les applique ensuite avec la même version. Aucune logique de build ici.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
