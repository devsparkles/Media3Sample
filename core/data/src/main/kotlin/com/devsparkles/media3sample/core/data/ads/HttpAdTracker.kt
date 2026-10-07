package com.devsparkles.media3sample.core.data.ads

import com.devsparkles.media3sample.core.data.ads.macro.MacroExpander
import com.devsparkles.media3sample.core.data.network.HttpClient
import com.devsparkles.media3sample.core.domain.model.TrackingContext
import com.devsparkles.media3sample.core.domain.repository.AdTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * RÔLE : envoyer les pixels de tracking VAST (impression, quartiles, complete, skip, erreurs).
 *
 * - "Fire and forget" : on lance une coroutine dans un scope APPLICATIF (pas celui de l'écran),
 *   pour qu'un pixel "complete" parte même si l'utilisateur quitte l'écran juste après.
 * - Macros VAST : déléguées à MacroExpander (design pattern Strategy, voir le package `macro`).
 *   L'instantané des valeurs ([TIMESTAMP], [CACHEBUSTING]) est pris de façon SYNCHRONE dans
 *   track(), donc à l'heure de l'événement, et partagé par toutes les URLs de cet événement.
 *   S'il était pris dans la coroutine, l'heure dépendrait de la charge du pool de threads.
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
        val snapshot = macroExpander.snapshot(context)
        scope.launch {
            urls.forEach { raw ->
                val url = macroExpander.expand(raw, snapshot)
                runCatching { http.fire(url) }
                    .onSuccess { logger("tracking OK $url") }
                    .onFailure { logger("tracking FAILED $url : ${it.message}") }
            }
        }
    }
}
