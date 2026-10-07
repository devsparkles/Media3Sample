package com.devsparkles.media3sample.core.data.ads

import com.devsparkles.media3sample.core.data.ads.parser.TimeParser
import com.devsparkles.media3sample.core.data.ads.parser.VastParser
import com.devsparkles.media3sample.core.data.ads.parser.VastParser.TrackingData
import com.devsparkles.media3sample.core.data.ads.parser.VastParser.VastAd
import com.devsparkles.media3sample.core.data.ads.parser.VmapParser
import com.devsparkles.media3sample.core.data.network.HttpClient
import com.devsparkles.media3sample.core.domain.model.AdBreak
import com.devsparkles.media3sample.core.domain.model.AdBreakEvent
import com.devsparkles.media3sample.core.domain.model.AdBreakPosition
import com.devsparkles.media3sample.core.domain.model.AdSchedule
import com.devsparkles.media3sample.core.domain.model.LinearAd
import com.devsparkles.media3sample.core.domain.model.TrackingContext
import com.devsparkles.media3sample.core.domain.repository.AdRepository
import com.devsparkles.media3sample.core.domain.repository.AdTracker
import com.devsparkles.media3sample.core.domain.usecase.MediaFileSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * RÔLE : orchestrer toute la chaîne publicitaire jusqu'à un AdSchedule exploitable.
 *
 *   VMAP (Ad Proxy) ──► pour chaque AdBreak, EN PARALLÈLE :
 *        ├─ VAST embarqué ou téléchargé via AdTagURI
 *        └─ pour chaque <Ad> : si Wrapper -> on suit VASTAdTagURI (max [maxWrapperDepth] sauts)
 *                              en accumulant les URLs de tracking de chaque niveau
 *                           si InLine  -> on choisit le MediaFile et on construit un LinearAd
 *
 * Choix techniques à savoir justifier :
 *  - `coroutineScope { async {...} }.awaitAll()` : concurrence structurée. Les breaks sont
 *    résolus en parallèle ; si la coroutine parente est annulée (l'utilisateur quitte l'écran),
 *    toutes les requêtes filles sont annulées automatiquement.
 *    Doc : https://kotlinlang.org/docs/composing-suspending-functions.html#structured-concurrency-with-async
 *  - `withTimeoutOrNull` par break : une régie lente ne doit pas retarder le contenu.
 *  - On relance TOUJOURS CancellationException (sinon on casse l'annulation coopérative).
 *  - Robustesse : "les pubs ne doivent jamais empêcher la lecture du contenu". Toute erreur
 *    donne un break ignoré (+ ping d'erreur VAST), jamais un crash.
 *  - Profondeur max des Wrappers : la spec VAST recommande une limite (souvent 5) pour éviter
 *    les boucles infinies de redirection. Erreur VAST 302 si dépassée.
 */
class AdRepositoryImpl(
    private val http: HttpClient,
    private val tracker: AdTracker,
    private val vmapParser: VmapParser = VmapParser(),
    private val vastParser: VastParser = VastParser(),
    private val breakTimeoutMs: Long = 8_000,
    private val maxWrapperDepth: Int = 5,
) : AdRepository {

    override suspend fun loadAdSchedule(adTagUrl: String): AdSchedule = try {
        val xml = http.get(adTagUrl)
        val breaks = if (vmapParser.isVmap(xml)) {
            coroutineScope {
                vmapParser.parse(xml).breaks.map { async { resolveBreak(it) } }.awaitAll().filterNotNull()
            }
        } else {
            // Tolérance : si l'Ad Proxy renvoie directement un VAST, on le traite comme un pre-roll.
            listOfNotNull(buildBreak("preroll", AdBreakPosition.PreRoll, vastParser.parse(xml), emptyMap()))
        }
        AdSchedule(breaks)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AdSchedule.EMPTY
    }

    private suspend fun resolveBreak(vmapBreak: VmapParser.VmapAdBreak): AdBreak? {
        val position = TimeParser.parseBreakPosition(vmapBreak.timeOffset) ?: return null
        val breakId = vmapBreak.breakId ?: vmapBreak.timeOffset.orEmpty()
        return try {
            withTimeoutOrNull(breakTimeoutMs) {
                val vast = when {
                    vmapBreak.inlineVast != null -> vastParser.parse(vmapBreak.inlineVast)
                    vmapBreak.adTagUri != null -> vastParser.parse(http.get(vmapBreak.adTagUri))
                    else -> return@withTimeoutOrNull null
                }
                buildBreak(breakId, position, vast, vmapBreak.trackingEvents)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tracker.track(vmapBreak.trackingEvents[AdBreakEvent.ERROR].orEmpty(), TrackingContext(errorCode = VAST_ERROR_XML_PARSING))
            null
        }
    }

    private suspend fun buildBreak(
        id: String,
        position: AdBreakPosition,
        vast: VastParser.VastDocument,
        breakTracking: Map<AdBreakEvent, List<String>>,
    ): AdBreak? {
        val ads = vast.ads.flatMap { resolveAd(it, TrackingData(), depth = 0) }
        if (ads.isEmpty()) {
            // "No ad" : la spec demande d'appeler les <Error> racine avec le code 303.
            tracker.track(vast.errorUrls, TrackingContext(errorCode = VAST_ERROR_NO_ADS))
            return null
        }
        return AdBreak(id = id, position = position, ads = ads, trackingEvents = breakTracking)
    }

    /** Résolution récursive des Wrappers, avec accumulation du tracking de chaque niveau. */
    private suspend fun resolveAd(ad: VastAd, inherited: TrackingData, depth: Int): List<LinearAd> {
        val tracking = inherited + ad.tracking
        return when (ad) {
            is VastAd.InLine -> listOfNotNull(toLinearAd(ad, tracking))
            is VastAd.Wrapper -> {
                if (depth >= maxWrapperDepth) {
                    tracker.track(tracking.errorUrls, TrackingContext(errorCode = VAST_ERROR_WRAPPER_LIMIT))
                    return emptyList()
                }
                val child = runCatching { vastParser.parse(http.get(ad.vastAdTagUri)) }.getOrElse {
                    if (it is CancellationException) throw it
                    tracker.track(tracking.errorUrls, TrackingContext(errorCode = VAST_ERROR_WRAPPER_TIMEOUT))
                    return emptyList()
                }
                child.ads.flatMap { resolveAd(it, tracking, depth + 1) }
            }
        }
    }

    private fun toLinearAd(ad: VastAd.InLine, tracking: TrackingData): LinearAd? {
        val mediaFile = MediaFileSelector.select(ad.mediaFiles) ?: run {
            // 403 = "Couldn't find MediaFile that is supported by this video player".
            tracker.track(tracking.errorUrls, TrackingContext(errorCode = VAST_ERROR_NO_SUPPORTED_MEDIA))
            return null
        }
        return LinearAd(
            id = ad.id,
            sequence = ad.sequence,
            durationMs = ad.durationMs,
            mediaFile = mediaFile,
            skipOffsetMs = TimeParser.parseOffsetMs(ad.skipOffset, ad.durationMs),
            clickThroughUrl = ad.clickThroughUrl,
            impressionUrls = tracking.impressionUrls,
            errorUrls = tracking.errorUrls,
            trackingEvents = tracking.events,
        )
    }

    companion object {
        // Codes d'erreur VAST normalisés (section "VAST Error Codes" de la spec IAB).
        const val VAST_ERROR_XML_PARSING = 100
        const val VAST_ERROR_WRAPPER_TIMEOUT = 301
        const val VAST_ERROR_WRAPPER_LIMIT = 302
        const val VAST_ERROR_NO_ADS = 303
        const val VAST_ERROR_NO_SUPPORTED_MEDIA = 403
        const val VAST_ERROR_MEDIA_PLAYBACK = 405
    }
}
