package com.devsparkles.media3sample.core.domain.model

/**
 * RÔLE : erreurs de lecture exprimées en langage MÉTIER.
 *
 * ExoPlayer remonte des `PlaybackException` avec des dizaines de codes
 * (ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, ERROR_CODE_IO_NETWORK_CONNECTION_FAILED...).
 * L'UI n'a pas à connaître ces codes : :player:engine les traduit vers ce sealed type
 * (voir PlaybackErrorMapper). L'UI décide alors : bouton "Réessayer", message, etc.
 */
sealed interface PlayerError {
    val detail: String
    val isRetryable: Boolean

    /** Pas de réseau, timeout, HTTP 5xx... -> on peut réessayer. */
    data class Network(override val detail: String) : PlayerError {
        override val isRetryable = true
    }

    /** Licence refusée, appareil non certifié, clé expirée... */
    data class Drm(override val detail: String) : PlayerError {
        override val isRetryable = false
    }

    /** Format non supporté par l'appareil, décodeur en échec... */
    data class Decoder(override val detail: String) : PlayerError {
        override val isRetryable = false
    }

    /** Manifest invalide ou contenu introuvable (404). */
    data class Source(override val detail: String) : PlayerError {
        override val isRetryable = true
    }

    data class Unknown(override val detail: String) : PlayerError {
        override val isRetryable = true
    }
}
