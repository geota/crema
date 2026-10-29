package coffee.crema.ui

import coffee.crema.core.EventMaintenanceProgressInner
import coffee.crema.core.MachineState
import coffee.crema.core.MaintenanceFailure
import coffee.crema.core.MaintenancePhase

/**
 * Title + subtitle for the running-maintenance row on Settings → Water
 * (`MainUiState.maintenanceRun`). Same copy as web `WaterSection`: on old DE1
 * firmware (< 1356, or unknown) a cold request is held until core's 1 °C
 * profile has loaded and preheat has ended; a descale reads Decenza's
 * "42% · Step 4 of 5 · 7 min 0 s left" from the firmware's fixed 720 s schedule.
 */
/** Whether a `MaintenanceProgress` [phase] ends the cycle (finished, cancelled, or never started). */
internal fun isMaintenanceTerminal(phase: MaintenancePhase): Boolean =
    phase == MaintenancePhase.Finished || phase == MaintenancePhase.Cancelled || phase == MaintenancePhase.Failed

/**
 * The notice for a cycle core never started (`MaintenancePhase.Failed`, round-2
 * verification V3 #4), worded by core's [failure]: another profile loaded before
 * the cycle could start (the user picked one or started a shot), or the
 * cold-start profile did not load (round-3 clean check C1 P3-1). Same copy as web
 * `maintenanceFailedMessage`.
 */
internal fun maintenanceFailedText(state: MachineState, failure: MaintenanceFailure?): String =
    if (failure == MaintenanceFailure.ProfileReplaced) {
        "${maintenanceTitle(state)} didn't start: another profile was loaded before the cycle could start. Start it again when you're ready."
    } else {
        "${maintenanceTitle(state)} didn't start: the cold-start profile older DE1 firmware needs could not be loaded. Try again."
    }

private fun maintenanceTitle(state: MachineState): String = when (state) {
    MachineState.Descale -> "Descale"
    MachineState.Clean -> "Clean cycle"
    MachineState.AirPurge -> "Air purge"
    else -> state.string
}

internal fun maintenanceRunText(run: EventMaintenanceProgressInner): Pair<String, String> {
    val title = maintenanceTitle(run.state)
    val sub = when {
        // Held for the cold profile's upload AND the end of preheat (C3 #7).
        run.phase == MaintenancePhase.WaitingForPreheat ->
            "Loading the cold-start profile and waiting for heating to stop (older firmware workaround)…"
        run.phase == MaintenancePhase.Requested -> "Starting…"
        run.step_count.toInt() > 0 && run.step_index.toInt() > 0 -> {
            val pct = (run.progress * 100f).toInt()
            val secs = run.seconds_remaining.toInt()
            val pass = if (run.cycle.toInt() > 1) " · pass ${run.cycle}" else ""
            "$pct% · Step ${run.step_index} of ${run.step_count} · ${secs / 60} min ${secs % 60} s left$pass"
        }
        else -> "Running…"
    }
    return title to sub
}
