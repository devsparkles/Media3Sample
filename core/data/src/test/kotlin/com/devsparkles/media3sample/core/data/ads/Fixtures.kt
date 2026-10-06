package com.devsparkles.media3sample.core.data.ads

/**
 * Fixtures XML : exemples minimalistes mais réalistes de VMAP / VAST.
 * En vrai projet, on les met dans src/test/resources/ et on ajoute à chaque bug de prod
 * le XML fautif reçu de la régie (test de non-régression).
 */
object Fixtures {

    const val VMAP = """<?xml version="1.0" encoding="UTF-8"?>
<vmap:VMAP xmlns:vmap="http://www.iab.net/videosuite/vmap" version="1.0">
  <vmap:AdBreak timeOffset="start" breakType="linear" breakId="preroll">
    <vmap:AdSource id="pre" allowMultipleAds="false" followRedirects="true">
      <vmap:AdTagURI templateType="vast3"><![CDATA[https://ads.test/wrapper.xml]]></vmap:AdTagURI>
    </vmap:AdSource>
    <vmap:TrackingEvents>
      <vmap:Tracking event="breakStart"><![CDATA[https://track.test/breakStart]]></vmap:Tracking>
    </vmap:TrackingEvents>
  </vmap:AdBreak>
  <vmap:AdBreak timeOffset="00:00:15.000" breakType="linear" breakId="midroll-1">
    <vmap:AdSource id="mid" allowMultipleAds="true" followRedirects="true">
      <vmap:VASTAdData>
        <VAST version="3.0">
          <Ad id="mid-ad" sequence="1">
            <InLine>
              <Impression><![CDATA[https://track.test/mid/impression]]></Impression>
              <Creatives><Creative><Linear>
                <Duration>00:00:10</Duration>
                <MediaFiles>
                  <MediaFile delivery="progressive" type="video/mp4" width="640" height="360" bitrate="500"><![CDATA[https://cdn.test/mid.mp4]]></MediaFile>
                </MediaFiles>
              </Linear></Creative></Creatives>
            </InLine>
          </Ad>
        </VAST>
      </vmap:VASTAdData>
    </vmap:AdSource>
  </vmap:AdBreak>
  <vmap:AdBreak timeOffset="end" breakType="linear" breakId="postroll">
    <vmap:AdSource id="post"><vmap:AdTagURI><![CDATA[https://ads.test/empty.xml]]></vmap:AdTagURI></vmap:AdSource>
  </vmap:AdBreak>
</vmap:VMAP>"""

    const val WRAPPER = """<VAST version="3.0">
  <Ad id="wrapper-1">
    <Wrapper>
      <AdSystem>Ad Proxy</AdSystem>
      <VASTAdTagURI><![CDATA[https://ads.test/inline.xml]]></VASTAdTagURI>
      <Impression><![CDATA[https://track.test/wrapper/impression]]></Impression>
      <Error><![CDATA[https://track.test/wrapper/error?code=[ERRORCODE]]]></Error>
      <Creatives><Creative><Linear><TrackingEvents>
        <Tracking event="complete"><![CDATA[https://track.test/wrapper/complete]]></Tracking>
      </TrackingEvents></Linear></Creative></Creatives>
    </Wrapper>
  </Ad>
</VAST>"""

    const val INLINE = """<VAST version="4.0">
  <Ad id="inline-1" sequence="1">
    <InLine>
      <AdSystem>Test</AdSystem>
      <Impression><![CDATA[https://track.test/inline/impression]]></Impression>
      <Error><![CDATA[https://track.test/inline/error?code=[ERRORCODE]]]></Error>
      <Creatives><Creative><Linear skipoffset="00:00:05">
        <Duration>00:00:20.500</Duration>
        <TrackingEvents>
          <Tracking event="start"><![CDATA[https://track.test/inline/start]]></Tracking>
          <Tracking event="firstQuartile"><![CDATA[https://track.test/inline/q1]]></Tracking>
          <Tracking event="complete"><![CDATA[https://track.test/inline/complete]]></Tracking>
        </TrackingEvents>
        <VideoClicks><ClickThrough><![CDATA[https://advertiser.test]]></ClickThrough></VideoClicks>
        <MediaFiles>
          <MediaFile delivery="progressive" type="video/mp4" width="1920" height="1080" bitrate="4500"><![CDATA[https://cdn.test/1080.mp4]]></MediaFile>
          <MediaFile delivery="progressive" type="video/mp4" width="1280" height="720" bitrate="1800"><![CDATA[https://cdn.test/720.mp4]]></MediaFile>
          <MediaFile apiFramework="VPAID" type="application/javascript"><![CDATA[https://cdn.test/vpaid.js]]></MediaFile>
        </MediaFiles>
      </Linear></Creative></Creatives>
    </InLine>
  </Ad>
</VAST>"""

    const val EMPTY_VAST = """<VAST version="3.0"><Error><![CDATA[https://track.test/noad?code=[ERRORCODE]]]></Error></VAST>"""
}
