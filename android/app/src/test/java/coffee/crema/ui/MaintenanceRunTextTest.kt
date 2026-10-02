package coffee.crema.ui

import coffee.crema.core.EventMaintenanceProgressInner
import coffee.crema.core.MachineState
import coffee.crema.core.MaintenancePhase
import kotlin.test.Test
import kotlin.test.assertEquals

/** The running-maintenance row copy (web `WaterSection` parity). */
class MaintenanceRunTextTest {
    private fun run(
        phase: MaintenancePhase,
        state: MachineState = MachineState.Descale,
        step: Int = 0,
        progress: Float = 0f,
        remaining: Int = 0,
        cycle: Int = 0,
        cold: Boolean = false,
    ) = EventMaintenanceProgressInner(
        state = state,
        phase = phase,
        step_index = step.toUByte(),
        step_count = (if (state == MachineState.Descale) 5 else 0).toUByte(),
        progress = progress,
        seconds_remaining = remaining.toUInt(),
        cycle = cycle.toUByte(),
        cold_workaround = cold,
    )

    @Test
    fun descaleReadsTheScheduleCountdown() {
        val (title, sub) = maintenanceRunText(run(MaintenancePhase.Running, step = 4, progress = 0.42f, remaining = 420, cycle = 1))
        assertEquals("Descale", title)
        assertEquals("42% · Step 4 of 5 · 7 min 0 s left", sub)
    }

    @Test
    fun aSecondPassIsNamed() {
        val (_, sub) = maintenanceRunText(run(MaintenancePhase.Running, step = 2, progress = 0.1f, remaining = 650, cycle = 2))
        assertEquals("10% · Step 2 of 5 · 10 min 50 s left · pass 2", sub)
    }

    @Test
    fun aHeldColdRequestSaysWhy() {
        val (title, sub) = maintenanceRunText(run(MaintenancePhase.WaitingForPreheat, state = MachineState.Clean, cold = true))
        assertEquals("Clean cycle", title)
        assertEquals("Waiting for the machine to stop heating (older firmware workaround)…", sub)
    }

    @Test
    fun cleanRunningHasNoCountdown() {
        assertEquals("Running…", maintenanceRunText(run(MaintenancePhase.Running, state = MachineState.Clean)).second)
        assertEquals("Starting…", maintenanceRunText(run(MaintenancePhase.Requested)).second)
    }
}
