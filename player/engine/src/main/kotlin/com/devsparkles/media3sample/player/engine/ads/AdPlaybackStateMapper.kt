package com.devsparkles.media3sample.player.engine.ads

import androidx.annotation.OptIn
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.devsparkles.media3sample.core.domain.model.AdBreak
import com.devsparkles.media3sample.core.domain.model.AdBreakPosition
import com.devsparkles.media3sample.core.domain.model.AdSchedule

/**
 * RÔLE : convertir notre AdSchedule métier en AdPlaybackState, LA structure que comprend
 * ExoPlayer pour savoir où et quoi insérer comme pubs.
 *
 * AdPlaybackState (immuable, chaque `withXxx` renvoie une copie) =
 *   - une liste d'"ad groups" (= nos AdBreak) triée par position en MICROsecondes :
 *       0                      -> pre-roll
 *       15_000_000             -> mid-roll à 15 s
 *       C.TIME_END_OF_SOURCE   -> post-roll
 *   - pour chaque groupe : le nombre de pubs, leur MediaItem, leur durée, et leur ÉTAT :
 *       AVAILABLE (prête), PLAYED, SKIPPED, ERROR, UNAVAILABLE.
 * ExoPlayer construit alors une Timeline où les pubs sont des "périodes" à part,
 * et la PlayerView affiche automatiquement les marqueurs jaunes sur la barre de progression.
 * https://developer.android.com/reference/androidx/media3/common/AdPlaybackState
 *
 * Attention aux unités : Media3 utilise des MICROsecondes dans AdPlaybackState
 * (Util.msToUs), mais des MILLIsecondes dans l'API Player (currentPosition, duration).
 */
@OptIn(UnstableApi::class)
internal object AdPlaybackStateMapper {

    /** Renvoie les breaks dans l'ordre des ad groups (index identiques) + l'état Media3. */
    fun map(adsId: Any, schedule: AdSchedule): Pair<List<AdBreak>, AdPlaybackState> {
        val sorted = schedule.breaks.sortedBy { sortKey(it.position) }
        val groupTimesUs = sorted.map { timeUs(it.position) }.toLongArray()

        var state = AdPlaybackState(adsId, *groupTimesUs)
        sorted.forEachIndexed { groupIndex, adBreak ->
            state = state.withAdCount(groupIndex, adBreak.ads.size)
            adBreak.ads.forEachIndexed { adIndex, ad ->
                val mediaItem = MediaItem.Builder()
                    .setUri(ad.mediaFile.url)
                    .setMimeType(ad.mediaFile.mimeType)
                    .build()
                state = state
                    .withAvailableAdMediaItem(groupIndex, adIndex, mediaItem)
                    .withAdId(groupIndex, adIndex, ad.id)
            }
            val durationsUs = adBreak.ads
                .map { if (it.durationMs > 0) Util.msToUs(it.durationMs) else C.TIME_UNSET }
                .toLongArray()
            state = state.withAdDurationsUs(groupIndex, *durationsUs)
        }
        return sorted to state
    }

    private fun timeUs(position: AdBreakPosition): Long = when (position) {
        AdBreakPosition.PreRoll -> 0L
        is AdBreakPosition.MidRoll -> Util.msToUs(position.offsetMs)
        AdBreakPosition.PostRoll -> C.TIME_END_OF_SOURCE // = Long.MIN_VALUE, d'où sortKey()
    }

    private fun sortKey(position: AdBreakPosition): Long = when (position) {
        AdBreakPosition.PreRoll -> 0L
        is AdBreakPosition.MidRoll -> position.offsetMs
        AdBreakPosition.PostRoll -> Long.MAX_VALUE
    }
}
