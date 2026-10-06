package com.devsparkles.media3sample.core.domain.repository

import com.devsparkles.media3sample.core.domain.model.AdSchedule
import com.devsparkles.media3sample.core.domain.model.VideoContent

/**
 * RÔLE : CONTRATS (interfaces) que la couche data doit remplir.
 *
 * Principe d'inversion de dépendances (le "D" de SOLID) : le domaine définit l'interface,
 * la couche data l'implémente. Ainsi :domain ne dépend PAS de :data, c'est l'inverse.
 * En test, on remplace l'implémentation par un fake en 3 lignes.
 */
interface ContentRepository {
    suspend fun getCatalog(): List<VideoContent>
    suspend fun getContent(id: String): VideoContent
}

interface AdRepository {
    /**
     * Télécharge le VMAP, résout tous les VAST (y compris les Wrappers) et renvoie
     * un planning prêt à être joué. Ne doit JAMAIS bloquer la lecture du contenu :
     * en cas d'échec, renvoyer AdSchedule.EMPTY plutôt que lever une exception.
     */
    suspend fun loadAdSchedule(adTagUrl: String): AdSchedule
}

/**
 * Envoi des "pixels" de tracking (simples GET HTTP "fire and forget").
 * Séparé de AdRepository car son cycle de vie est différent : il est appelé pendant
 * la lecture, par le player, des dizaines de fois.
 */
interface AdTracker {
    /**
     * @param urls URLs de tracking (peuvent contenir des macros VAST comme [CACHEBUSTING])
     * @param errorCode code d'erreur VAST (ex : 405 = fichier média illisible) pour la macro [ERRORCODE]
     */
    fun track(urls: List<String>, errorCode: Int? = null)
}
