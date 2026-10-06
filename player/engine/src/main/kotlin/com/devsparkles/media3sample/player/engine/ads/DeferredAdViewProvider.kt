package com.devsparkles.media3sample.player.engine.ads

import android.view.ViewGroup
import androidx.media3.common.AdOverlayInfo
import androidx.media3.common.AdViewProvider

/**
 * RÔLE : fournir la vue dans laquelle les pubs peuvent dessiner leur UI (overlays, SIMID, OMID).
 *
 * Problème : le player est créé dans le ViewModel (il survit à la rotation), AVANT que la
 * PlayerView existe. Or setLocalAdInsertionComponents() exige un AdViewProvider dès la
 * construction. Solution : un provider "différé" auquel l'UI branche sa PlayerView
 * (qui implémente elle-même AdViewProvider) quand elle est affichée.
 *
 * Pourquoi c'est important pour le poste :
 *  - OMID (Open Measurement, IAB) mesure la VISIBILITÉ de la pub (viewability). Le SDK OM
 *    a besoin de la vue de la pub et des "friendly obstructions" (nos boutons par-dessus la
 *    vidéo, déclarés ici via getAdOverlayInfos) pour ne pas les compter comme masquant la pub.
 *    https://iabtechlab.com/standards/open-measurement-sdk/
 *  - SIMID (Secure Interactive Media Interface Definition) : pubs interactives affichées
 *    dans une WebView au-dessus du player -> il faut un conteneur, c'est ce ViewGroup.
 *    https://iabtechlab.com/standards/simid/
 */
class DeferredAdViewProvider : AdViewProvider {

    /** Typiquement la PlayerView (qui implémente AdViewProvider). null quand l'écran est détruit. */
    var delegate: AdViewProvider? = null

    override fun getAdViewGroup(): ViewGroup =
        checkNotNull(delegate?.adViewGroup) { "No ad view attached: is the PlayerView displayed?" }

    override fun getAdOverlayInfos(): List<AdOverlayInfo> = delegate?.adOverlayInfos.orEmpty()
}
