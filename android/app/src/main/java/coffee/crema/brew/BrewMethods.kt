package coffee.crema.brew

import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewStep
import coffee.crema.core.BrewStepKind
import coffee.crema.core.StepAdvance
import coffee.crema.core.newRecipeId
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.decodeFromString

/*
 * The Brew Log method vocabulary (issue #10) — the Android twin of the web's
 * `$lib/brew/methods`. Storage accepts ANY normalized method string (the
 * core's `normalize_brew_method` rule). The preset ids + seed numbers live in
 * the core (`de1_domain::brew_method_presets`, one table for both shells);
 * this file owns what the UI makes of an id: its label and icon, plus the
 * default guided-brew recipes. Tea is deliberately absent — a BC tea brew
 * still imports, carrying its name as free text. "Other" is the free-text
 * chip, not a preset.
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

/**
 * Build the sensible starter recipe for a method — what the Scale screen's
 * Brew segment offers before the user has saved anything. Twin of the web's
 * `defaultRecipeFor`.
 */
fun defaultRecipeFor(method: String, nowMs: Long): BrewRecipe {
    val preset = BREW_METHOD_PRESETS.firstOrNull { it.id == method }
    val dose = preset?.seeds?.seedDoseG ?: 15f
    val water = preset?.seeds?.seedWaterG ?: preset?.seeds?.seedYieldG ?: 250f
    return BrewRecipe(
        id = newRecipeId(),
        name = "${(preset?.label ?: method).substringBefore(" / ")} classic",
        method = method,
        doseG = dose,
        waterG = water,
        tempC = preset?.seeds?.seedTempC,
        steps = defaultStepsFor(method, dose, water),
        notes = null,
        favourite = false,
        createdAt = nowMs,
        updatedAt = nowMs,
        deletedAt = null,
    )
}

private fun defaultStepsFor(method: String, dose: Float, water: Float): List<BrewStep> {
    val bloom = min((dose * 3).roundToInt(), (water * 0.25f).roundToInt()).toFloat()
    fun pour(target: Float) =
        BrewStep(kind = BrewStepKind.Pour, targetWaterG = target, advance = StepAdvance.Auto)
    fun timed(kind: BrewStepKind, s: Long, advance: StepAdvance = StepAdvance.Auto) =
        BrewStep(kind = kind, durationS = s, advance = advance)
    fun open(kind: BrewStepKind) = BrewStep(kind = kind, advance = StepAdvance.Manual)
    return when (method) {
        "pourover" -> listOf(
            BrewStep(
                kind = BrewStepKind.Bloom,
                targetWaterG = bloom,
                durationS = 45,
                advance = StepAdvance.Auto,
            ),
            pour((water * 0.6f).roundToInt().toFloat()),
            timed(BrewStepKind.Wait, 30),
            pour(water),
            open(BrewStepKind.Drawdown),
        )
        "aeropress" -> listOf(
            pour(water),
            timed(BrewStepKind.Stir, 10),
            timed(BrewStepKind.Steep, 60),
            timed(BrewStepKind.Press, 25),
        )
        "french_press" -> listOf(pour(water), timed(BrewStepKind.Steep, 240), open(BrewStepKind.Press))
        "clever" -> listOf(pour(water), timed(BrewStepKind.Steep, 150), open(BrewStepKind.Drawdown))
        "siphon" -> listOf(pour(water), timed(BrewStepKind.Steep, 90), open(BrewStepKind.Drawdown))
        // Espresso / moka / drip / cold brew / free-text: one open pour to
        // the water (or yield) target — "just time it for me".
        else -> listOf(pour(water))
    }
}

/** Display label for a step's kind. */
fun stepKindLabel(kind: BrewStepKind): String = when (kind) {
    BrewStepKind.Bloom -> "Bloom"
    BrewStepKind.Pour -> "Pour"
    BrewStepKind.Wait -> "Wait"
    BrewStepKind.Steep -> "Steep"
    BrewStepKind.Stir -> "Stir"
    BrewStepKind.Press -> "Press"
    BrewStepKind.Drawdown -> "Drawdown"
    BrewStepKind.Other -> "Step"
}

/** "to 250 g · 0:45" / "until you tap" — one spec line per step. */
fun stepSpec(step: BrewStep): String {
    val parts = mutableListOf<String>()
    step.targetWaterG?.let { parts.add("to ${it.roundToInt()} g") }
    step.durationS?.let { parts.add(formatClock(it * 1000)) }
    if (parts.isEmpty()) parts.add("until you tap")
    return parts.joinToString(" · ")
}

/** "3:05" — mm:ss for any duration in ms. */
fun formatClock(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
