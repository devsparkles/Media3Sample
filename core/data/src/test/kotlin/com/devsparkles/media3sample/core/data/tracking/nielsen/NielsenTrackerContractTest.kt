package com.devsparkles.media3sample.core.data.tracking.nielsen

import com.devsparkles.media3sample.core.data.tracking.PlaybackTrackerContractTest
import com.devsparkles.media3sample.core.domain.tracking.PlaybackTracker

/**
 * Nielsen passe la suite de contrat. Traduction des appels SDK en catégories :
 * le premier play() d'une session = ouverture, end() = fermeture, play() après une pause = activité.
 */
class NielsenTrackerContractTest : PlaybackTrackerContractTest() {

    private val sdk = RecordingNielsenSdk()

    override fun newTracker(): PlaybackTracker = NielsenTracker(sdk)

    override fun outputs(): List<Output> {
        var open = false
        return sdk.calls.map { call ->
            when (call) {
                "play" -> if (open) Output.Activity(call) else Output.Open.also { open = true }
                "end" -> Output.Close.also { open = false }
                "appInBackground", "appInForeground", "close" -> Output.AppLifecycle(call)
                else -> Output.Activity(call)
            }
        }
    }
}
