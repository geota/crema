package coffee.crema.brew

/*
 * The Brew Log method vocabulary (issue #10) — the Android twin of the web's
 * `$lib/brew/methods`. Storage accepts ANY normalized method string (the
 * core's `normalize_brew_method` rule); this file owns what the UI makes of
 * one: the curated preset chips, display labels, per-method seed values for
 * the log form. Tea is deliberately
 * absent — a BC tea brew still imports, carrying its name as free text.
 */

/** One curated method preset — a chip in the log form. */
data class BrewMethodPreset(
    /** The stored method string (`"french_press"`). */
    val id: String,
    /** Display label ("French press"). */
    val label: String,
    /** The PhIcon name for its mark (web MethodMark parity). */
    val icon: String,
    /** Seed dry dose, g, for a first-ever log of this method. */
    val seedDose: Float,
    /** Seed water-in, g — null for espresso (which speaks yield). */
    val seedWater: Float?,
    /** Seed yield-out, g — espresso only. */
    val seedYield: Float?,
    /** Seed water temperature, °C, or null (cold brew). */
    val seedTemp: Float?,
)

/** The chip row, in display order (spec §3 defaults). */
val BREW_METHOD_PRESETS: List<BrewMethodPreset> = listOf(
    BrewMethodPreset("espresso", "Espresso", "coffee", 18f, null, 36f, 93f),
    BrewMethodPreset("pourover", "V60 / pourover", "funnel", 15f, 250f, null, 96f),
    BrewMethodPreset("aeropress", "AeroPress", "cylinder", 14f, 220f, null, 90f),
    BrewMethodPreset("french_press", "French press", "jar", 30f, 500f, null, 95f),
    BrewMethodPreset("moka", "Moka", "hourglass", 15f, 150f, null, null),
    BrewMethodPreset("cold_brew", "Cold brew", "snowflake", 60f, 700f, null, null),
    BrewMethodPreset("drip", "Drip machine", "drop", 30f, 500f, null, 94f),
    BrewMethodPreset("siphon", "Siphon", "flask", 20f, 300f, null, 92f),
    BrewMethodPreset("clever", "Clever / Switch", "funnel-simple", 18f, 300f, null, 94f),
)

/** Look up a preset by stored id, or null for free-text methods. */
fun presetFor(method: String?): BrewMethodPreset? =
    method?.trim()?.lowercase()?.let { m -> BREW_METHOD_PRESETS.find { it.id == m } }

/** The mark PhIcon name for any method (web MethodMark parity). */
fun methodIcon(method: String?): String =
    presetFor(method)?.icon ?: if (method.isNullOrBlank()) "coffee" else "coffee-bean"

/**
 * Display label for any stored method string: the preset label when curated,
 * else the free text re-humanized ("karlsbad_kanne" → "Karlsbad kanne").
 * Null/blank (machine espresso) → "Espresso".
 */
fun methodLabel(method: String?): String {
    val m = method?.trim()?.lowercase().orEmpty()
    if (m.isEmpty()) return "Espresso"
    presetFor(m)?.let { return it.label }
    val words = m.replace('_', ' ').trim()
    return words.replaceFirstChar { it.uppercase() }
}

/**
 * Compact label for tight surfaces (metric tiles): the preset label's
 * first alternative — "V60 / pourover" → "V60" — else the full label.
 */
fun methodShortLabel(method: String?): String =
    methodLabel(method).substringBefore(" / ").trim()

/** The espresso-family rule — mirrors `de1_domain::is_espresso_method`. */
fun isEspressoMethod(method: String?): Boolean {
    val m = method?.trim()?.lowercase()
    return m.isNullOrEmpty() || m == "espresso"
}

/** "3:05" — mm:ss for any duration in ms. */
fun formatClock(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
