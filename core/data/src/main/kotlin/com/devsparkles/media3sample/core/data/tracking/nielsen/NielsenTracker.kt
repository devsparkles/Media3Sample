package com.devsparkles.media3sample.core.data.tracking.nielsen

import com.devsparkles.media3sample.core.domain.tracking.AdBreakKind
import com.devsparkles.media3sample.core.domain.tracking.PauseReason
import com.devsparkles.media3sample.core.domain.tracking.PlaybackTracker
import com.devsparkles.media3sample.core.domain.tracking.Playhead
import com.devsparkles.media3sample.core.domain.tracking.SessionEndReason
import com.devsparkles.media3sample.core.domain.tracking.TrackedAd
import com.devsparkles.media3sample.core.domain.tracking.TrackedAdBreak
import com.devsparkles.media3sample.core.domain.tracking.TrackedContent

/**
 * RÔLE : adapter « Nielsen DCR vidéo » : traduit les événements normalisés en appels au SDK
 * Nielsen, en respectant SES règles. Ces règles sont propres à Nielsen : elles vivent ici et
 * nulle part ailleurs (un autre outil n'a pas les mêmes contraintes).
 *
 * Sources (Nielsen Engineering Portal) :
 *  - [DCR]  https://engineeringportal.nielsen.com/wiki/DCR_Video_Android_SDK
 *  - [FAQ]  https://engineeringportal.nielsen.com/wiki/Digital_Measurement_FAQ
 *  - [INT]  https://engineeringportal.nielsen.com/wiki/Digital_Measurement_Interruption_Scenarios
 *  - [META] https://engineeringportal.nielsen.com/wiki/loadMetadata()
 *  - [FSM]  https://engineeringportal.nielsen.com/wiki/iOS_SDK_API_Reference (cycle de vie / états
 *           du SDK ; cette description d'états n'a pas été trouvée sur la page Android)
 *
 * États du SDK [FSM] : « play & loadMetadata » -> PROCESSING ; « stop or end » -> IDLE.
 * On les modélise dans [sdkState] pour ne JAMAIS appeler stop() quand le SDK est déjà IDLE.
 *
 * À distinguer : [session] = le contenu mesuré, ouvert par play() et fermé par end().
 * Une pause (stop) met le SDK en IDLE mais NE ferme PAS la session : on reprend avec
 * play + loadMetadata sur le même asset.
 *
 * Choix non documentés par Nielsen (signalés « non documenté ») :
 *  - seek : aucune consigne trouvée -> aucun appel SDK, le playhead suivant porte la nouvelle position ;
 *  - post-roll : la place exacte de end() par rapport au stop() de la pub n'est pas décrite dans
 *    les pages consultées -> on appelle directement end() après le post-roll ;
 *  - end() alors que le SDK est IDLE (ex : release pendant une pause) : comportement non
 *    documenté -> on l'appelle quand même, car la session de contenu doit être close.
 *
 * Toutes les méthodes sont appelées sur le thread applicatif du player (garanti par le cœur).
 *
 * @param channelName passé à play() ; optionnel en DCR, requis en DTVR [play()].
 */
class NielsenTracker(
    private val sdk: NielsenSdkGateway,
    private val channelName: String = "Media3Sample",
) : PlaybackTracker {

    override val name = "Nielsen"

    private enum class SdkState { IDLE, PROCESSING }

    private var sdkState = SdkState.IDLE

    /** Contenu de la session ouverte (play() ... end()), null hors session. */
    private var session: TrackedContent? = null

    /** Métadonnées de l'asset en cours (contenu ou pub) : rechargées à la reprise après une pause. */
    private var currentAssetMetadata: Map<String, String> = emptyMap()

    private var lastSentPlayheadSec: Long? = null

    /** Après close(), l'instance SDK est libérée : plus aucun appel. */
    private var closed = false

    // --- Session ------------------------------------------------------------------------------

    override fun onSessionStart(content: TrackedContent, positionMs: Long) = openSession(content)

    /**
     * Changement de contenu : on passe directement par [openSession], dont le flush appelle
     * end() sur l'ancien contenu (« end() : changement de contenu »).
     */
    override fun onContentChange(previous: TrackedContent, next: TrackedContent, positionMs: Long) = openSession(next)

    private fun openSession(content: TrackedContent) {
        if (closed) return
        flushPreviousSession(next = content)
        // [DCR] « play() : call at start of each new stream » puis « loadMetadata() needs to be
        // called at the beginning of each asset ». Jamais stop() puis play() pour démarrer :
        // stop() n'est appelé que dans le flush, et seulement si le SDK est en PROCESSING.
        sdk.play(channelInfo())
        loadAsset(contentMetadata(content))
        session = content
    }

    /**
     * LE FLUSH : unique endroit qui ferme une session précédente avant d'en ouvrir une nouvelle.
     *  - contenu différent -> end() [DCR] (« when content stop is initiated and content cannot
     *    be resumed from the same position ») ;
     *  - même contenu (session restée ouverte) -> stop() si le SDK est en PROCESSING.
     * Le cas normal (session déjà terminée par onSessionEnd) ne fait rien ici.
     */
    private fun flushPreviousSession(next: TrackedContent) {
        val previous = session ?: return
        if (previous.id != next.id) {
            endSession(finalPlayhead = null)
        } else if (sdkState == SdkState.PROCESSING) {
            stopAsset(finalPlayhead = null)
        }
    }

    override fun onSessionEnd(reason: SessionEndReason, finalPlayhead: Playhead?) {
        if (closed) return
        endSession(finalPlayhead)
    }

    /** Idempotent : jamais deux end() pour la même session. */
    private fun endSession(finalPlayhead: Playhead?) {
        if (session == null) return
        // [DCR] « The final playhead position must be sent for the current asset being played
        // before calling stop, end or loadmetadata ».
        sendFinalPlayhead(finalPlayhead)
        sdk.end()
        sdkState = SdkState.IDLE
        session = null
        lastSentPlayheadSec = null
    }

    // --- Pause / reprise ------------------------------------------------------------------------

    /**
     * [INT] : pause, appel téléphonique, alarme, casque débranché, arrière-plan... -> « Call stop
     * as soon as [...] and withhold sending the playhead position ». Nielsen traite donc toutes
     * les [PauseReason] de la même façon : la distinction reste utile aux autres trackers.
     */
    override fun onPause(reason: PauseReason, playhead: Playhead) {
        if (closed || session == null) return
        stopAsset(playhead)
    }

    /**
     * [FAQ] « when content resumes playback call play, loadMetadata and start [...] playheadPosition ».
     * On recharge les métadonnées de l'asset EN COURS (la pub si on était en pause pendant une pub).
     */
    override fun onResume(playhead: Playhead) {
        if (closed || session == null || sdkState == SdkState.PROCESSING) return
        sdk.play(channelInfo())
        loadAsset(currentAssetMetadata)
    }

    /** Non documenté par Nielsen : aucun appel, le prochain playhead porte la nouvelle position. */
    override fun onSeek(fromMs: Long, toMs: Long) = Unit

    // --- Pubs -------------------------------------------------------------------------------------

    /** Rien au début du break lui-même : tout se fait pub par pub ([onAdStart]). */
    override fun onAdBreakStart(adBreak: TrackedAdBreak) = Unit

    /**
     * [FAQ] « Call stop() before starting an ad break. Call loadMetadata() to load ad. »
     * [DCR] « stop() : call [...] at the end of each Ad » -> même stop() entre deux pubs.
     */
    override fun onAdStart(ad: TrackedAd) {
        if (closed || session == null) return
        stopAsset(finalPlayhead = null)
        loadAsset(adMetadata(ad))
    }

    /** [FAQ] « Once adbreak is complete, call stop and loadMetadata(content) ». */
    override fun onAdBreakEnd(adBreak: TrackedAdBreak, resumesContent: Boolean) {
        val content = session ?: return
        if (closed || !resumesContent) return // post-roll : end() arrive juste après (non documenté)
        stopAsset(finalPlayhead = null)
        loadAsset(contentMetadata(content))
    }

    // --- Playhead -----------------------------------------------------------------------------------

    /**
     * [DCR] « setPlayheadPosition() has to be called every second » : VOD = position en
     * secondes, live = « current Unix timestamp (seconds since Jan-1-1970 UTC) ». Pendant une
     * pub, c'est la position DANS la pub [FAQ] (« App should always send the playhead position
     * regardless of ad break »). Hors PROCESSING (pause), on retient le playhead [INT].
     */
    override fun onPlayheadTick(playhead: Playhead) {
        if (closed || session == null || sdkState != SdkState.PROCESSING) return
        sendPlayhead(playhead)
    }

    private fun sendPlayhead(playhead: Playhead) {
        val seconds = if (playhead.isLive && !playhead.isAd) playhead.unixTimeMs / 1000 else playhead.positionMs / 1000
        sdk.setPlayheadPosition(seconds)
        lastSentPlayheadSec = seconds
    }

    private fun sendFinalPlayhead(finalPlayhead: Playhead?) {
        if (finalPlayhead == null || sdkState != SdkState.PROCESSING) return
        val seconds = if (finalPlayhead.isLive && !finalPlayhead.isAd) finalPlayhead.unixTimeMs / 1000 else finalPlayhead.positionMs / 1000
        if (seconds != lastSentPlayheadSec) sendPlayhead(finalPlayhead)
    }

    // --- Cycle de vie de l'app ---------------------------------------------------------------------

    override fun onAppBackground() {
        if (!closed) sdk.appInBackground()
    }

    override fun onAppForeground() {
        if (!closed) sdk.appInForeground()
    }

    /** close() « à la fermeture de l'application seulement » : on termine proprement avant. */
    override fun onAppClose() {
        if (closed) return
        endSession(finalPlayhead = null)
        sdk.close()
        closed = true
    }

    // --- Utilitaires --------------------------------------------------------------------------------

    /** stop() uniquement depuis PROCESSING : jamais de stop() « par précaution » sur un SDK IDLE. */
    private fun stopAsset(finalPlayhead: Playhead?) {
        if (sdkState != SdkState.PROCESSING) return
        sendFinalPlayhead(finalPlayhead)
        sdk.stop()
        sdkState = SdkState.IDLE
    }

    private fun loadAsset(metadata: Map<String, String>) {
        sdk.loadMetadata(metadata)
        currentAssetMetadata = metadata
        sdkState = SdkState.PROCESSING // [FSM] loadMetadata fait passer en PROCESSING
        lastSentPlayheadSec = null
    }

    /** [play()] : `channelName` libre (32 caractères max), `mediaURL` optionnel (vide si inconnu). */
    private fun channelInfo() = mapOf("channelName" to channelName.take(32), "mediaURL" to "")

    /**
     * Métadonnées CONTENU [META] : `type` = "content", `assetid`, `program`, `title`, `length` (s).
     * Les autres clés listées par Nielsen (airdate, isfullepisode, adloadtype, segB, segC,
     * crossId1...) dépendent du contrat client : valeurs fournies par Nielsen à l'onboarding,
     * donc non renseignées ici. `program` = titre faute de notion de programme dans le catalogue.
     */
    private fun contentMetadata(content: TrackedContent): Map<String, String> = buildMap {
        put("type", "content")
        put("assetid", content.id)
        put("program", content.title)
        put("title", content.title)
        content.durationMs?.let { put("length", (it / 1000).toString()) }
    }

    /**
     * Métadonnées PUB [META] : `type` = "preroll" | "midroll" | "postroll" (ou "ad" par défaut),
     * `assetid`, `title`. C'est le champ `type` qui distingue contenu et pub : on ne mélange
     * JAMAIS les deux dans le même objet.
     */
    private fun adMetadata(ad: TrackedAd): Map<String, String> = mapOf(
        "type" to when (ad.adBreak.kind) {
            AdBreakKind.PREROLL -> "preroll"
            AdBreakKind.MIDROLL -> "midroll"
            AdBreakKind.POSTROLL -> "postroll"
        },
        "assetid" to ad.id,
        "title" to ad.id,
    )
}
