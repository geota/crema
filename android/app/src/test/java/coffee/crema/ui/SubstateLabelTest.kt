package coffee.crema.ui

import coffee.crema.ui.screens.substateLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/** Substate 0x11 is the refill kit giving up on a refill (decaid ac3e433d). */
class SubstateLabelTest {
    @Test
    fun `refill stopped reads as such`() {
        assertEquals("Refill stopped", substateLabel("RefillStopped"))
    }

    @Test
    fun `other substates keep their names`() {
        assertEquals("Heating", substateLabel("Heating"))
    }
}
