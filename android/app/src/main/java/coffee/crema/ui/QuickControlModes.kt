package coffee.crema.ui

import coffee.crema.core.BrewDefaultsBounds
import coffee.crema.core.ProfileEditorBounds
import coffee.crema.core.QuickControlBounds
import coffee.crema.core.brewDefaultsBoundsJson
import coffee.crema.core.profileEditorBoundsJson
import coffee.crema.core.quickControlBoundsJson

/*
 * Quick-Controls / brew-sheet per-stepper "mode" — which sub-value each combined
 * stepper currently edits. This is shell-local *view* state (not a core/wire type),
 * shared by the tablet `QuickControlsSheet` and the phone brew sheets.
 *
 * Each variant's `value` is the lowercase id used by the String-keyed segmented
 * widgets (`SplitOption`/`SegOption`); `of` maps that id back to the enum at the
 * widget boundary. Everything else compares the enum directly (exhaustive `when`,
 * typo-proof `==`) instead of bare strings.
 */

enum class DoseGrindMode(val value: String) {
    Dose("dose"),
    Grind("grind"),
    ;
    companion object {
        fun of(value: String): DoseGrindMode = entries.firstOrNull { it.value == value } ?: Dose
    }
}

enum class BrewMode(val value: String) {
    Temp("temp"),
    Preinf("preinf"),
    ;
    companion object {
        fun of(value: String): BrewMode = entries.firstOrNull { it.value == value } ?: Temp
    }
}

enum class SteamMode(val value: String) {
    Time("time"),
    Flow("flow"),
    Temp("temp"),
    ;
    companion object {
        fun of(value: String): SteamMode = entries.firstOrNull { it.value == value } ?: Time
    }
}

enum class WaterMode(val value: String) {
    Temp("temp"),
    Volume("volume"),
    ;
    companion object {
        fun of(value: String): WaterMode = entries.firstOrNull { it.value == value } ?: Volume
    }
}

enum class FlushMode(val value: String) {
    Time("time"),
    Temp("temp"),
    ;
    companion object {
        fun of(value: String): FlushMode = entries.firstOrNull { it.value == value } ?: Time
    }
}

enum class YieldRatioMode(val value: String) {
    Yield("yield"),
    Ratio("ratio"),
    ;
    companion object {
        fun of(value: String): YieldRatioMode = entries.firstOrNull { it.value == value } ?: Yield
    }
}

enum class PiFlushMode(val value: String) {
    Preinf("preinf"),
    Flush("flush"),
    ;
    companion object {
        fun of(value: String): PiFlushMode = entries.firstOrNull { it.value == value } ?: Preinf
    }
}

/** Decodes the core's bounds JSON (the typeshare'd tables). */
private val boundsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * The stepper and editor ranges from the core's one table (`de1_domain::bounds`),
 * decoded once on first use — the same numbers web and the core's own
 * enforcement use. Hot water is the core's `hot_water_bounds`; a profile's
 * brew-temp range is `brewTempRange`. Fails with a plain reason if the core's
 * native library can't load.
 */
internal object CoreBounds {
    private fun load(what: String, json: () -> String): String = try {
        json()
    } catch (e: Throwable) {
        throw IllegalStateException("The Crema core (libde1_ffi) didn't load, so the $what are unavailable", e)
    }

    /** The Quick Controls steppers' ranges. */
    val qc: QuickControlBounds by lazy {
        boundsJson.decodeFromString(QuickControlBounds.serializer(), load("Quick Controls ranges", ::quickControlBoundsJson))
    }

    /** Settings → Brew defaults' ranges. */
    val brewDefaults: BrewDefaultsBounds by lazy {
        boundsJson.decodeFromString(BrewDefaultsBounds.serializer(), load("brew-default ranges", ::brewDefaultsBoundsJson))
    }

    /** The profile editor's ranges: they apply when a field is edited; a stored
     *  profile outside them shows its own values. */
    val editor: ProfileEditorBounds by lazy {
        boundsJson.decodeFromString(ProfileEditorBounds.serializer(), load("profile editor ranges", ::profileEditorBoundsJson))
    }
}

/**
 * Quick-Controls steam values, read from the core's table ([CoreBounds]). The
 * steam temperature may also be 0 (heater off).
 */
object QcSteam {
    /** Default steam-temp target (°C) the Steam dot arms to: the default
     *  settings' steam temperature. */
    val DEFAULT_TEMP_C: Double get() = coffee.crema.settings.CommonDefaults.common.qcSteamTempC.toDouble()

    /** Steam-temp floor (°C). The DE1 firmware snaps anything below this to 0
     *  (heater off); the floor keeps the user out of that silent snap band. */
    val MIN_TEMP_C: Double get() = CoreBounds.qc.steamTempC.min.toDouble()

    /** Steam-temp ceiling (°C). */
    val MAX_TEMP_C: Double get() = CoreBounds.qc.steamTempC.max.toDouble()

    val MIN_FLOW_ML_S: Double get() = CoreBounds.qc.steamFlowMlS.min.toDouble()
    val MAX_FLOW_ML_S: Double get() = CoreBounds.qc.steamFlowMlS.max.toDouble()
    val MIN_TIME_S: Double get() = CoreBounds.qc.steamTimeS.min.toDouble()
    val MAX_TIME_S: Double get() = CoreBounds.qc.steamTimeS.max.toDouble()
}

/**
 * Min/max ranges for the remaining Quick-Controls steppers, read from the core's
 * table ([CoreBounds]). (Per-layout preset/chip arrays stay in their sheets:
 * they intentionally differ tablet vs phone.) The hot-water values are the
 * hot-water modes' and stay here until that work moves them.
 */
object QcBounds {
    val DOSE_MIN_G: Double get() = CoreBounds.qc.doseG.min.toDouble()
    val DOSE_MAX_G: Double get() = CoreBounds.qc.doseG.max.toDouble()
    val GRIND_MIN: Double get() = CoreBounds.qc.grind.min.toDouble()
    val GRIND_MAX: Double get() = CoreBounds.qc.grind.max.toDouble()
    val YIELD_MIN_G: Double get() = CoreBounds.qc.yieldG.min.toDouble()
    val YIELD_MAX_G: Double get() = CoreBounds.qc.yieldG.max.toDouble()
    val RATIO_MIN: Double get() = CoreBounds.qc.ratio.min.toDouble()
    val RATIO_MAX: Double get() = CoreBounds.qc.ratio.max.toDouble()
    val BREW_TEMP_MIN_C: Double get() = CoreBounds.qc.brewTempC.min.toDouble()
    val BREW_TEMP_MAX_C: Double get() = CoreBounds.qc.brewTempC.max.toDouble()
    val PREINF_MIN_S: Double get() = CoreBounds.qc.preinfuseS.min.toDouble()
    val PREINF_MAX_S: Double get() = CoreBounds.qc.preinfuseS.max.toDouble()
    const val WATER_TEMP_MIN_C = 40.0
    const val WATER_TEMP_MAX_C = 98.0
    const val WATER_VOL_MIN_ML = 20.0
    const val WATER_VOL_MAX_ML = 500.0
    val FLUSH_TIME_MIN_S: Double get() = CoreBounds.qc.flushTimeS.min.toDouble()
    val FLUSH_TIME_MAX_S: Double get() = CoreBounds.qc.flushTimeS.max.toDouble()
    val FLUSH_TEMP_MIN_C: Double get() = CoreBounds.qc.flushTempC.min.toDouble()
    val FLUSH_TEMP_MAX_C: Double get() = CoreBounds.qc.flushTempC.max.toDouble()
}
