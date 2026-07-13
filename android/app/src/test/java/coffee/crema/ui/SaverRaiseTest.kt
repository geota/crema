package coffee.crema.ui

import coffee.crema.core.MachineState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the screensaver-raise decision. The BLE round-trip that
 * drives it needs a DE1 we don't have; this pins the policy — which sleeps are
 * "the user walked away" (raise the saver) and which are "the user asked for
 * this" (don't).
 */
class SaverRaiseTest {
    /** An app-initiated-sleep marker that counts how often it's spent. */
    private class Marker(private val armed: Boolean) : () -> Boolean {
        var spent = 0
            private set

        override fun invoke(): Boolean {
            spent++
            return armed
        }
    }

    private val external = Marker(armed = false)

    @Test
    fun `an external sleep raises the saver`() {
        assertTrue(shouldRaiseSaver(MachineState.Idle, MachineState.GoingToSleep, external))
        assertTrue(shouldRaiseSaver(MachineState.Idle, MachineState.Sleep, external))
    }

    @Test
    fun `a sleep the app asked for does not`() {
        val marker = Marker(armed = true)
        assertFalse(shouldRaiseSaver(MachineState.Idle, MachineState.GoingToSleep, marker))
    }

    @Test
    fun `the first report after connect does not, even on an asleep DE1`() {
        assertFalse(shouldRaiseSaver(null, MachineState.Sleep, external))
    }

    @Test
    fun `re-stating a sleep we're already in does not`() {
        assertFalse(shouldRaiseSaver(MachineState.GoingToSleep, MachineState.Sleep, external))
        assertFalse(shouldRaiseSaver(MachineState.Sleep, MachineState.Sleep, external))
    }

    @Test
    fun `waking does not`() {
        assertFalse(shouldRaiseSaver(MachineState.Sleep, MachineState.Idle, external))
        assertFalse(shouldRaiseSaver(MachineState.Idle, MachineState.Espresso, external))
    }

    /**
     * The marker is one-shot, so it must only be spent on the transition it
     * speaks for. An unrelated report landing between the sleep write and the
     * machine actually sleeping (a duplicate Idle, say) must not burn it —
     * that would let the real GoingToSleep raise the saver anyway, which is
     * the whole bug.
     */
    @Test
    fun `an unrelated report between the write and the sleep does not spend the marker`() {
        val marker = Marker(armed = true)
        assertFalse(shouldRaiseSaver(MachineState.Idle, MachineState.Idle, marker))
        assertEquals(0, marker.spent)
        assertFalse(shouldRaiseSaver(MachineState.Idle, MachineState.GoingToSleep, marker))
        assertEquals(1, marker.spent)
    }
}
