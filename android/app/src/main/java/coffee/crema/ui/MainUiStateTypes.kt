package coffee.crema.ui

import coffee.crema.history.StoredShot

/**
 * Summary of the most recently completed espresso shot — assembled from
 * `Event.ShotCompleted` for the Brew screen's "Last shot" card. Null until a
 * shot finishes; held until the next shot starts.
 */
data class LastShot(
    /** Total shot duration, milliseconds. */
    val durationMs: Long,
    /** Final scale weight before the shot ended (the yield), grams, or null. */
    val yieldG: Float?,
    /** Peak group pressure across the shot, bar, or null. */
    val peakPressure: Float?,
    /** Peak group-head temperature across the shot, °C, or null. */
    val peakTemp: Float?,
    /** Unix epoch ms the shot completed — drives the card's "· N min ago" eyebrow. */
    val completedAtMs: Long = 0,
    /** The matching [StoredShot] id (a shared UUID-v7 `newShotId()`), so tapping
     *  the card opens it in History. Null for the pre-first-shot default. */
    val id: String? = null,
)

/**
 * The core's stop-target projection, mirrored into the UI snapshot.
 *
 * [armed] distinguishes "a shot is running and these ARE the armed targets"
 * from "this is what would arm if you pulled now". Every field null with
 * [armed] false means nothing whatsoever will stop the next shot — the state
 * that used to be invisible.
 */
data class StopTargetsView(
    val weightG: Float? = null,
    val volumeMl: Float? = null,
    val maxTimeS: Float? = null,
    val armed: Boolean = false,
    /** The weight target that WOULD apply, armed or not — so a row the user
     *  configured stays on screen when a scale drops instead of vanishing. */
    val weightConfiguredG: Float? = null,
    /** Why [weightG] is null despite [weightConfiguredG] being set —
     *  `"no-scale"` or `"untared-cup"`; null when armed, unset, or the user
     *  turned the stop off. */
    val weightBlocked: String? = null,
) {
    /** Human reason for a configured-but-unarmable weight stop. */
    val weightBlockedLabel: String? get() = when (weightBlocked) {
        "no-scale" -> "no scale"
        "untared-cup" -> "cup not tared"
        else -> null
    }

    /** True when no leg will fire — the shot would run until the machine's own
     *  profile limits end it. */
    val none: Boolean get() = weightG == null && volumeMl == null && maxTimeS == null
}

/**
 * The guided-brew session's SHELL state (issue #10) — the core's
 * `BrewSessionMonitor` owns the truth (boundaries, cues, the recording);
 * this holds what the Scale screen's Brew segment renders between events.
 * Timestamps are `SystemClock.elapsedRealtime()` — the same monotonic
 * clock every BLE notification stamps into the core, so the shell clock
 * and the core's step boundaries can't drift apart.
 */
data class GuidedBrewUi(
    /** idle | armed | running | paused | done. */
    val phase: String = "idle",
    val recipe: coffee.crema.core.BrewRecipe? = null,
    val startOnPour: Boolean = false,
    val stepIndex: Int = 0,
    /** elapsedRealtime() the clock started; null before Started. */
    val startedAtRealtime: Long? = null,
    /** Session-elapsed ms the current step began (from BrewStepChanged). */
    val stepStartedAtMs: Long = 0L,
    /** Accumulated paused time, ms. */
    val pausedAccumMs: Long = 0L,
    /** elapsedRealtime() the current pause began; null while running. */
    val pausedSinceRealtime: Long? = null,
    /** The finished session's summary, until saved or discarded. */
    val summary: coffee.crema.core.BrewSessionSummary? = null,
    /**
     * The last visual cue (always on, whatever the sound / haptics settings):
     * elapsedRealtime() it fired and its kind (`approach` | `step` |
     * `boundary`). The step card flashes for a moment after it — keyed on the
     * timestamp, so a recomposition after rotation doesn't replay an old cue.
     */
    val cueAtRealtime: Long? = null,
    val cueKind: String? = null,
) {
    /** Armed, running or paused — a session the screen must stay up for. */
    val live: Boolean get() = phase == "armed" || phase == "running" || phase == "paused"

    /** Session time at wall-clock [nowRealtime], pauses excluded. */
    fun elapsedMs(nowRealtime: Long): Long {
        val started = startedAtRealtime ?: return 0L
        val effective = pausedSinceRealtime ?: nowRealtime
        return (effective - started - pausedAccumMs).coerceAtLeast(0L)
    }
}

/**
 * A remote this hub has paired (relay v4: one of the store's pins), as the
 * Paired devices list shows it. [canControl] false marks a view-only remote.
 */
data class PairedDevice(
    val id: String,
    val name: String,
    val canControl: Boolean = true,
)
