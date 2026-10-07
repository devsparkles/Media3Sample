package com.devsparkles.media3sample.core.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * RÔLE : abstraction HTTP minimale pour les appels pub (VMAP, VAST, pixels de tracking).
 *
 * Choix technique : interface + implémentation HttpURLConnection pour garder le sample sans
 * dépendance. En production on utiliserait OkHttp (pool de connexions, HTTP/2, intercepteurs
 * pour logs/headers d'auth vers l'Ad Proxy) ou Retrofit pour les APIs REST typées.
 * Grâce à l'interface, les tests injectent un FakeHttpClient qui renvoie des XML fixtures.
 */
interface HttpClient {
    /** GET et renvoie le corps en texte. Lève IOException si code HTTP >= 400. */
    suspend fun get(url: String): String

    /** GET dont on ignore la réponse (pixel de tracking). */
    suspend fun fire(url: String)
}

class UrlConnectionHttpClient(
    private val userAgent: String,
    private val timeoutMs: Int = 5_000,
) : HttpClient {

    // withContext(Dispatchers.IO) : on bascule sur le pool de threads dédié aux I/O bloquantes.
    // L'appelant (ViewModel, AdsLoader sur le main thread) peut donc appeler `get` sans risque
    // d'ANR. C'est la convention "main-safe" des fonctions suspend.
    // Doc : https://developer.android.com/kotlin/coroutines/coroutines-best-practices#main-safe
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code >= 400) throw IOException("HTTP $code for $url")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun fire(url: String) = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            connection.responseCode // déclenche la requête
            Unit
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }
}
