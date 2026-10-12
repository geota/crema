package coffee.crema.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A relayed "Take over" is refused while a firmware flash (or its erase /
 * reboot window) holds the DE1 — the flash's own Sleep makes the machine read
 * as idle, which used to grant it (audit r2 H1) — and the requesting device is
 * told why.
 */
class HandoffFirmwareLockTest {
    @Test
    fun aHeldFirmwareLockRefusesTakeoverEvenWhenTheMachineLooksIdle() {
        for (state in listOf("Sleep", "GoingToSleep", "Idle", "SchedIdle", null, "Espresso")) {
            assertEquals(FIRMWARE_BUSY_REASON, handoffRefusal(state, firmwareHoldsLink = true), "state $state")
        }
    }

    @Test
    fun withoutAFlashOnlyIdleStatesAreGranted() {
        assertNull(handoffRefusal("Sleep", firmwareHoldsLink = false))
        assertNull(handoffRefusal("Idle", firmwareHoldsLink = false))
        assertEquals(
            "machine not idle (Espresso) — handoff is idle-only",
            handoffRefusal("Espresso", firmwareHoldsLink = false),
        )
    }

    @Test
    fun theRequesterSeesTheFirmwareReason() {
        // ProxyTransport.control wraps the primary's ControlErr reason.
        assertEquals(
            "Can't take over — the DE1 is updating firmware",
            takeoverRefusedMessage("control rejected: $FIRMWARE_BUSY_REASON"),
        )
        assertEquals("Can't take over — the machine is busy", takeoverRefusedMessage("control rejected: machine not idle"))
        assertEquals("Can't take over — the machine is busy", takeoverRefusedMessage(null))
    }
}
