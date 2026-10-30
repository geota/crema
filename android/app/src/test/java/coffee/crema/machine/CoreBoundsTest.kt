package coffee.crema.machine

import coffee.crema.ui.CoreBounds
import coffee.crema.ui.QcBounds
import coffee.crema.ui.QcSteam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * F8: the Quick Controls, relay and editor ranges come from the core's one
 * bounds table (host build of the core). The values are pinned by the core
 * tests; these check Android reads them and refuses what the steppers can't
 * produce.
 */
class CoreBoundsTest {
    @Test
    fun `QcField and AdjustBrew ranges come from the core table`() {
        val q = CoreBounds.qc
        assertEquals(q.flushTimeS.min.toDouble(), QcBounds.FLUSH_TIME_MIN_S)
        assertEquals(q.steamTempC.max.toDouble(), QcSteam.MAX_TEMP_C)
        assertEquals(q.doseG.max.toDouble(), QcBounds.DOSE_MAX_G)

        // Flush time starts at 1 s (decided); 0 is now refused on the relay.
        assertNotNull(QcField.FLUSH_TIME.refusal(0f))
        assertNull(QcField.FLUSH_TIME.refusal(1f))
        assertNotNull(QcField.STEAM_FLOW.refusal(3.1f))
        assertNull(QcField.STEAM_TEMP.refusal(0f), "steam off is always allowed")
        assertNotNull(QcField.STEAM_TEMP.refusal(120f))

        fun brew(dose: Double, yieldOut: Double) = MachineCommand.AdjustBrew(dose, yieldOut, 93.0, null).refusal()
        assertNull(brew(30.0, 36.0))
        assertNotNull(brew(31.0, 36.0), "dose above the stepper")
        assertNull(brew(30.0, 150.0), "the ratio stepper reaches 30 g × 5")
        assertNotNull(brew(30.0, 151.0))
        assertNull(brew(18.0, 0.0), "a yield of 0 means no weight target")
    }

    @Test
    fun `the editor table is the core's`() {
        val e = CoreBounds.editor
        assertEquals(listOf(100f, 12f, 12f, 2000f, 45f), listOf(e.segmentTempC.max, e.pressureBar.max, e.flowMlS.max, e.yieldG.max, e.tankTempC.max))
        assertEquals(60f, CoreBounds.brewDefaults.preinfuseS.max)
    }
}
