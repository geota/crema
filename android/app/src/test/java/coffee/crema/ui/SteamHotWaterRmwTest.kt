package coffee.crema.ui

import coffee.crema.core.EventShotSettingsReadInner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-JVM tests for the issue-14 steam/hot-water read-modify-write. The actual
 * BLE write to cuuid_0B needs a DE1 we don't have; this pins the field-merge
 * logic that decides which bytes get sent — the part most prone to silent drift.
 */
class SteamHotWaterRmwTest {
    private fun machine() = EventShotSettingsReadInner(
        steam_temp = 160f,
        steam_timeout = 30f,
        hot_water_temp = 90f,
        hot_water_volume = 200f,
        hot_water_timeout = 45f,
        espresso_volume = 220f,
        group_temp = 88f,
    )

    @Test
    fun `QC values drive the four modeled fields`() {
        val s = buildSteamHotWaterSettings(
            steamTempC = 148f,
            steamTimeoutS = 12f,
            hotWaterTempC = 80f,
            hotWaterVolumeMl = 150f,
            machine = machine(),
        )
        assertEquals(148f, s.steamTempC)
        assertEquals(12f, s.steamTimeoutS)
        assertEquals(80f, s.hotWaterTempC)
        assertEquals(150f, s.hotWaterVolumeMl)
    }

    @Test
    fun `unmodeled fields are preserved from the machine's last report`() {
        val s = buildSteamHotWaterSettings(148f, 12f, 80f, 150f, machine())
        assertEquals(45f, s.hotWaterTimeoutS)
        assertEquals(220f, s.espressoVolumeMl)
        assertEquals(88f, s.groupTempC)
    }

    @Test
    fun `legacy defaults stand in before the machine has reported`() {
        val s = buildSteamHotWaterSettings(148f, 12f, 80f, 150f, machine = null)
        assertEquals(60f, s.hotWaterTimeoutS)
        assertEquals(200f, s.espressoVolumeMl)
        assertEquals(92f, s.groupTempC)
    }

    @Test
    fun `steam flags are always zero (legacy parity)`() {
        val s = buildSteamHotWaterSettings(148f, 12f, 80f, 150f, machine())
        assertEquals(0u.toUByte(), s.steamFlags)
    }
}
