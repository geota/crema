package coffee.crema.ui

import coffee.crema.core.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals

/** An estimated weight stop wears the weight row's icon: it stood in for it. */
class StopIconTest {
    @Test
    fun `an estimated stop wears the weight icon`() {
        assertEquals(stopIcon(StopReason.Weight), stopIcon(StopReason.EstimatedWeight))
        assertEquals("drop-half", stopIcon(StopReason.Volume))
    }
}
