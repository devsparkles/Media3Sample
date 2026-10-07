package com.devsparkles.media3sample.core.data.tracking.inhouse

import com.devsparkles.media3sample.core.data.tracking.PlaybackTrackerContractTest
import com.devsparkles.media3sample.core.domain.tracking.PlaybackTracker

/** Le tracker maison passe la même suite de contrat que Nielsen. */
class InHouseTrackerContractTest : PlaybackTrackerContractTest() {

    private val beacons = mutableListOf<InHouseBeacon>()

    override fun newTracker(): PlaybackTracker = InHouseTracker(send = { beacons += it }, heartbeatEveryTicks = 1)

    override fun outputs(): List<Output> = beacons.map { beacon ->
        when (beacon.type) {
            "session_start" -> Output.Open
            "session_end" -> Output.Close
            "app_background", "app_foreground" -> Output.AppLifecycle(beacon.type)
            else -> Output.Activity(beacon.type)
        }
    }
}
