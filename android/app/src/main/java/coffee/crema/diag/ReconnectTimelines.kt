package coffee.crema.diag

import android.os.SystemClock
import coffee.crema.ble.ReconnectTimelineRecorder

/**
 * The process-wide reconnect-timeline recorder (see [ReconnectTimelineRecorder]):
 * the BLE managers write to it, Settings → Advanced lists it, and the
 * diagnostics snapshot embeds it — process-scoped like [DiagLog], so the
 * crash handler can still read it.
 */
object ReconnectTimelines {
    val recorder = ReconnectTimelineRecorder(nowMs = SystemClock::elapsedRealtime)

    /** Newest first, one compact line each — for the diagnostics snapshot. */
    fun snapshotLines(): List<String> = recorder.timelines.value.map { t ->
        val at = android.text.format.DateFormat.format("HH:mm:ss", t.startedWallMs)
        "$at  ${t.compactLine()}"
    }
}
