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
