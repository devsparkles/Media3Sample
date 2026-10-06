package com.devsparkles.media3sample.core.data.ads

import com.devsparkles.media3sample.core.data.network.HttpClient
import com.devsparkles.media3sample.core.domain.repository.AdTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * RÔLE : envoyer les pixels de tracking VAST (impression, quartiles, complete, skip, erreurs).
 *
 * - "Fire and forget" : on lance une coroutine dans un scope APPLICATIF (pas celui de l'écran),
 *   pour qu'un pixel "complete" parte même si l'utilisateur quitte l'écran juste après.
 * - Macros VAST : la régie met des placeholders dans les URLs que le player doit remplacer.
 *   [ERRORCODE]   -> code d'erreur VAST (ex : 405)
 *   [CACHEBUSTING]-> nombre aléatoire 8 chiffres, empêche les proxies/caches de dédoublonner
 *   (VAST 4 en définit beaucoup d'autres : [TIMESTAMP], [ADPLAYHEAD], [CONTENTPLAYHEAD]...)
 *   https://interactiveadvertisingbureau.github.io/vast/vast4macros/vast4-macros-latest.html
 * - En production : file d'attente persistée (WorkManager) + retry, car des impressions perdues
 *   hors-ligne = revenus perdus. Et logs vers l'outil d'observabilité (Datadog, Firebase...).
 */
class HttpAdTracker(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) : AdTracker {

    override fun track(urls: List<String>, errorCode: Int?) {
        if (urls.isEmpty()) return
        scope.launch {
            urls.forEach { raw ->
                val url = expandMacros(raw, errorCode)
                runCatching { http.fire(url) }
                    .onSuccess { logger("tracking OK $url") }
                    .onFailure { logger("tracking FAILED $url : ${it.message}") }
            }
        }
    }

    internal fun expandMacros(url: String, errorCode: Int?): String = url
        .replace("[ERRORCODE]", errorCode?.toString() ?: "")
        .replace("[CACHEBUSTING]", Random.nextInt(10_000_000, 99_999_999).toString())
}
