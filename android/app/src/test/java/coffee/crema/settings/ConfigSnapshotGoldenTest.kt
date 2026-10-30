package coffee.crema.settings

import coffee.crema.ble.proxy.Golden
import coffee.crema.core.HotWaterBasis
import coffee.crema.core.HotWaterMode
import coffee.crema.core.HotWaterTarget
import coffee.crema.ui.MainUiState
import coffee.crema.ui.coreJson
import coffee.crema.ui.knownOnRemote
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The v4 config snapshot (relay v4 R3/R3b, spec §4.9, AC-42), pinned by
 * `config-snapshot/schema.json`. There are no unit fields and no
 * `settingsSchema`, and the fields every v4 hub sends are required: a
 * snapshot missing one fails to decode, and the remote drops it.
 */
class ConfigSnapshotGoldenTest {

    /** A v4 hub's snapshot, every machine register known. */
    private val snapshot = ConfigSnapshot(
        activeProfileId = "019e5fb5-63b5-7333-a2ac-34c516215b2c", activeBeanId = "bean:s", activeBeanSummary = "S bag",
        stopOnWeight = true, autoTare = true, maxShotDurationS = 44f, grinderModel = "Niche",
        qcSteamTimeS = 30f, qcSteamFlowMlS = 1.6f, qcSteamTempC = 150f, qcHotWaterTempC = 85f, qcHotWaterVolumeMl = 200f,
        qcFlushTimeS = 6f, qcFlushTempC = 93f, qcGrind = 15f,
        qcHotWaterMode = HotWaterMode.Time, qcHotWaterTimeS = 45f, qcHotWaterTimeNoLimit = true, qcHotWaterRatio = 2.5f,
        hotWaterTarget = HotWaterTarget(
            mode = HotWaterMode.Time, timeS = 45f, basis = HotWaterBasis.NotApplicable,
            wireVolumeMl = 0f, wireTimeoutS = 45f,
        ),
        brewDoseG = 20.0, brewYieldG = 40.0, brewTempC = 94.0, brewPreinfuseS = 6.0, primaryName = "Old tablet",
        rev = 7,
        steamEco = true, steamTwoTap = true, fanThresholdC = 58f, waterRefillPointMm = 8f, heaterVoltage = 230,
        flowMultiplier = 1.1f, lineFrequencyHz = 50f, lineFrequencyOverride = 0f, cupWarmerTempC = 60,
        volumeStopWithScale = true, requireScale = true, preFlush = true, steamPurge = true, idleSleepMin = 60,
    )

    @Test
    fun `the fixture is what a v4 hub sends`() {
        Golden.check("config-snapshot/schema.json", coreJson.encodeToString(ConfigSnapshot.serializer(), snapshot))
        val keys = coreJson.parseToJsonElement(Golden.require("config-snapshot/schema.json")).jsonObject.keys
        for (gone in listOf("settingsSchema", "weightUnit", "tempUnit", "pressureUnit", "volumeUnit")) {
            assertTrue(gone !in keys, "$gone is not sent")
        }
    }

    /** A snapshot without one of the fields every v4 hub sends (an older
     *  hub's, or a broken one) fails to decode: never applied with defaults. */
    @Test
    fun `a snapshot missing a required field fails to decode`() {
        val full = coreJson.parseToJsonElement(Golden.require("config-snapshot/schema.json")).jsonObject
        for (required in listOf("rev", "steamEco", "steamTwoTap", "fanThresholdC", "waterRefillPointMm", "lineFrequencyOverride",
            "volumeStopWithScale", "requireScale", "preFlush", "steamPurge", "idleSleepMin")) {
            val without = JsonObject(full - required).toString()
            assertFailsWith<SerializationException>(required) { coreJson.decodeFromString(ConfigSnapshot.serializer(), without) }
        }
    }

    @Test
    fun `a v4 snapshot carries every machine and session setting`() {
        val remote = MainUiState(mirror = MirroredSettings().applied(snapshot, null, null))
        val unknown = Setting.entries.filter { it.mirroredVia == MirrorSource.SNAPSHOT && !remote.knownOnRemote(it) }
        assertTrue(unknown.isEmpty(), "$unknown")
    }

    /** AC-HW24: the four hot-water dose fields and the hub's target round-trip;
     *  null they read as unknown ("Waiting for <hub>"), never as defaults. */
    @Test
    fun `hot-water dose fields round-trip, and null reads as unknown`() {
        val back = coreJson.decodeFromString(ConfigSnapshot.serializer(), coreJson.encodeToString(ConfigSnapshot.serializer(), snapshot))
        kotlin.test.assertEquals(snapshot, back)
        val unsent = snapshot.copy(
            qcHotWaterMode = null, qcHotWaterTimeS = null, qcHotWaterTimeNoLimit = null, qcHotWaterRatio = null, hotWaterTarget = null,
        )
        val remote = MainUiState(mirror = MirroredSettings().applied(unsent, null, null))
        val unknown = Setting.entries.filter { it.mirroredVia == MirrorSource.SNAPSHOT && !remote.knownOnRemote(it) }.toSet()
        kotlin.test.assertEquals(
            setOf(Setting.QC_HOT_WATER_MODE, Setting.QC_HOT_WATER_TIME, Setting.QC_HOT_WATER_TIME_NO_LIMIT, Setting.QC_HOT_WATER_RATIO),
            unknown,
        )
    }

    /** The machine registers a hub may not have read yet stay unknown until it has. */
    @Test
    fun `unread machine registers are unknown`() {
        val unread = snapshot.copy(heaterVoltage = null, flowMultiplier = null, cupWarmerTempC = null)
        val remote = MainUiState(mirror = MirroredSettings().applied(unread, null, null))
        val unknown = Setting.entries.filter { it.mirroredVia == MirrorSource.SNAPSHOT && !remote.knownOnRemote(it) }.toSet()
        kotlin.test.assertEquals(setOf(Setting.HEATER_VOLTAGE, Setting.FLOW_MULTIPLIER, Setting.CUP_WARMER_TEMP), unknown)
    }
}
