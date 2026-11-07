package coffee.crema.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each reason the core gives for a configured weight stop being off gets its note. */
class StopTargetsViewLabelTest {
    private fun label(blocked: String?) = StopTargetsView(weightBlocked = blocked).weightBlockedLabel

    @Test
    fun everyBlockedReasonHasALabel() {
        assertEquals("no scale", label("no-scale"))
        assertEquals("Started before Crema connected", label("began-unseen"))
        assertEquals("cup not tared", label("untared-cup"))
        assertEquals(null, label(null))
    }
}
