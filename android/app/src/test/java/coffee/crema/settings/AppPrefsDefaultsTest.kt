package coffee.crema.settings

import coffee.crema.ui.MainUiState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F15: Android's settings defaults, sanitize and reset come from the core
 * (host build): `default_common_settings`, `sanitize_common_settings`,
 * `reset_settings`. The rules themselves are pinned by the core tests in
 * `app_settings.rs`.
 */
class AppPrefsDefaultsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `AppPrefs defaults equal the core default bundle`() {
        assertEquals(CommonDefaults.common, AppPrefs().toCommonSettings())
    }

    @Test
    fun `MainUiState defaults equal AppPrefs defaults`() {
        val p = AppPrefs()
        val s = MainUiState()
        assertEquals(
            listOf(p.themeMode, p.weightUnit, p.tempUnit, p.pressureUnit, p.volumeUnit, p.waterLevelUnit, p.usbChargingMode),
            listOf(s.themeMode, s.weightUnit, s.tempUnit, s.pressureUnit, s.volumeUnit, s.waterLevelUnit, s.usbChargingMode),
        )
        assertEquals(
            listOf(p.autoTare, p.stopOnWeight, p.volumeStopWithScale, p.requireScale, p.steamEco, p.steamTwoTap, p.preFlush, p.steamPurge, p.keepScreenOnBrew, p.showDebugPanel),
            listOf(s.autoTare, s.stopOnWeight, s.volumeStopWithScale, s.requireScale, s.steamEco, s.steamTwoTap, s.preFlush, s.steamPurge, s.keepScreenOnBrew, s.showDebugPanel),
        )
        assertEquals(
            listOf(p.maxShotDurationS, p.fanThresholdC, p.defaultDoseG, p.defaultRatio, p.defaultBrewTempC, p.defaultPreinfuseS,
                p.qcSteamTimeS, p.qcSteamFlowMlS, p.qcSteamTempC, p.qcHotWaterTempC, p.qcHotWaterVolumeMl, p.qcFlushTimeS, p.qcFlushTempC),
            listOf(s.maxShotDurationS, s.fanThresholdC, s.defaultDoseG, s.defaultRatio, s.defaultBrewTempC, s.defaultPreinfuseS,
                s.qcSteamTimeS, s.qcSteamFlowMlS, s.qcSteamTempC, s.qcHotWaterTempC, s.qcHotWaterVolumeMl, s.qcFlushTimeS, s.qcFlushTempC),
        )
        assertEquals(p.chartChannels, s.chartChannels)
        assertEquals(p.grinderModel, s.grinderModel)
        assertEquals(p.baristaName, s.baristaName)
    }

    @Test
    fun `a wrong-typed field in prefs json keeps the rest`() {
        val text = """{"common":{"themeMode":"light","autoTare":"yes","fanThresholdC":400,"weightUnit":"oz"},"proxyRole":"normal"}"""
        val p = decodePrefs(json, text)
        assertEquals("light", p.themeMode)
        assertEquals(true, p.autoTare, "the wrong-typed field reads as its default")
        assertEquals(60f, p.fanThresholdC, "an out-of-range value is clamped")
        assertEquals("oz", p.weightUnit)
    }

    @Test
    fun `resetCommon resets every common field but keeps the weight unit, line frequency and grinder`() {
        val mine = AppPrefs(
            themeMode = "light", weightUnit = "oz", tempUnit = "F", grinderModel = "Niche",
            baristaName = "Ana", qcSteamTempC = 150f, idleSleepMin = 120, waterWarnMl = 300f,
            qcGrind = "12", proxyRole = "primary",
        )
        val (reset, hz) = mine.resetCommon(50f)
        assertEquals(50f, hz)
        assertEquals("oz", reset.weightUnit)
        val d = AppPrefs()
        assertEquals(
            listOf(d.themeMode, d.tempUnit, d.baristaName, d.qcSteamTempC),
            listOf(reset.themeMode, reset.tempUnit, reset.baristaName, reset.qcSteamTempC),
        )
        // #488: the grinder is equipment, kept like the weight unit.
        assertEquals("Niche", reset.grinderModel)
        assertEquals(30, reset.idleSleepMin)
        assertEquals(null, reset.waterWarnMl)
        // Android-only extras and bindings stay.
        assertEquals("12", reset.qcGrind)
        assertEquals("primary", reset.proxyRole)
        assertTrue(mine.resetCommon(55f).second == 0f, "an invalid line frequency reads as auto")
    }

    @Test
    fun `an old flat prefs json loads sanitized`() {
        val text = """{"themeMode":"light","fanThresholdC":400,"weightUnit":"stone","autoTare":"yes","proxyRole":"primary"}"""
        val p = decodePrefs(json, text)
        assertEquals("light", p.themeMode)
        assertEquals(60f, p.fanThresholdC)
        assertEquals("g", p.weightUnit)
        assertEquals(true, p.autoTare)
        assertEquals("primary", p.proxyRole, "platform fields still decode")
    }

    /** #488 round 2: Brew's grind is text; a prefs file from before keeps its number as digits. */
    @org.junit.Test
    fun `an older prefs file's grind number reads as text`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        kotlin.test.assertEquals("15", json.decodeFromString(AppPrefs.serializer(), """{"qcGrind":15.0}""").qcGrind)
        kotlin.test.assertEquals("4.2", json.decodeFromString(AppPrefs.serializer(), """{"qcGrind":4.2}""").qcGrind)
        kotlin.test.assertEquals("2.1.3", json.decodeFromString(AppPrefs.serializer(), """{"qcGrind":"2.1.3"}""").qcGrind)
        kotlin.test.assertEquals("\"2.1.3\"", json.parseToJsonElement(json.encodeToString(AppPrefs.serializer(), AppPrefs(qcGrind = "2.1.3"))).let { (it as kotlinx.serialization.json.JsonObject)["qcGrind"].toString() })
    }
}
