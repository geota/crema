package coffee.crema.brew

import kotlinx.serialization.decodeFromString

/*
 * The Brew Log method vocabulary (issue #10) — the Android twin of the web's
 * `$lib/brew/methods`. Storage accepts ANY normalized method string (the
 * core's `normalize_brew_method` rule). The preset ids + seed numbers live in
 * the core (`de1_domain::brew_method_presets`, one table for both shells);
 * this file owns only what the UI makes of an id: its label and icon. Tea is
 * deliberately absent — a BC tea brew still imports, carrying its name as
 * free text. "Other" is the free-text chip, not a preset.
 */

/** One curated method preset — a chip in the log form: the core preset plus its UI face. */
data class BrewMethodPreset(
    /** The stored method string (`"french_press"`). */
    val id: String,
    /** Display label ("French press"). */
    val label: String,
    /** The PhIcon name for its mark (web MethodMark parity). */
    val icon: String,
    /** The core's seed numbers for this preset. */
    val seeds: coffee.crema.core.BrewMethodPreset,
)

/** Decodes core JSON; tolerant of fields a newer core adds. */
internal val CoreJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** Label + icon per core preset id — the only per-method data the shell owns. */
private val METHOD_FACES: Map<String, Pair<String, String>> = mapOf(
    "espresso" to ("Espresso" to "coffee"),
    "pourover" to ("V60 / pourover" to "funnel"),
    "aeropress" to ("AeroPress" to "cylinder"),
    "french_press" to ("French press" to "jar"),
    "moka" to ("Moka" to "hourglass"),
    "cold_brew" to ("Cold brew" to "snowflake"),
    "drip" to ("Drip machine" to "drop"),
    "siphon" to ("Siphon" to "flask"),
    "clever" to ("Clever / Switch" to "funnel-simple"),
)

/**
 * The chip row, in the core's display order — parsed once from
 * `brewMethodPresetsJson()` (native; call from UI code only, never from a
 * JVM unit test).
 */
val BREW_METHOD_PRESETS: List<BrewMethodPreset> by lazy {
    CoreJson
        .decodeFromString<List<coffee.crema.core.BrewMethodPreset>>(coffee.crema.core.brewMethodPresetsJson())
        .map { p ->
            val (label, icon) = METHOD_FACES[p.id] ?: (p.id to "coffee-bean")
            BrewMethodPreset(p.id, label, icon, p)
        }
}

/** The label + icon face for a stored method, or null for free text. */
private fun faceFor(method: String?): Pair<String, String>? =
    method?.trim()?.lowercase()?.let { METHOD_FACES[it] }

/** The mark PhIcon name for any method (web MethodMark parity). */
fun methodIcon(method: String?): String =
    faceFor(method)?.second ?: if (method.isNullOrBlank()) "coffee" else "coffee-bean"

/**
 * Display label for any stored method string: the preset label when curated,
 * else the free text re-humanized ("karlsbad_kanne" → "Karlsbad kanne").
 * Null/blank (machine espresso) → "Espresso".
 */
fun methodLabel(method: String?): String {
    val m = method?.trim()?.lowercase().orEmpty()
    if (m.isEmpty()) return "Espresso"
    faceFor(m)?.let { return it.first }
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
