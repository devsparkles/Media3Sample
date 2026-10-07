package com.devsparkles.media3sample.core.data.ads.parser

import com.devsparkles.media3sample.core.data.ads.parser.XmlSupport.attr
import com.devsparkles.media3sample.core.data.ads.parser.XmlSupport.child
import com.devsparkles.media3sample.core.data.ads.parser.XmlSupport.children
import com.devsparkles.media3sample.core.data.ads.parser.XmlSupport.text
import com.devsparkles.media3sample.core.domain.model.AdBreakEvent
import org.w3c.dom.Element

/**
 * RÔLE : parser un document VMAP en objets "bruts" (DTO), sans résolution réseau.
 *
 * Structure VMAP 1.0 (https://iabtechlab.com/standards/vmap/) :
 *
 * <vmap:VMAP xmlns:vmap="http://www.iab.net/videosuite/vmap" version="1.0">
 *   <vmap:AdBreak timeOffset="start" breakType="linear" breakId="preroll">
 *     <vmap:AdSource id="preroll-ad-1" allowMultipleAds="false" followRedirects="true">
 *       <vmap:AdTagURI templateType="vast3"><![CDATA[ https://.../vast.xml ]]></vmap:AdTagURI>
 *       <!-- OU bien le VAST directement embarqué : -->
 *       <!-- <vmap:VASTAdData><VAST version="3.0">...</VAST></vmap:VASTAdData> -->
 *     </vmap:AdSource>
 *     <vmap:TrackingEvents>
 *       <vmap:Tracking event="breakStart"><![CDATA[ https://... ]]></vmap:Tracking>
 *     </vmap:TrackingEvents>
 *   </vmap:AdBreak>
 *   <vmap:AdBreak timeOffset="00:10:00.000" ...> ... </vmap:AdBreak>   <- mid-roll
 *   <vmap:AdBreak timeOffset="end" ...> ... </vmap:AdBreak>            <- post-roll
 * </vmap:VMAP>
 *
 * Séparation parsing / résolution : le parser est une fonction PURE (String -> objets),
 * donc trivialement testable. Le téléchargement des AdTagURI est fait par AdRepositoryImpl.
 */
class VmapParser {

    data class VmapDocument(val breaks: List<VmapAdBreak>)

    data class VmapAdBreak(
        val breakId: String?,
        val timeOffset: String?,
        val breakType: String?,
        /** URL d'un VAST à télécharger... */
        val adTagUri: String?,
        /** ...ou VAST déjà présent dans le VMAP (élément racine <VAST>). */
        val inlineVast: Element?,
        val trackingEvents: Map<AdBreakEvent, List<String>>,
    )

    /** Indique si le XML est un VMAP (sinon on peut le traiter comme un VAST simple). */
    fun isVmap(xml: String): Boolean = XmlSupport.parse(xml).documentElement.localName == "VMAP"

    fun parse(xml: String): VmapDocument {
        val root = XmlSupport.parse(xml).documentElement
        require(root.localName == "VMAP") { "Not a VMAP document: <${root.localName}>" }

        val breaks = root.children("AdBreak").map { adBreak ->
            val adSource = adBreak.child("AdSource")
            VmapAdBreak(
                breakId = adBreak.attr("breakId"),
                timeOffset = adBreak.attr("timeOffset"),
                breakType = adBreak.attr("breakType"),
                adTagUri = adSource?.child("AdTagURI")?.text()?.takeIf { it.isNotEmpty() },
                inlineVast = adSource?.child("VASTAdData")?.child("VAST"),
                trackingEvents = parseTracking(adBreak),
            )
        }
        return VmapDocument(breaks)
    }

    private fun parseTracking(adBreak: Element): Map<AdBreakEvent, List<String>> {
        val trackings = adBreak.child("TrackingEvents")?.children("Tracking").orEmpty()
        return trackings.mapNotNull { tracking ->
            val event = when (tracking.attr("event")) {
                "breakStart" -> AdBreakEvent.BREAK_START
                "breakEnd" -> AdBreakEvent.BREAK_END
                "error" -> AdBreakEvent.ERROR
                else -> null
            }
            event?.let { it to tracking.text() }
        }.groupBy({ it.first }, { it.second })
    }
}
