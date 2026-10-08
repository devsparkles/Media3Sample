package com.devsparkles.media3sample.core.domain.tracking

/**
 * RÔLE : contrat commun à TOUS les outils de mesure d'audience (Nielsen, outil maison, Médiamétrie...).
 *
 * Pattern Adapter : le cœur parle un langage NORMALISÉ (ces méthodes) ; chaque implémentation
 * le traduit vers son SDK ou son backend, avec SES règles. Exemple : Nielsen exige `stop()`
 * avant chaque pub, un tracker maison ne l'exige pas. Ces règles vivent dans l'adapter, jamais ici.
 *
 * Garanties apportées par le cœur ([PlaybackSessionStateMachine]) :
 *  - tous les appels arrivent sur le MÊME thread (le thread applicatif du player) ;
 *  - [onSessionStart] précède tout autre appel de session ;
 *  - une session se termine au plus une fois ([onSessionEnd]).
 * Malgré tout, une implémentation doit rester DÉFENSIVE (cf. la suite de tests de contrat
 * `PlaybackTrackerContractTest`) : ignorer un appel hors session, ne jamais lever d'exception.
 *
 * Seules [onAppClose] et [onContentChange] ont une implémentation par défaut : les autres
 * méthodes forcent chaque adapter à se poser la question « que fait mon outil ici ? ».
 */
interface PlaybackTracker {

    /** Nom lisible, pour les logs (« Nielsen », « InHouse »...). */
    val name: String

    /** Début d'une session de visionnage (l'utilisateur a lancé la lecture et le média est prêt). */
    fun onSessionStart(content: TrackedContent, positionMs: Long)

    /** Lecture arrêtée (pause utilisateur, appel téléphonique, arrière-plan...). */
    fun onPause(reason: PauseReason, playhead: Playhead)

    /** Reprise après [onPause]. */
    fun onResume(playhead: Playhead)

    /** Saut dans le contenu. N'implique ni pause ni fin de session. */
    fun onSeek(fromMs: Long, toMs: Long)

    /**
     * Le player est passé à un autre contenu (playlist) SANS interruption de session côté
     * utilisateur. Par défaut : fin de la session courante puis nouvelle session.
     * Un adapter peut surcharger (Nielsen : son « flush » s'en charge).
     *
     * @param exitPlayhead dernière position de [previous] (null si inconnue)
     */
    fun onContentChange(previous: TrackedContent, next: TrackedContent, positionMs: Long, exitPlayhead: Playhead? = null) {
        onSessionEnd(SessionEndReason.CONTENT_CHANGED, finalPlayhead = exitPlayhead)
        onSessionStart(next, positionMs)
    }

    fun onAdBreakStart(adBreak: TrackedAdBreak)

    /**
     * Une pub du break commence (la première, puis chacune des suivantes).
     * @param exitPlayhead dernière position de l'asset quitté : le contenu (première pub) ou la
     *        pub précédente. Null si inconnue.
     */
    fun onAdStart(ad: TrackedAd, exitPlayhead: Playhead? = null)

    /**
     * @param resumesContent false si le break est suivi de la fin de session (post-roll) :
     *        inutile alors de recharger les métadonnées du contenu.
     * @param exitPlayhead dernière position dans la dernière pub du break (null si inconnue)
     */
    fun onAdBreakEnd(adBreak: TrackedAdBreak, resumesContent: Boolean, exitPlayhead: Playhead? = null)

    /**
     * Fin de session. Appelé AU PLUS UNE FOIS par session par le cœur ; une implémentation
     * doit tout de même être idempotente.
     *
     * @param finalPlayhead dernière position connue (Nielsen veut la position finale avant `end()`).
     */
    fun onSessionEnd(reason: SessionEndReason, finalPlayhead: Playhead?)

    /** Battement de cœur : toutes les secondes, uniquement quand la lecture avance vraiment. */
    fun onPlayheadTick(playhead: Playhead)

    fun onAppBackground()

    fun onAppForeground()

    /** Fermeture définitive de l'application : libérer le SDK. Rien à faire par défaut. */
    fun onAppClose() {}
}
