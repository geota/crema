package coffee.crema.ui

import coffee.crema.core.MachineState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F16a on Android: the core's wake hint is one one-shot tick, replaced by each
 * hint, cancelled cleanly and never firing once cancelled; the Settings
 * "Run now" buttons follow the core's rule (Idle or Sleep).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachineRequestsShellTest {
    private fun timer(scope: TestScope, ticks: MutableList<Long>) = WakeTimer(
        scope = scope,
        now = { scope.testScheduler.currentTime },
        tick = { ticks += it },
        context = StandardTestDispatcher(scope.testScheduler),
    )

    @Test
    fun `the wake tick comes at the hinted time`() {
        val scope = TestScope()
        val ticks = mutableListOf<Long>()
        val wake = timer(scope, ticks)
        wake.arm(500)
        scope.advanceTimeBy(499)
        scope.runCurrent()
        assertEquals(emptyList(), ticks)
        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertEquals(listOf(500L), ticks)
        assertFalse(wake.pending)
    }

    @Test
    fun `at most one wake is pending - a new hint replaces it`() {
        val scope = TestScope()
        val ticks = mutableListOf<Long>()
        val wake = timer(scope, ticks)
        wake.arm(1_500)
        wake.arm(500)
        scope.advanceTimeBy(2_000)
        scope.runCurrent()
        assertEquals(listOf(500L), ticks)
    }

    @Test
    fun `a cancelled wake never fires (backgrounded, link gone, core reset)`() {
        val scope = TestScope()
        val ticks = mutableListOf<Long>()
        val wake = timer(scope, ticks)
        wake.arm(500)
        assertTrue(wake.pending)
        wake.cancel()
        assertFalse(wake.pending)
        scope.advanceTimeBy(10_000)
        scope.runCurrent()
        assertEquals(emptyList(), ticks)
        wake.cancel() // harmless twice
    }

    @Test
    fun `the Run-now buttons follow the core's rule`() {
        assertTrue(MainUiState(machineStateName = MachineState.Idle).maintenanceRunAllowed())
        assertTrue(MainUiState(machineStateName = MachineState.Sleep).maintenanceRunAllowed())
        for (state in listOf(MachineState.Espresso, MachineState.Steam, MachineState.Descale, null)) {
            assertFalse(MainUiState(machineStateName = state).maintenanceRunAllowed(), "$state")
        }
    }
}
