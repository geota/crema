package coffee.crema.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the rail's compact/regular switch (issue 06): short rails (a phone in
 * landscape) go compact so all six destinations + both pips fit; a 7" tablet in
 * landscape (≈600dp) and taller keep the regular labelled rail.
 */
class RailDensityTest {
    @Test
    fun `phone landscape heights are compact`() {
        assertEquals(RailDensity.Compact, railDensity(320f))
        assertEquals(RailDensity.Compact, railDensity(360f))
        assertEquals(RailDensity.Compact, railDensity(426f))
    }

    @Test
    fun `just under the threshold is compact, at it is regular`() {
        assertEquals(RailDensity.Compact, railDensity(COMPACT_RAIL_BELOW_DP - 0.5f))
        assertEquals(RailDensity.Regular, railDensity(COMPACT_RAIL_BELOW_DP))
    }

    @Test
    fun `tablet landscape heights stay regular`() {
        assertEquals(RailDensity.Regular, railDensity(600f))
        assertEquals(RailDensity.Regular, railDensity(800f))
    }
}
