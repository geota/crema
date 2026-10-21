package coffee.crema.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The stuck-DE1 hint's threshold — mirrored by the web's `stuckLinkHint`. */
class StuckLinkTest {
    @Test fun aFirstConnectNeverHints() {
        assertNull(stuckLinkHint(wasConnected = false, consecutiveMisses = 10))
    }

    @Test fun oneMissAfterAConnectionIsStillOrdinary() {
        assertNull(stuckLinkHint(wasConnected = true, consecutiveMisses = 1))
    }

    @Test fun twoMissesAfterAConnectionNameTheStuckLink() {
        assertEquals(STUCK_LINK_HINT, stuckLinkHint(wasConnected = true, consecutiveMisses = STUCK_LINK_MISSES))
        assertEquals(STUCK_LINK_HINT, stuckLinkHint(wasConnected = true, consecutiveMisses = 5))
    }
}
