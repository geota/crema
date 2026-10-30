package coffee.crema.settings

import coffee.crema.ble.proxy.Golden
import coffee.crema.ui.MainUiState
import coffee.crema.ui.coreJson
import coffee.crema.ui.effective
import coffee.crema.ui.knownOnRemote
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Config snapshots across versions (spec §6.2, §6.3, AC-28), pinned by two
 * golden fixtures: `config-snapshot/schema1.json`, what a P2 hub sends, and
 * `config-snapshot/legacy-nightly.json`, what a nightly hub from before P2
 * sends (no settings schema). Both are deliberate new fixtures; no existing
 * fixture changes.
 */
class ConfigSnapshotCompatTest {

    /** A pre-P2 hub's snapshot, encoded by the frozen pre-P2 class. */
    private val legacy = LegacyConfigSnapshot(
        activeProfileId = "019e5fb5-63b5-7333-a2ac-34c516215b2c", activeBeanId = "bean:s", activeBeanSummary = "S bag",
        stopOnWeight = true, autoTare = true, maxShotDurationS = 44f, grinderModel = "Niche",
        weightUnit = "oz", tempUnit = "F", pressureUnit = "psi", volumeUnit = "floz",
        qcSteamTimeS = 30f, qcSteamFlowMlS = 1.6f, qcSteamTempC = 150f, qcHotWaterTempC = 85f, qcHotWaterVolumeMl = 200f,
        qcFlushTimeS = 6f, qcFlushTempC = 93f, qcGrind = 15f,
        brewDoseG = 20.0, brewYieldG = 40.0, brewTempC = 94.0, brewPreinfuseS = 6.0, primaryName = "Old tablet",
    )

    /** A P2 hub's snapshot. */
    private val schema1 = ConfigSnapshot(
        activeProfileId = legacy.activeProfileId, activeBeanId = legacy.activeBeanId, activeBeanSummary = legacy.activeBeanSummary,
        stopOnWeight = true, autoTare = true, maxShotDurationS = 44f, grinderModel = "Niche",
        qcSteamTimeS = 30f, qcSteamFlowMlS = 1.6f, qcSteamTempC = 150f, qcHotWaterTempC = 85f, qcHotWaterVolumeMl = 200f,
        qcFlushTimeS = 6f, qcFlushTempC = 93f, qcGrind = 15f,
        brewDoseG = 20.0, brewYieldG = 40.0, brewTempC = 94.0, brewPreinfuseS = 6.0, primaryName = "Old tablet",
        settingsSchema = 1, rev = 7,
        steamEco = true, steamTwoTap = true, fanThresholdC = 58f, waterRefillPointMm = 8f, heaterVoltage = 230,
        flowMultiplier = 1.1f, lineFrequencyHz = 50f, lineFrequencyOverride = 0f, cupWarmerTempC = 60,
        volumeStopWithScale = true, requireScale = true, preFlush = true, steamPurge = true, idleSleepMin = 60,
    )

    @Test
    fun `the fixtures are what each hub version sends`() {
        Golden.check("config-snapshot/schema1.json", coreJson.encodeToString(ConfigSnapshot.serializer(), schema1))
        Golden.check("config-snapshot/legacy-nightly.json", coreJson.encodeToString(LegacyConfigSnapshot.serializer(), legacy))
    }

    /** AC-28: a P2 remote applies a pre-P2 hub's snapshot. The fields it
     *  carries show; the 13 it lacks are unknown (9 of them adoptable);
     *  its units don't apply. */
    @Test
    fun `a P2 remote applies a legacy nightly snapshot`() {
        val cfg = coreJson.decodeFromString(ConfigSnapshot.serializer(), Golden.require("config-snapshot/legacy-nightly.json"))
        val own = MainUiState(weightUnit = "g", tempUnit = "C", steamEco = false, preFlush = false)
        val remote = own.copy(mirror = MirroredSettings().applied(cfg, null, null))
        val shown = remote.effective()
        assertEquals(true, shown.stopOnWeight)
        assertEquals(150f, shown.qcSteamTempC)
        assertEquals(15f, shown.qcGrind)
        assertEquals("g", shown.weightUnit, "units are personal")
        assertEquals(false, shown.steamEco, "not sent: this device's own, shown as unknown")
        val unknown = Setting.entries.filter { it.mirroredVia == MirrorSource.SNAPSHOT && !remote.knownOnRemote(it) }
        assertEquals(
            setOf(
                Setting.STEAM_ECO, Setting.STEAM_TWO_TAP, Setting.FAN_THRESHOLD, Setting.WATER_REFILL_POINT,
                Setting.HEATER_VOLTAGE, Setting.FLOW_MULTIPLIER, Setting.LINE_FREQUENCY, Setting.CUP_WARMER_TEMP,
                Setting.VOLUME_STOP_WITH_SCALE, Setting.REQUIRE_SCALE, Setting.PRE_FLUSH, Setting.STEAM_PURGE, Setting.IDLE_SLEEP,
            ),
            unknown.toSet(),
        )
        assertEquals(9, unknown.count { it.adoptable })
    }

    @Test
    fun `a schema-1 snapshot carries every machine and session setting it can`() {
        val remote = MainUiState(mirror = MirroredSettings().applied(schema1, null, null))
        val unknown = Setting.entries.filter { it.mirroredVia == MirrorSource.SNAPSHOT && !remote.knownOnRemote(it) }
        assertTrue(unknown.isEmpty(), "$unknown")
    }
}

/** A frozen copy of [ConfigSnapshot] as nightlies before settings ownership
 *  shipped it: what a pre-P2 remote decodes, and what a pre-P2 hub encodes. */
@Serializable
private data class LegacyConfigSnapshot(
    val activeProfileId: String? = null,
    val activeProfileJson: String? = null,
    val activeBeanId: String? = null,
    val activeBeanSummary: String? = null,
    val stopOnWeight: Boolean = false,
    val autoTare: Boolean = false,
    val maxShotDurationS: Float = 45f,
    val grinderModel: String = "",
    val weightUnit: String = "g",
    val tempUnit: String = "C",
    val pressureUnit: String = "bar",
    val volumeUnit: String = "ml",
    val qcSteamTimeS: Float = 12f,
    val qcSteamFlowMlS: Float = 1.2f,
    val qcSteamTempC: Float = 148f,
    val qcHotWaterTempC: Float = 80f,
    val qcHotWaterVolumeMl: Float = 150f,
    val qcFlushTimeS: Float = 4f,
    val qcFlushTempC: Float = 95f,
    val qcGrind: Float? = null,
    val brewDoseG: Double? = null,
    val brewYieldG: Double? = null,
    val brewTempC: Double? = null,
    val brewPreinfuseS: Double? = null,
    val primaryName: String = "",
    val authority: String = "primary",
)
