package com.devsparkles.media3sample.core.data.tracking.nielsen

/** Faux SDK Nielsen : enregistre chaque appel sous une forme lisible (« stop() », « setPlayheadPosition(3) »...). */
class RecordingNielsenSdk : NielsenSdkGateway {
    val calls = mutableListOf<String>()
    val metadata = mutableListOf<Map<String, String>>()

    override fun play(channelInfo: Map<String, String>) { calls += "play" }
    override fun loadMetadata(metadata: Map<String, String>) {
        this.metadata += metadata
        calls += "loadMetadata(${metadata["type"]})"
    }
    override fun setPlayheadPosition(positionSeconds: Long) { calls += "setPlayheadPosition($positionSeconds)" }
    override fun stop() { calls += "stop" }
    override fun end() { calls += "end" }
    override fun appInBackground() { calls += "appInBackground" }
    override fun appInForeground() { calls += "appInForeground" }
    override fun close() { calls += "close" }
}
