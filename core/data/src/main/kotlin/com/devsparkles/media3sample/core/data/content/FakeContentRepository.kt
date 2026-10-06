package com.devsparkles.media3sample.core.data.content

import com.devsparkles.media3sample.core.domain.model.DrmConfig
import com.devsparkles.media3sample.core.domain.model.DrmScheme
import com.devsparkles.media3sample.core.domain.model.StreamType
import com.devsparkles.media3sample.core.domain.model.VideoContent
import com.devsparkles.media3sample.core.domain.repository.ContentRepository

/**
 * RÔLE : catalogue en dur pour la démo (en prod : appel REST au backend, qui renverrait
 * l'URL du manifest, l'URL de licence + un token DRM signé, et l'URL de l'Ad Proxy).
 *
 * Flux de test publics utilisés :
 *  - Flux Widevine de Google (ceux de l'app démo ExoPlayer) :
 *    https://github.com/androidx/media/blob/release/demos/main/src/main/assets/media.exolist.json
 *  - Tags VMAP/VAST de test IMA (Google Ad Manager) :
 *    https://developers.google.com/interactive-media-ads/docs/sdks/html5/client-side/tags
 */
class FakeContentRepository : ContentRepository {

    private val catalog = listOf(
        VideoContent(
            id = "tears-widevine-ads",
            title = "Tears of Steel — DASH + Widevine + VMAP (pre/mid/post)",
            streamUrl = "https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd",
            streamType = StreamType.DASH,
            drm = DrmConfig(
                scheme = DrmScheme.WIDEVINE,
                licenseUrl = "https://proxy.uat.widevine.com/proxy?video_id=2015_tears&provider=widevine_test",
                // En prod : requestHeaders = mapOf("Authorization" to "Bearer <token DRM court>")
            ),
            adTagUrl = VMAP_PRE_MID_POST,
        ),
        VideoContent(
            id = "tears-clear-skippable",
            title = "Tears of Steel — DASH en clair + pre-roll skippable (VAST seul)",
            streamUrl = "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd",
            streamType = StreamType.DASH,
            adTagUrl = VAST_SKIPPABLE_PREROLL,
        ),
        VideoContent(
            id = "tears-widevine-noads",
            title = "Tears of Steel — DASH + Widevine, sans pub",
            streamUrl = "https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd",
            streamType = StreamType.DASH,
            drm = DrmConfig(
                scheme = DrmScheme.WIDEVINE,
                licenseUrl = "https://proxy.uat.widevine.com/proxy?video_id=2015_tears&provider=widevine_test",
            ),
        ),
        VideoContent(
            id = "drm-error",
            title = "Démo erreur — licence Widevine invalide",
            streamUrl = "https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd",
            streamType = StreamType.DASH,
            drm = DrmConfig(DrmScheme.WIDEVINE, licenseUrl = "https://proxy.uat.widevine.com/proxy?provider=invalid"),
        ),
    )

    override suspend fun getCatalog(): List<VideoContent> = catalog

    override suspend fun getContent(id: String): VideoContent =
        catalog.firstOrNull { it.id == id } ?: throw NoSuchElementException("Unknown content $id")

    private companion object {
        const val VMAP_PRE_MID_POST =
            "https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/vmap_ad_samples" +
                "&sz=640x480&cust_params=sample_ar%3Dpremidpost&ciu_szs=300x250&gdfp_req=1&ad_rule=1" +
                "&output=vmap&unviewed_position_start=1&env=vp&impl=s&cmsid=496&vid=short_onecue&correlator="
        const val VAST_SKIPPABLE_PREROLL =
            "https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_preroll_skippable" +
                "&sz=640x480&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1" +
                "&env=vp&impl=s&correlator="
    }
}
