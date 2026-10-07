package com.devsparkles.media3sample.core.domain.tracking

/** État du player, version métier de `Player.STATE_*` (le domaine ne connaît pas Media3). */
enum class PlaybackStatus { IDLE, BUFFERING, READY, ENDED }

/** Version métier de `Player.getPlaybackSuppressionReason()`. */
enum class Suppression {
    NONE,

    /** Appel téléphonique, alarme... (PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS). */
    TRANSIENT_AUDIO_FOCUS_LOSS,

    /** Sortie audio inadaptée (PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT). */
    UNSUITABLE_OUTPUT,

    /** L'utilisateur fait glisser la barre de progression (PLAYBACK_SUPPRESSION_REASON_SCRUBBING). */
    SCRUBBING,
}

/**
 * Photographie CONSOLIDÉE du player à un instant donné (lue dans `Player.Listener.onEvents`).
 *
 * @property content contenu courant, null si le player n'a pas de média
 * @property ad pub en cours, null si on lit le contenu
 * @property contentPositionMs position dans le CONTENU (même pendant une pub)
 * @property playhead position dans l'asset en cours (pub ou contenu), pour les trackers
 */
data class PlayerSnapshot(
    val content: TrackedContent?,
    val status: PlaybackStatus,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val suppression: Suppression,
    val ad: TrackedAd?,
    val contentPositionMs: Long,
    val playhead: Playhead,
)

/**
 * Ce que la photographie seule ne dit pas : les RAISONS des changements, qui ne sont fournies
 * que par les callbacks individuels de Media3 (onPositionDiscontinuity, onMediaItemTransition,
 * onPlayWhenReadyChanged). Le traducteur les collecte pendant l'itération puis les joint au
 * snapshot dans onEvents.
 *
 * @property errorOccurred une erreur fatale a eu lieu pendant l'itération (même si le code
 *           applicatif a déjà relancé prepare() : cf. BEHIND_LIVE_WINDOW)
 * @property seek saut demandé par l'utilisateur ou le code (DISCONTINUITY_REASON_SEEK)
 * @property repeated le média a redémarré en mode repeat (MEDIA_ITEM_TRANSITION_REASON_REPEAT)
 * @property pauseReason raison du passage de playWhenReady à false
 */
data class SnapshotHints(
    val errorOccurred: Boolean = false,
    val seek: Seek? = null,
    val repeated: Boolean = false,
    val pauseReason: PauseReason? = null,
) {
    data class Seek(val fromMs: Long, val toMs: Long)
}

/**
 * RÔLE : transformer une suite de [PlayerSnapshot] en événements de session normalisés.
 *
 * Kotlin pur et SANS horloge ni thread : on la nourrit de snapshots, elle renvoie des
 * événements. Elle se teste donc en JVM pure, scénario par scénario.
 *
 * États internes :
 *
 *      (pas de session) ──READY + playWhenReady──► EN SESSION ──ENDED / erreur / stop──► (pas de session)
 *                                                  │  ▲   │
 *                                     pause/interr.│  │   │ pub en cours ? -> currentBreak/currentAd
 *                                                  ▼  │reprise
 *                                                 EN PAUSE
 *      release() depuis n'importe où ──► RELEASED (terminal : plus rien n'est émis)
 *
 * Décisions (documentées dans GUIDE_ENTRETIEN.md, section « Tracking d'audience ») :
 *  - DÉMARRAGE : la session commence au premier `READY` avec `playWhenReady = true`, pas au
 *    `prepare()` : le buffering initial n'est pas du visionnage, et à ce moment la timeline
 *    est chargée (durée, live ou non) pour les métadonnées.
 *  - BUFFERING : ce n'est PAS une pause. Pas d'événement ; les ticks s'arrêtent d'eux-mêmes
 *    car `isPlaying` passe à false. C'est la règle Nielsen (FAQ : « For brief buffering [...]
 *    do not call stop right away. Instead, stop sending the playheadPosition »).
 *  - SUPPRESSION (appel téléphonique) : `playWhenReady` reste true mais la lecture est
 *    suspendue -> [PauseReason.INTERRUPTION], distincte d'une pause utilisateur.
 *    Le SCRUBBING n'est pas une interruption (c'est un seek en cours).
 *  - SEEK : uniquement [PlaybackEvent.Seeked], jamais de pause/fin implicite.
 *  - REPEAT : un tour de boucle = fin de la session (COMPLETED) + nouvelle session.
 *  - ERREUR : fin de session (ERROR). Un `prepare()` (bouton Réessayer, recalage
 *    BEHIND_LIVE_WINDOW) ouvrira une NOUVELLE session au prochain READY.
 *  - FIN : au plus une fois par session, quel que soit le chemin (ENDED, erreur, release...).
 */
class PlaybackSessionStateMachine {

    private var session: TrackedContent? = null
    private var paused = false
    private var currentBreak: TrackedAdBreak? = null
    private var currentAd: TrackedAd? = null
    private var lastPlayhead: Playhead? = null
    private var released = false

    /** true si une session est ouverte et non en pause : le traducteur arme alors le ticker 1 s. */
    val isSessionActive: Boolean get() = session != null && !paused && !released

    fun onSnapshot(snapshot: PlayerSnapshot, hints: SnapshotHints = SnapshotHints()): List<PlaybackEvent> {
        if (released) return emptyList()
        val events = mutableListOf<PlaybackEvent>()

        // 1. Erreur fatale : on ferme la session en cours (le player est en IDLE ou relancé).
        if (hints.errorOccurred) closeSession(SessionEndReason.ERROR, events)

        // 2. Changement de contenu ou tour de boucle. UNE seule transition, même si Media3 a
        //    émis onMediaItemTransition ET onPositionDiscontinuity(AUTO_TRANSITION) : on compare
        //    des états consolidés, pas des callbacks.
        val current = session
        val content = snapshot.content
        if (current != null && content != null && content.id != current.id) {
            closeAdBreak(resumesContent = false, events)
            session = content
            paused = false
            events += PlaybackEvent.ContentChanged(current, content, snapshot.contentPositionMs)
        } else if (current != null && hints.repeated) {
            closeSession(SessionEndReason.COMPLETED, events)
        }

        // 3. Fin de session : fin du média, ou player arrêté.
        if (session != null) {
            when (snapshot.status) {
                PlaybackStatus.ENDED -> closeSession(SessionEndReason.COMPLETED, events, snapshot.playhead)
                PlaybackStatus.IDLE -> closeSession(SessionEndReason.STOPPED, events, snapshot.playhead)
                else -> Unit
            }
        }

        // 4. Démarrage d'une session.
        if (session == null) {
            if (content == null || snapshot.status != PlaybackStatus.READY || !snapshot.playWhenReady) {
                lastPlayhead = snapshot.playhead
                return events
            }
            session = content
            paused = false
            events += PlaybackEvent.SessionStarted(content, snapshot.contentPositionMs)
        }

        // 5. Seek : un simple événement, la session continue.
        hints.seek?.let { events += PlaybackEvent.Seeked(it.fromMs, it.toMs) }

        // 6. Bascules contenu <-> pub.
        updateAds(snapshot.ad, events)

        // 7. Pause / reprise.
        val wantsToPlay = snapshot.playWhenReady &&
            (snapshot.suppression == Suppression.NONE || snapshot.suppression == Suppression.SCRUBBING)
        if (!paused && !wantsToPlay) {
            paused = true
            events += PlaybackEvent.Paused(pauseReason(snapshot, hints), snapshot.playhead)
        } else if (paused && wantsToPlay) {
            paused = false
            events += PlaybackEvent.Resumed(snapshot.playhead)
        }

        lastPlayhead = snapshot.playhead
        return events
    }

    /** Appelé toutes les secondes : un battement de cœur seulement si la lecture avance VRAIMENT. */
    fun onTick(snapshot: PlayerSnapshot): List<PlaybackEvent> {
        if (!isSessionActive || !snapshot.isPlaying) return emptyList()
        lastPlayhead = snapshot.playhead
        return listOf(PlaybackEvent.Tick(snapshot.playhead))
    }

    /**
     * Libération du player. Idempotent : le second appel ne renvoie rien.
     * @param snapshot dernier état lu AVANT player.release() (pour la position finale)
     */
    fun release(snapshot: PlayerSnapshot?): List<PlaybackEvent> {
        if (released) return emptyList()
        val events = mutableListOf<PlaybackEvent>()
        closeSession(SessionEndReason.RELEASED, events, snapshot?.playhead)
        released = true
        events += PlaybackEvent.Released
        return events
    }

    private fun updateAds(ad: TrackedAd?, events: MutableList<PlaybackEvent>) {
        if (ad == null) {
            closeAdBreak(resumesContent = true, events)
            return
        }
        if (currentBreak?.groupIndex != ad.adBreak.groupIndex) {
            closeAdBreak(resumesContent = true, events) // break précédent sans retour au contenu (rare)
            currentBreak = ad.adBreak
            events += PlaybackEvent.AdBreakStarted(ad.adBreak)
        }
        if (currentAd?.indexInBreak != ad.indexInBreak) {
            currentAd = ad
            events += PlaybackEvent.AdStarted(ad)
        }
    }

    private fun closeAdBreak(resumesContent: Boolean, events: MutableList<PlaybackEvent>) {
        val adBreak = currentBreak ?: return
        currentBreak = null
        currentAd = null
        events += PlaybackEvent.AdBreakEnded(adBreak, resumesContent)
    }

    /** LE seul endroit qui émet [PlaybackEvent.Ended] : garantit « au plus une fin par session ». */
    private fun closeSession(reason: SessionEndReason, events: MutableList<PlaybackEvent>, finalPlayhead: Playhead? = lastPlayhead) {
        if (session == null) return
        closeAdBreak(resumesContent = false, events)
        session = null
        paused = false
        events += PlaybackEvent.Ended(reason, finalPlayhead)
    }

    private fun pauseReason(snapshot: PlayerSnapshot, hints: SnapshotHints): PauseReason = when {
        !snapshot.playWhenReady -> hints.pauseReason ?: PauseReason.USER
        snapshot.suppression == Suppression.TRANSIENT_AUDIO_FOCUS_LOSS -> PauseReason.INTERRUPTION
        snapshot.suppression == Suppression.UNSUITABLE_OUTPUT -> PauseReason.UNSUITABLE_OUTPUT
        else -> PauseReason.OTHER
    }
}
