package coffee.crema.ui

import coffee.crema.settings.Setting
import coffee.crema.settings.v4Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AC-57: adopting a hub's snapshot (remote → standalone) applies the same
 * range checks as a local edit, before anything reaches this device's DE1. A
 * value the local command would refuse keeps this device's own; one a local
 * edit clamps or snaps is clamped or snapped; an in-range value is taken.
 */
class AdoptionRangeTest {

    private val own = MainUiState(
        qcSteamTempC = 140f, qcSteamTimeS = 20f, qcHotWaterVolumeMl = 200f, qcGrind = 10f,
        fanThresholdC = 50f, waterRefillPointMm = 6f, idleSleepMin = 30, maxShotDurationS = 45f,
        brewParams = BrewParams(18.0, 36.0, 93.0, null),
    )

    private fun adoptAll(cfg: coffee.crema.settings.ConfigSnapshot) =
        adopt(own, cfg, Setting.entries.filter { it.adoptable }.toSet(), hasBean = { true })

    @Test
    fun `out-of-range Quick Controls values keep this device's own`() {
        val hub = v4Snapshot(own).copy(
            qcSteamTempC = 120f, // the band the firmware snaps to off: a local edit refuses it
            qcSteamTimeS = 999f,
            qcHotWaterVolumeMl = 501f,
            qcGrind = -3f,
        )
        val adopted = adoptAll(hub)
        assertEquals(140f, adopted.qcSteamTempC)
        assertEquals(20f, adopted.qcSteamTimeS)
        assertEquals(200f, adopted.qcHotWaterVolumeMl)
        assertEquals(10f, adopted.qcGrind)
    }

    @Test
    fun `in-range values are taken`() {
        val hub = v4Snapshot(own).copy(qcSteamTempC = 150f, qcSteamTimeS = 30f, qcHotWaterVolumeMl = 250f, qcGrind = 14.5f)
        val adopted = adoptAll(hub)
        assertEquals(150f, adopted.qcSteamTempC)
        assertEquals(30f, adopted.qcSteamTimeS)
        assertEquals(250f, adopted.qcHotWaterVolumeMl)
        assertEquals(14.5f, adopted.qcGrind)
        assertEquals(0f, adoptAll(hub.copy(qcSteamTempC = 0f)).qcSteamTempC, "0 turns the steam heater off, as locally")
    }

    @Test
    fun `clamped and snapped settings follow the local edit`() {
        val hub = v4Snapshot(own).copy(fanThresholdC = 90f, waterRefillPointMm = 9_999f, idleSleepMin = 17)
        val adopted = adoptAll(hub)
        assertEquals(60f, adopted.fanThresholdC, "the fan threshold clamps to 0..60")
        assertEquals(REFILL_POINT_MAX_MM, adopted.waterRefillPointMm, "the refill point clamps")
        assertEquals(idleSleepChoices()[idleSleepIndex(17, idleSleepChoices())], adopted.idleSleepMin, "the idle sleep snaps to a choice")
        assertEquals(REFILL_POINT_MIN_MM, adoptAll(hub.copy(waterRefillPointMm = -5f)).waterRefillPointMm)
    }

    @Test
    fun `a max shot duration or brew override the local edit refuses keeps this device's own`() {
        val hub = v4Snapshot(own).copy(maxShotDurationS = -5f, brewDoseG = 1_000.0, brewYieldG = 36.0, brewTempC = 93.0)
        val adopted = adoptAll(hub)
        assertEquals(45f, adopted.maxShotDurationS)
        assertEquals(BrewParams(18.0, 36.0, 93.0, null), adopted.brewParams)
        val fine = adoptAll(hub.copy(maxShotDurationS = 50f, brewDoseG = 20.0, brewYieldG = 40.0, brewTempC = 94.0))
        assertEquals(50f, fine.maxShotDurationS)
        assertEquals(BrewParams(20.0, 40.0, 94.0, null), fine.brewParams)
    }
}
