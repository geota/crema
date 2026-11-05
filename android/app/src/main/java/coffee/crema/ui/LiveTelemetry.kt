package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * The high-rate readouts: DE1 samples (25 to 40 Hz in a shot), scale readings,
 * the tank level, the mode clock and the debug log. They live in a flow of
 * their own ([MainViewModel.live]), apart from [MainUiState], so a sample
 * recomposes only the composables that show it.
 *
 * On a remote these come from its own core, fed the hub's relayed
 * notifications, as on any device: no mirror slice overlays them and no
 * config snapshot carries them. Nothing here is persisted or backed up.
 */
@Immutable
data class LiveTelemetry(
    // Projected from `Event.Telemetry`; null until the first sample of a session.
    /** Group pressure, bar. */
    val pressure: Float? = null,
    /** Group flow, ml/s. */
    val flow: Float? = null,
    /** Group-head temperature, °C (the COFFEE channel). */
    val headTemp: Float? = null,
    /** Mix temperature, °C — the DE1's blended group water (COFFEE-card secondary). */
    val mixTemp: Float? = null,
    /** Steam-heater temperature, °C (foot "Steam" readout). */
    val steamTemp: Float? = null,
    /** Running shot volume dispensed so far, ml — integrated by the core. */
    val dispensedVolume: Float? = null,
    /** Puck resistance, `bar/(ml/s)²` — pump-flow derived, or null near zero flow. */
    val resistance: Float? = null,
    /** Scale-derived puck resistance, `bar/(g/s)²` — preferred when a scale is paired. */
    val resistanceWeight: Float? = null,
    /** Milliseconds since the shot began (`Event.Telemetry.elapsed`); resets each shot. */
    val shotElapsedMs: Long = 0,
    /**
     * Buffered per-sample telemetry for the live shot chart — appended on each
     * `Event.Telemetry` while [MainUiState.shotInProgress], reset on
     * `ShotStarted`, capped at [SHOT_TELEMETRY_CAP]. Empty when no shot is
     * running. The scalar fields above stay live outside a shot (for the
     * resting channel cards); this series only fills during extraction (when
     * the chart draws curves).
     */
    val shotTelemetry: List<TelemetrySample> = emptyList(),
    /** Latest scale weight in grams, or null before the first reading. */
    val scaleWeightG: Float? = null,
    /**
     * Latest scale-reported native mass-flow rate in g/s, or null — only
     * scales that report their own flow (the Bookoo) populate this; it is
     * distinct from the core-computed flow.
     */
    val scaleFlowGPerS: Float? = null,
    /**
     * Latest scale-reported built-in-timer reading in milliseconds, or null —
     * only scales with a built-in timer (the Bookoo) populate this.
     */
    val scaleTimerMs: Long? = null,
    /** Latest tank DEPTH, mm (`Event.WaterLevel.level` — raw + core's 5 mm
     *  sensor offset, smoothed), or null before the first report. */
    val waterLevelMm: Float? = null,
    /** Milliseconds since the current service mode began — ticked at 4 Hz
     *  while a mode runs (the steam / hot-water / flush timers); 0 when idle. */
    val modeElapsedMs: Long = 0,
    /** A rolling event log, newest first. */
    val eventLog: List<String> = emptyList(),
)

/** The latest sample as the debug readout's line, or null before the first. */
fun LiveTelemetry.debugLine(): String? = pressure?.let {
    fmt("t=%dms  P=%.1fbar  flow=%.1fmL/s  head=%.1f°C", shotElapsedMs, it, flow, headTemp)
}

/**
 * [content] over the latest [live] readouts, collected here so that a sample
 * recomposes [content] and not the screen around it. It adds no layout, so
 * parent-scope modifiers such as `weight` work inside [content].
 */
@Composable
fun LiveReadouts(live: StateFlow<LiveTelemetry>, content: @Composable (LiveTelemetry) -> Unit) {
    val latest by live.collectAsStateWithLifecycle()
    content(latest)
}
