package com.devsparkles.media3sample.core.data.tracking.brand

import com.devsparkles.media3sample.core.data.tracking.inhouse.InHouseBeacon
import com.devsparkles.media3sample.core.data.tracking.inhouse.InHouseTracker
import com.devsparkles.media3sample.core.data.tracking.nielsen.NielsenSdkGateway
import com.devsparkles.media3sample.core.data.tracking.nielsen.NielsenTracker
import com.devsparkles.media3sample.core.domain.tracking.PlaybackTracker

/** Les outils de mesure qu'une marque peut activer. Un nouvel outil = une entrée ici + une branche dans [BrandTrackerFactory]. */
enum class TrackerKind { NIELSEN, IN_HOUSE }

/**
 * Configuration de mesure d'UNE marque d'un player en marque blanche.
 *
 * Cas réel : un même player sert plusieurs diffuseurs (plusieurs apps, un seul code). Chaque
 * marque a SES obligations de mesure (institut d'audience de son pays, outil maison...) :
 * on ne code pas « if (brand == X) » dans le player, on décrit la marque par une config.
 * En prod, la config viendrait d'un product flavor (`BuildConfig.BRAND`) ou d'une config
 * distante ; ici, AppContainer en choisit une.
 *
 * @property brandId identifiant de la marque (logs, channelName Nielsen par défaut)
 * @property trackers outils activés pour cette marque, dans l'ordre d'appel
 * @property nielsenChannelName `channelName` passé à play() (32 caractères max)
 * @property heartbeatEveryTicks fréquence du heartbeat de l'outil maison (en secondes)
 */
data class BrandTrackingConfig(
    val brandId: String,
    val trackers: List<TrackerKind>,
    val nielsenChannelName: String = brandId,
    val heartbeatEveryTicks: Int = 10,
) {
    init {
        require(trackers.toSet().size == trackers.size) { "Outil déclaré deux fois pour $brandId : $trackers" }
    }
}

/**
 * RÔLE : construire la liste des reporters d'une marque (pattern Factory).
 *
 * Le player ne sait pas quelle marque il sert : il reçoit une liste de [PlaybackTracker],
 * que le CompositeTracker diffuse. Changer d'outils pour une marque = changer sa config,
 * zéro ligne de player.
 *
 * Les dépendances des SDK sont injectées sous forme de fabriques : un SDK n'est créé que si
 * la marque l'utilise (pas d'init Nielsen inutile pour une marque qui ne le mesure pas).
 */
class BrandTrackerFactory(
    private val nielsenGateway: () -> NielsenSdkGateway,
    private val inHouseSend: (InHouseBeacon) -> Unit,
) {
    fun create(config: BrandTrackingConfig): List<PlaybackTracker> = config.trackers.map { kind ->
        when (kind) {
            TrackerKind.NIELSEN -> NielsenTracker(nielsenGateway(), channelName = config.nielsenChannelName)
            TrackerKind.IN_HOUSE -> InHouseTracker(send = inHouseSend, heartbeatEveryTicks = config.heartbeatEveryTicks)
        }
    }
}
