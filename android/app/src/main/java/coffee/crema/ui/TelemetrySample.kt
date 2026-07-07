package coffee.crema.ui

import kotlinx.serialization.Serializable

/**
 * One buffered telemetry row — the chart's data shape. Mirrors the web shell's
 * `TelemetrySample` (`$lib/state/ui-state.svelte.ts`): the per-sample channels
 * the shot chart plots, assembled in [MainViewModel.applyEvent] on each
 * `Event.Telemetry` (with the scale's weight/flow folded in from the latest
 * `Event.ScaleReading`).
 *
 * Buffered into [MainUiState.shotTelemetry] only while a shot is in progress,
 * capped at [SHOT_TELEMETRY_CAP] (FIFO), and reset on `ShotStarted` — the same
 * lifecycle the web uses. The live scalar fields on [MainUiState] (pressure,
 * flow, …) still update outside a shot to drive the resting channel cards; this
 * series only fills during extraction, which is when the chart draws curves.
 *
 * `@Serializable` so a downsampled slice can persist on a `StoredShot` for the
 * History detail chart.
 */
@Serializable
data class TelemetrySample(
    /** Milliseconds since the shot began; the chart's x is `elapsedMs / 1000` s. */
    val elapsedMs: Long,
    /** Group pressure, bar (plotted raw on the 0–10 scale). */
    val pressure: Float,
    /** Group flow, ml/s (plotted raw). */
    val flow: Float,
    /** Group-head temperature, °C — the COFFEE curve (plotted ÷10). */
    val headTemp: Float,
    /** Mix temperature, °C — the blended group water (plotted ÷10). */
    val mixTemp: Float,
    /** Scale weight, g, or null when no scale is paired (plotted ÷10). */
    val weight: Float?,
    /** Scale mass-flow, g/s, or null (plotted raw). */
    val weightFlow: Float?,
    /** Dispensed volume so far, ml (plotted ÷10). */
    val dispensedVolume: Float,
    /** Puck resistance, `bar/(ml/s)²`, or null near zero flow. */
    val resistance: Float?,
    /** Scale-derived puck resistance, `bar/(g/s)²`, or null. */
    val resistanceWeight: Float?,
    /** Target group-head temperature for the active frame, °C — goal overlay (÷10). */
    val setHeadTemp: Float,
    /** Target group pressure for the active frame, bar — goal overlay (raw). */
    val setGroupPressure: Float,
    /** Target group flow for the active frame, ml/s — goal overlay (raw). */
    val setGroupFlow: Float,
    /**
     * Profile frame executing at this instant. Stamped from the shot's
     * frame tracker at buffer time — persisted so the core's shot-quality
     * analysis gets EXACT phase boundaries instead of a setpoint-step
     * heuristic (review #39). Null on records from before this field.
     */
    val frameNumber: Int? = null,
)

/**
 * Max buffered telemetry samples per shot — matches the web's
 * `MAX_TELEMETRY_SAMPLES`. At ~25 Hz this is ~80 s before the FIFO trims; the
 * chart's x-axis grows past that and old samples scroll off.
 */
const val SHOT_TELEMETRY_CAP: Int = 2000
