package com.devsparkles.media3sample.core.data.ads.parser

import com.devsparkles.media3sample.core.data.ads.parser.XmlSupport.attr
import com.devsparkles.media3sample.core.data.ads.parser.XmlSupport.child
import com.devsparkles.media3sample.core.data.ads.parser.XmlSupport.children
import com.devsparkles.media3sample.core.data.ads.parser.XmlSupport.path
import com.devsparkles.media3sample.core.data.ads.parser.XmlSupport.text
import com.devsparkles.media3sample.core.domain.model.AdTrackingEvent
import com.devsparkles.media3sample.core.domain.model.MediaFile
import org.w3c.dom.Element

/**
 * RÔLE : parser un document VAST (2.0 / 3.0 / 4.x) en DTO.
 *
 * Structure simplifiée (https://iabtechlab.com/standards/vast/) :
 *
 * <VAST version="4.0">
 *   <Ad id="123" sequence="1">                <- plusieurs <Ad> avec sequence = un "ad pod"
 *     <InLine>                                <- la pub est décrite ici...
 *       <Impression><![CDATA[url]]></Impression>
 *       <Error><![CDATA[url?err=[ERRORCODE]]]></Error>
 *       <Creatives><Creative><Linear skipoffset="00:00:05">
 *           <Duration>00:00:30</Duration>
 *           <TrackingEvents><Tracking event="firstQuartile">url</Tracking>...</TrackingEvents>
 *           <VideoClicks><ClickThrough>url</ClickThrough><ClickTracking>url</ClickTracking></VideoClicks>
 *           <MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="1280" height="720" bitrate="2000">url</MediaFile></MediaFiles>
 *       </Linear></Creative></Creatives>
 *     </InLine>
 *     <!-- ...OU <Wrapper> : redirection vers un autre VAST (chaîne de régies). -->
 *     <!-- <Wrapper><VASTAdTagURI>url</VASTAdTagURI><Impression>..</Impression>...</Wrapper> -->
 *   </Ad>
 * </VAST>
 *
 * Un VAST vide (<VAST version="3.0"/>) est valide : c'est un "no fill" (pas de pub à servir).
 */
class VastParser {

    data class VastDocument(val ads: List<VastAd>, val errorUrls: List<String>)

    /** Données communes à InLine et Wrapper : tout ça doit être tracké, à TOUS les niveaux. */
    data class TrackingData(
        val impressionUrls: List<String> = emptyList(),
        val errorUrls: List<String> = emptyList(),
        val events: Map<AdTrackingEvent, List<String>> = emptyMap(),
    ) {
        /** Fusion wrapper + inline : on DOIT pinger les URLs de chaque niveau de wrapper. */
        operator fun plus(other: TrackingData) = TrackingData(
            impressionUrls = impressionUrls + other.impressionUrls,
            errorUrls = errorUrls + other.errorUrls,
            events = (events.keys + other.events.keys).associateWith {
                events[it].orEmpty() + other.events[it].orEmpty()
            },
        )
    }

    sealed interface VastAd {
        val id: String
        val sequence: Int
        val tracking: TrackingData

        data class InLine(
            override val id: String,
            override val sequence: Int,
            override val tracking: TrackingData,
            val durationMs: Long,
            val skipOffset: String?,
            val clickThroughUrl: String?,
            val mediaFiles: List<MediaFile>,
        ) : VastAd

        data class Wrapper(
            override val id: String,
            override val sequence: Int,
            override val tracking: TrackingData,
            val vastAdTagUri: String,
        ) : VastAd
    }

    fun parse(xml: String): VastDocument = parse(XmlSupport.parse(xml).documentElement)

    fun parse(root: Element): VastDocument {
        require(root.localName == "VAST") { "Not a VAST document: <${root.localName}>" }
        val ads = root.children("Ad").mapIndexedNotNull { index, ad -> parseAd(ad, index) }
        return VastDocument(ads = ads, errorUrls = root.children("Error").map { it.text() })
    }

    private fun parseAd(ad: Element, index: Int): VastAd? {
        val id = ad.attr("id") ?: "ad-$index"
        val sequence = ad.attr("sequence")?.toIntOrNull() ?: (index + 1)

        ad.child("InLine")?.let { inline ->
            // On ne garde que la première Creative LINÉAIRE (on ignore NonLinear/Companion ici).
            val linear = inline.path("Creatives", "Creative", "Linear").firstOrNull() ?: return null
            return VastAd.InLine(
                id = id,
                sequence = sequence,
                tracking = parseTracking(inline, linear),
                durationMs = TimeParser.parseClockMs(linear.child("Duration")?.text()) ?: 0L,
                skipOffset = linear.attr("skipoffset"),
                clickThroughUrl = linear.child("VideoClicks")?.child("ClickThrough")?.text(),
                mediaFiles = linear.path("MediaFiles", "MediaFile").mapNotNull(::parseMediaFile),
            )
        }

        ad.child("Wrapper")?.let { wrapper ->
            val uri = wrapper.child("VASTAdTagURI")?.text()?.takeIf { it.isNotEmpty() } ?: return null
            // Un Wrapper peut aussi avoir des TrackingEvents dans ses Creatives/Linear.
            val linear = wrapper.path("Creatives", "Creative", "Linear").firstOrNull()
            return VastAd.Wrapper(id, sequence, parseTracking(wrapper, linear), uri)
        }
        return null
    }

    private fun parseTracking(container: Element, linear: Element?): TrackingData {
        val events = linear?.path("TrackingEvents", "Tracking").orEmpty()
            .mapNotNull { t -> AdTrackingEvent.fromVastName(t.attr("event").orEmpty())?.let { it to t.text() } }
        val clickTracking = linear?.child("VideoClicks")?.children("ClickTracking").orEmpty()
            .map { AdTrackingEvent.CLICK_TRACKING to it.text() }
        return TrackingData(
            impressionUrls = container.children("Impression").map { it.text() }.filter { it.isNotEmpty() },
            errorUrls = container.children("Error").map { it.text() }.filter { it.isNotEmpty() },
            events = (events + clickTracking).groupBy({ it.first }, { it.second }),
        )
    }

    private fun parseMediaFile(element: Element): MediaFile? {
        val url = element.text().takeIf { it.isNotEmpty() } ?: return null
        // "streaming" = HLS/DASH, "progressive" = MP4 téléchargé en HTTP classique.
        return MediaFile(
            url = url,
            mimeType = element.attr("type").orEmpty(),
            width = element.attr("width")?.toIntOrNull() ?: 0,
            height = element.attr("height")?.toIntOrNull() ?: 0,
            bitrateKbps = element.attr("bitrate")?.toIntOrNull(),
        )
    }
}
