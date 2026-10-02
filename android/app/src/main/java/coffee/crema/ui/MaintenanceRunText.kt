package coffee.crema.ui

import coffee.crema.core.EventMaintenanceProgressInner
import coffee.crema.core.MachineState
import coffee.crema.core.MaintenancePhase

/**
 * Title + subtitle for the running-maintenance row on Settings → Water
 * (`MainUiState.maintenanceRun`). Same copy as web `WaterSection`: on old DE1
 * firmware (< 1356, or unknown) a cold request is held behind core's 1 °C
 * profile until preheat ends; a descale reads Decenza's
 * "42% · Step 4 of 5 · 7 min 0 s left" from the firmware's fixed 720 s schedule.
 */
internal fun maintenanceRunText(run: EventMaintenanceProgressInner): Pair<String, String> {
    val title = when (run.state) {
        MachineState.Descale -> "Descale"
        MachineState.Clean -> "Clean cycle"
        MachineState.AirPurge -> "Air purge"
        else -> run.state.string
    }
    val sub = when {
        run.phase == MaintenancePhase.WaitingForPreheat ->
            "Waiting for the machine to stop heating (older firmware workaround)…"
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
