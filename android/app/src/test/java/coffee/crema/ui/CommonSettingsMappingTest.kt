package coffee.crema.ui

import coffee.crema.core.CommonSettings
import coffee.crema.core.HotWaterMode
import coffee.crema.settings.CommonDefaults
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F15: [commonSettings] (what `pushCoreInputs` hands the core) lists the
 * shared fields by hand. A fully non-default bundle goes into a [MainUiState]
 * through every same-named constructor parameter and must come back whole —
 * a field missing from the mapping (or from MainUiState) comes back as its
 * default and fails this. The bundle itself is checked to differ from the
 * defaults in every field, so a new CommonSettings field fails here until it
 * is set below.
 */
class CommonSettingsMappingTest {
    private val json = Json { encodeDefaults = true }

    private val nonDefault = CommonSettings(
        themeMode = "light", maxShotDurationS = 40f, autoTare = false, stopOnWeight = false,
        volumeStopWithScale = true, requireScale = true, steamEco = true, steamTwoTap = true, fanThresholdC = 40f,
        preFlush = true, steamPurge = true, weightUnit = "oz", tempUnit = "F", pressureUnit = "psi", volumeUnit = "floz",
        waterLevelUnit = "percent", waterWarnMl = 200f, waterRefillPointMm = 10f, usbChargingMode = "smart",
        chartChannels = listOf("flow", "resistance"), keepScreenOnBrew = true, brewCueSound = true, brewCueHaptics = false,
        showDebugPanel = true, defaultDoseG = 20f, defaultRatio = 2.5f, defaultBrewTempC = 94f, defaultPreinfuseS = 5f,
        grinderModel = "Niche", baristaName = "Ana", suppressDe1Sleep = true, idleSleepMin = 60u,
        qcSteamTimeS = 30f, qcSteamFlowMlS = 1.6f, qcSteamTempC = 150f, qcHotWaterTempC = 85f, qcHotWaterVolumeMl = 200f,
        qcHotWaterMode = HotWaterMode.Time, qcHotWaterTimeS = 45f, qcHotWaterTimeNoLimit = true, qcHotWaterRatio = 2.5f,
        qcFlushTimeS = 6f, qcFlushTempC = 93f,
    )

    @Test
    fun `the bundle differs from the defaults in every field`() {
        val nd = json.encodeToJsonElement(CommonSettings.serializer(), nonDefault).jsonObject
        val d = json.encodeToJsonElement(CommonSettings.serializer(), CommonDefaults.common).jsonObject
        val same = CommonSettings.serializer().descriptor.elementNames
            .filter { it != "suppressDe1Sleep" } // retired, always written true
            .filter { nd[it] == d[it] }
        assertEquals(emptyList(), same, "set these to a non-default value above")
    }

    @Test
    fun `every shared field survives the MainUiState mapping`() {
        val values = json.encodeToJsonElement(CommonSettings.serializer(), nonDefault).jsonObject
        val ctor = MainUiState::class.primaryConstructor!!
        val args = ctor.parameters.filter { it.name in values }.associateWith { p ->
            val v: Any? = CommonSettings::class.members.first { it.name == p.name }.call(nonDefault)
            when {
                v is List<*> -> v.toSet()
                v is UInt -> v.toInt()
                p.name == "baristaName" -> v ?: ""
                else -> v
            }
        }
        val ui = ctor.callBy(args)
        assertEquals(nonDefault, ui.commonSettings())
    }
}
