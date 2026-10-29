package coffee.crema.ui.screens

import coffee.crema.core.MmrRegister
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The heater-voltage readout decodes through the core (cohort review 2026-10-28, #2 N7). */
class HeaterVoltageLabelTest {

    private fun info(raw: UInt?) = if (raw == null) emptyMap() else mapOf(MmrRegister.HeaterVoltage to raw)

    @Test
    fun `the firmware +1000 marker decodes to 120 or 230 V`() {
        assertEquals("120 V", heaterVoltageLabel(info(1120u)))
        assertEquals("230 V", heaterVoltageLabel(info(1230u)))
        assertEquals("120 V", heaterVoltageLabel(info(120u)))
        assertEquals("230", heaterVoltageValue(info(1230u)))
    }

    @Test
    fun `a doubled marker is not recognised instead of reading 1120 V`() {
        assertEquals("Not recognised — set it again", heaterVoltageLabel(info(2120u)))
        assertNull(heaterVoltageValue(info(2120u)))
    }

    @Test
    fun `unread and not set stay distinct`() {
        assertEquals("—", heaterVoltageLabel(info(null)))
        assertEquals("Not set", heaterVoltageLabel(info(0u)))
        assertNull(heaterVoltageValue(info(0u)))
    }
}
