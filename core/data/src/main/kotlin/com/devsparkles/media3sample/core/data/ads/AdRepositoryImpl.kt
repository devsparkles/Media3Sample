package com.devsparkles.media3sample.core.data.ads

import com.devsparkles.media3sample.core.data.ads.macro.MacroExpander
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
 *  - `withTimeoutOrNull` par break et par Wrapper : une régie lente ne retarde pas le contenu.
 *  - On relance TOUJOURS CancellationException (sinon on casse l'annulation coopérative).
 *  - Robustesse : "les pubs ne doivent jamais empêcher la lecture du contenu". Toute erreur
 *    donne un break ignoré (+ ping d'erreur VAST + log), jamais un crash.
 *  - Macros dans les URLs de REQUÊTE (tag VMAP, AdTagURI, VASTAdTagURI) : remplacées avant
 *    l'appel, car VAST 4.1 §6.1 confie le remplacement à « la partie qui fait la requête ».
 *  - Wrappers (VAST 4.1 §2.3.5.1) : 5 au maximum, sinon erreur 302 envoyée à TOUS les
 *    wrappers de la chaîne (« Error codes should be sent for all wrappers in the chain »).
 */
class AdRepositoryImpl(
    private val http: HttpClient,
    private val tracker: AdTracker,
    private val vmapParser: VmapParser = VmapParser(),
    private val vastParser: VastParser = VastParser(),
    private val macroExpander: MacroExpander = MacroExpander(),
    private val breakTimeoutMs: Long = 8_000,
    private val wrapperTimeoutMs: Long = 4_000,
    private val maxWrapperDepth: Int = 5,
    private val logger: (String) -> Unit = {},
) : AdRepository {

    override suspend fun loadAdSchedule(adTagUrl: String): AdSchedule = try {
        val xml = http.get(macroExpander.expand(adTagUrl))
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
        // Observabilité : on ne bloque pas le contenu, mais on garde une trace de la cause.
        logger("ad schedule failed for $adTagUrl : ${e::class.simpleName} ${e.message}")
        AdSchedule.EMPTY
    }

    private suspend fun resolveBreak(vmapBreak: VmapParser.VmapAdBreak): AdBreak? {
        val position = TimeParser.parseBreakPosition(vmapBreak.timeOffset) ?: return null
        val breakId = vmapBreak.breakId ?: vmapBreak.timeOffset.orEmpty()
        return try {
            val result = withTimeoutOrNull(breakTimeoutMs) {
                val vast = when {
                    vmapBreak.inlineVast != null -> vastParser.parse(vmapBreak.inlineVast)
                    vmapBreak.adTagUri != null -> vastParser.parse(http.get(macroExpander.expand(vmapBreak.adTagUri)))
                    else -> return@withTimeoutOrNull null
                }
                buildBreak(breakId, position, vast, vmapBreak.trackingEvents)
            }
            if (result == null) logger("ad break $breakId dropped (timeout or no ad)")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("ad break $breakId failed : ${e::class.simpleName} ${e.message}")
            // Note : VMAP a ses propres codes d'erreur (spec VMAP 1.0) ; on réutilise ici le
            // code VAST 100 pour simplifier.
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
            // "No ad" : on appelle les <Error> racine avec le code 303.
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
                val child = fetchWrapped(ad.vastAdTagUri, tracking) ?: return emptyList()
                child.ads.flatMap { resolveAd(it, tracking, depth + 1) }
            }
        }
    }

    /**
     * Télécharge et parse le VAST pointé par un Wrapper. En cas d'échec, envoie le code VAST
     * précis (tableau §2.3.6.3) à toutes les <Error> accumulées de la chaîne :
     *  - 301 : timeout de l'URI du Wrapper ;
     *  - 300 : erreur générale de Wrapper (HTTP 404, réseau...) ;
     *  - 100 : XML illisible.
     */
    private suspend fun fetchWrapped(uri: String, tracking: TrackingData): VastParser.VastDocument? {
        val xml = try {
            withTimeoutOrNull(wrapperTimeoutMs) { http.get(macroExpander.expand(uri)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("wrapper request failed $uri : ${e.message}")
            tracker.track(tracking.errorUrls, TrackingContext(errorCode = VAST_ERROR_WRAPPER_GENERAL))
            return null
        }
        if (xml == null) {
            logger("wrapper request timed out $uri")
            tracker.track(tracking.errorUrls, TrackingContext(errorCode = VAST_ERROR_WRAPPER_TIMEOUT))
            return null
        }
        return try {
            vastParser.parse(xml)
        } catch (e: Exception) {
            logger("wrapper response is not valid VAST $uri : ${e.message}")
            tracker.track(tracking.errorUrls, TrackingContext(errorCode = VAST_ERROR_XML_PARSING))
            null
        }
    }

    private fun toLinearAd(ad: VastAd.InLine, tracking: TrackingData): LinearAd? {
        val mediaFile = MediaFileSelector.select(ad.mediaFiles) ?: run {
            // 403 = "Couldn't find MediaFile that is supported by this media player".
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
        // Codes d'erreur VAST 4.1, tableau §2.3.6.3.
        const val VAST_ERROR_XML_PARSING = 100
        const val VAST_ERROR_WRAPPER_GENERAL = 300
        const val VAST_ERROR_WRAPPER_TIMEOUT = 301
        const val VAST_ERROR_WRAPPER_LIMIT = 302
        const val VAST_ERROR_NO_ADS = 303
        const val VAST_ERROR_NO_SUPPORTED_MEDIA = 403
        const val VAST_ERROR_MEDIA_PLAYBACK = 405
    }
}
