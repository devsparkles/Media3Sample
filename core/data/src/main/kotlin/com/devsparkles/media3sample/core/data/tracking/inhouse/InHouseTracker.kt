package com.devsparkles.media3sample.core.data.tracking.inhouse

import com.devsparkles.media3sample.core.domain.tracking.PauseReason
import com.devsparkles.media3sample.core.domain.tracking.PlaybackTracker
import com.devsparkles.media3sample.core.domain.tracking.Playhead
import com.devsparkles.media3sample.core.domain.tracking.SessionEndReason
import com.devsparkles.media3sample.core.domain.tracking.TrackedAd
import com.devsparkles.media3sample.core.domain.tracking.TrackedAdBreak
import com.devsparkles.media3sample.core.domain.tracking.TrackedContent
import java.util.UUID

/**
 * Un « beacon » envoyé au backend d'analytics maison (en prod : JSON en POST, en lots).
 *
 * @property sessionId identifiant de session côté client : permet au backend de recoller les
 *           événements d'un même visionnage (et de dédoublonner les renvois).
 */
data class InHouseBeacon(
    val type: String,
    val sessionId: String,
    val fields: Map<String, Any?> = emptyMap(),
)

/**
 * RÔLE : tracker GÉNÉRIQUE d'un outil maison (cas d'un diffuseur qui développe ses propres
 * outils de mesure, au lieu ou en plus d'un SDK tiers).
 *
 * Il illustre pourquoi les règles Nielsen ne doivent PAS vivre dans le cœur :
 *  - pas de stop/play autour des pubs : un simple événement par break et par pub ;
 *  - la cause de la pause est CONSERVÉE (`reason`), utile pour analyser les interruptions
 *    (appels, casque) séparément des pauses volontaires ;
 *  - battement de cœur toutes les [heartbeatEveryTicks] secondes (et non chaque seconde) :
 *    c'est le backend qui reconstitue la durée vue, avec une précision suffisante pour un
 *    coût réseau 10 fois plus faible.
 *
 * @param send transport des beacons (Logcat dans ce sample ; HTTP/WorkManager en prod)
 */
class InHouseTracker(
    private val send: (InHouseBeacon) -> Unit,
    private val heartbeatEveryTicks: Int = 10,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) : PlaybackTracker {

    override val name = "InHouse"

    private var sessionId: String? = null
    private var ticksSinceHeartbeat = 0

    private fun emit(type: String, fields: Map<String, Any?> = emptyMap()) {
        val id = sessionId ?: return // hors session : on n'envoie rien
        send(InHouseBeacon(type, id, fields))
    }

    override fun onSessionStart(content: TrackedContent, positionMs: Long) {
        if (sessionId != null) onSessionEnd(SessionEndReason.CONTENT_CHANGED, finalPlayhead = null)
        sessionId = newSessionId()
        ticksSinceHeartbeat = 0
        emit("session_start", mapOf("contentId" to content.id, "positionMs" to positionMs, "live" to content.isLive))
    }

    override fun onPause(reason: PauseReason, playhead: Playhead) =
        emit("pause", mapOf("reason" to reason.name, "positionMs" to playhead.positionMs, "ad" to playhead.isAd))

    override fun onResume(playhead: Playhead) = emit("resume", mapOf("positionMs" to playhead.positionMs))

    override fun onSeek(fromMs: Long, toMs: Long) = emit("seek", mapOf("fromMs" to fromMs, "toMs" to toMs))

    override fun onAdBreakStart(adBreak: TrackedAdBreak) =
        emit("ad_break_start", mapOf("kind" to adBreak.kind.name, "adCount" to adBreak.adCount))

    override fun onAdStart(ad: TrackedAd, exitPlayhead: Playhead?) = emit("ad_start", mapOf("adId" to ad.id, "index" to ad.indexInBreak))

    override fun onAdBreakEnd(adBreak: TrackedAdBreak, resumesContent: Boolean, exitPlayhead: Playhead?) =
        emit("ad_break_end", mapOf("kind" to adBreak.kind.name))

    override fun onSessionEnd(reason: SessionEndReason, finalPlayhead: Playhead?) {
        if (sessionId == null) return // idempotent
        emit("session_end", mapOf("reason" to reason.name, "positionMs" to finalPlayhead?.positionMs))
        sessionId = null
    }

    override fun onPlayheadTick(playhead: Playhead) {
        if (sessionId == null) return
        ticksSinceHeartbeat++
        if (ticksSinceHeartbeat >= heartbeatEveryTicks) {
            ticksSinceHeartbeat = 0
            emit("heartbeat", mapOf("positionMs" to playhead.positionMs, "ad" to playhead.isAd))
        }
    }

    /** Hors session, rien à envoyer : l'outil maison ne mesure que le visionnage. */
    override fun onAppBackground() = emit("app_background")

    override fun onAppForeground() = emit("app_foreground")
}
