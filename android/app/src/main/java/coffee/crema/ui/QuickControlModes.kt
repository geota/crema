package coffee.crema.ui

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

/**
 * Quick-Controls steam-temp constants — previously the magic `148` / `135` / `170`
 * scattered across `MainViewModel` and both QC sheets (tablet + phone).
 */
object QcSteam {
    /** Default steam-temp target (°C) the Steam dot arms to. */
    const val DEFAULT_TEMP_C = 148.0

    /**
     * Steam-temp floor (°C). The DE1 firmware snaps anything below this to 0
     * (heater off); the floor keeps the user out of that silent 120–134 snap band.
     */
    const val MIN_TEMP_C = 135.0

    /** Steam-temp ceiling (°C). */
    const val MAX_TEMP_C = 170.0

    const val MIN_FLOW_ML_S = 0.2
    const val MAX_FLOW_ML_S = 3.0
    const val MIN_TIME_S = 1.0
    const val MAX_TIME_S = 60.0
}

/**
 * Min/max ranges for the remaining Quick-Controls steppers — previously magic
 * numbers inlined in the tablet (`QuickControlsSheet`) and phone (`PhoneBrewSheets`)
 * `if`/`when` expressions. (Per-layout preset/chip arrays stay in their sheets:
 * they intentionally differ tablet vs phone.)
 */
object QcBounds {
    const val DOSE_MIN_G = 5.0
    const val DOSE_MAX_G = 30.0
    const val GRIND_MIN = 0.0
    const val GRIND_MAX = 20.0
    const val YIELD_MIN_G = 10.0
    const val YIELD_MAX_G = 80.0
    const val RATIO_MIN = 1.0
    const val RATIO_MAX = 5.0
    const val BREW_TEMP_MIN_C = 80.0
    const val BREW_TEMP_MAX_C = 100.0
    const val PREINF_MIN_S = 0.0
    const val PREINF_MAX_S = 30.0
    const val WATER_TEMP_MIN_C = 40.0
    const val WATER_TEMP_MAX_C = 98.0
    const val WATER_VOL_MIN_ML = 20.0
    const val WATER_VOL_MAX_ML = 500.0
    const val FLUSH_TIME_MIN_S = 1.0
    const val FLUSH_TIME_MAX_S = 20.0
    const val FLUSH_TEMP_MIN_C = 60.0
    const val FLUSH_TEMP_MAX_C = 100.0
}
