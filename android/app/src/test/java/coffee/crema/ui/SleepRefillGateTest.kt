package coffee.crema.ui

import coffee.crema.core.MachineState
import coffee.crema.core.MmrRegister
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Refill sleep-latch gate (decaid 7a0b0c28 / f8cd9553): the shell must
 * hand core the live state, substate, firmware build and model, and skip the
 * automatic sleep when core says the firmware would latch it. The rule itself
 * is pinned by core tests; this pins the shell wiring.
 */
class SleepRefillGateTest {
    private data class Call(val state: String, val sub: String, val fw: UInt?, val model: UInt?)

    @Test
    fun `passes the live machine identity to core`() {
        val calls = mutableListOf<Call>()
        val info = mapOf(
            MmrRegister.FirmwareVersion to 1352u,
            MmrRegister.MachineModel to 1u,
        )
        val blocked = sleepLatchesInRefill(MachineState.Idle, "Refill", info) { st, sub, fw, model ->
            calls += Call(st, sub, fw, model)
            true
        }
        assertTrue(blocked)
        assertEquals(listOf(Call("Idle", "Refill", 1352u, 1u)), calls)
    }

    @Test
    fun `core's verdict decides`() {
        assertFalse(sleepLatchesInRefill(MachineState.Refill, "Refill", emptyMap()) { _, _, _, _ -> false })
        assertTrue(sleepLatchesInRefill(MachineState.Refill, "Refill", emptyMap()) { _, _, fw, _ -> fw == null })
    }

    @Test
    fun `no machine state means nothing to gate`() {
        var asked = false
        assertFalse(sleepLatchesInRefill(null, null, emptyMap()) { _, _, _, _ -> asked = true; true })
        assertFalse(asked)
    }
}
