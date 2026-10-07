package com.devsparkles.media3sample.core.data.ads

import com.devsparkles.media3sample.core.data.ads.macro.MacroExpander
import com.devsparkles.media3sample.core.data.network.HttpClient
import com.devsparkles.media3sample.core.domain.model.TrackingContext
import com.devsparkles.media3sample.core.domain.repository.AdTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * RÔLE : envoyer les pixels de tracking VAST (impression, quartiles, complete, skip, erreurs).
 *
 * - "Fire and forget" : on lance une coroutine dans un scope APPLICATIF (pas celui de l'écran),
 *   pour qu'un pixel "complete" parte même si l'utilisateur quitte l'écran juste après.
 * - Macros : remplacées par MacroExpander JUSTE AVANT chaque requête, car la spec VAST 4.1 §6.2
 *   définit [TIMESTAMP] comme l'heure d'accès à l'URI. Les données de l'événement (erreur,
 *   positions) viennent du TrackingContext fourni par le player.
 * - En production : file d'attente persistée (WorkManager) + retry, car des impressions perdues
 *   hors-ligne = revenus perdus. Et logs vers l'outil d'observabilité (Datadog, Firebase...).
 */
class HttpAdTracker(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val macroExpander: MacroExpander = MacroExpander(),
    private val logger: (String) -> Unit = {},
) : AdTracker {

    override fun track(urls: List<String>, context: TrackingContext) {
        if (urls.isEmpty()) return
        scope.launch {
            urls.forEach { raw ->
                val url = macroExpander.expand(raw, context)
                try {
                    http.fire(url)
                    logger("tracking OK $url")
                } catch (e: CancellationException) {
                    throw e // annulation coopérative : ce n'est pas un échec de tracking
                } catch (e: Exception) {
                    logger("tracking FAILED $url : ${e.message}")
                }
            }
        }
    }
}
