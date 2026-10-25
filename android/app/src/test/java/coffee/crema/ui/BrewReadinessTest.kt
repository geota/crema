package coffee.crema.ui

import coffee.crema.core.MachineState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Brew hero's one readiness rule: no state from the DE1 yet means
 * syncing, never ready. A GATT link that came up at the edge of range and
 * then answered nothing used to read "Ready to brew".
 */
class BrewReadinessTest {
    @Test fun noLinkIsNotConnectedWhateverTheStaleStateSays() {
        assertEquals(BrewReadiness.NOT_CONNECTED, brewReadiness(false, null, null))
        assertEquals(BrewReadiness.NOT_CONNECTED, brewReadiness(false, MachineState.Idle, "Ready"))
    }

    @Test fun aLinkWithoutAReportedStateIsSyncingNotReady() {
        assertEquals(BrewReadiness.SYNCING, brewReadiness(true, null, null))
        assertEquals(BrewReadiness.SYNCING, brewReadiness(true, null, "Heating"))
    }

    @Test fun aReportedStateDecidesTheRest() {
        assertEquals(BrewReadiness.ASLEEP, brewReadiness(true, MachineState.Sleep, null))
        assertEquals(BrewReadiness.HEATING, brewReadiness(true, MachineState.Idle, "Heating"))
        assertEquals(BrewReadiness.READY, brewReadiness(true, MachineState.Idle, "Ready"))
        assertEquals(BrewReadiness.READY, brewReadiness(true, MachineState.Idle, null))
    }
}
