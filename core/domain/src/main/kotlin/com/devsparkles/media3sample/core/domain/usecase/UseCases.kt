package com.devsparkles.media3sample.core.domain.usecase

import com.devsparkles.media3sample.core.domain.model.AdSchedule
import com.devsparkles.media3sample.core.domain.model.MediaFile
import com.devsparkles.media3sample.core.domain.model.VideoContent
import com.devsparkles.media3sample.core.domain.repository.AdRepository
import com.devsparkles.media3sample.core.domain.repository.ContentRepository

/**
 * RÔLE des Use Cases : porter UNE action métier, avec ses règles.
 * Convention : une classe = une action, exposée via `operator fun invoke` pour l'appeler
 * comme une fonction : `getPlayableContent("id")`.
 * Doc : https://developer.android.com/topic/architecture/domain-layer
 */
class GetPlayableContentUseCase(private val repository: ContentRepository) {
    suspend operator fun invoke(contentId: String): VideoContent = repository.getContent(contentId)
}

class GetCatalogUseCase(private val repository: ContentRepository) {
    suspend operator fun invoke(): List<VideoContent> = repository.getCatalog()
}

/**
 * Charge le planning pub et applique les RÈGLES MÉTIER :
 *  - on retire les breaks vides (VAST "no fill" = la régie n'avait pas de pub à servir) ;
 *  - on trie les pubs d'un pod par `sequence` (ordre imposé par le VAST) ;
 *  - un seul pre-roll et un seul post-roll max (règle produit fictive, typique des équipes pub).
 */
class LoadAdScheduleUseCase(private val repository: AdRepository) {
    suspend operator fun invoke(adTagUrl: String): AdSchedule {
        val schedule = repository.loadAdSchedule(adTagUrl)
        val cleaned = schedule.breaks
            .filter { it.ads.isNotEmpty() }
            .map { it.copy(ads = it.ads.sortedBy { ad -> ad.sequence }) }
            .distinctBy { it.position }
        return AdSchedule(cleaned)
    }
}

/**
 * Règle métier : choisir le meilleur fichier vidéo parmi ceux proposés par le VAST.
 *
 * Un VAST propose souvent : mp4 360p/720p/1080p, webm, parfois un VPAID (JavaScript, NON
 * supporté nativement par ExoPlayer) ou du HLS. On veut :
 *  1. un type supporté par le player,
 *  2. le bitrate le plus proche (sans dépasser) de la cible, pour ne pas faire rebuffer la pub
 *     (une pub qui buffer = mauvaise expérience + taux de complétion en baisse).
 */
object MediaFileSelector {
    private val SUPPORTED_MIME_TYPES = setOf("video/mp4", "video/webm", "application/x-mpegURL", "application/dash+xml")

    fun select(candidates: List<MediaFile>, targetBitrateKbps: Int = 2_000): MediaFile? {
        val supported = candidates.filter { it.mimeType in SUPPORTED_MIME_TYPES }
        if (supported.isEmpty()) return null
        val withBitrate = supported.filter { it.bitrateKbps != null }
        if (withBitrate.isEmpty()) return supported.maxByOrNull { it.width * it.height }
        return withBitrate.filter { it.bitrateKbps!! <= targetBitrateKbps }.maxByOrNull { it.bitrateKbps!! }
            ?: withBitrate.minByOrNull { it.bitrateKbps!! }
    }
}
