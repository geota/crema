package coffee.crema.brew

import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewStep
import coffee.crema.core.BrewStepKind
import coffee.crema.core.StepAdvance
import coffee.crema.core.blankRecipeJson
import coffee.crema.core.builtinBrewRecipesJson
import coffee.crema.core.newRecipeId
import kotlin.math.roundToInt
import kotlinx.serialization.decodeFromString

/*
 * The Brew Log method vocabulary (issue #10) — the Android twin of the web's
 * `$lib/brew/methods`. Storage accepts ANY normalized method string (the
 * core's `normalize_brew_method` rule). The preset ids + seed numbers live in
 * the core (`de1_domain::brew_method_presets`, one table for both shells);
 * this file owns what the UI makes of an id: its label and icon, plus the
 * built-in guided-brew recipe catalogue access. Tea is deliberately absent — a BC tea brew
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
    "chemex" to ("Chemex" to "hourglass-simple"),
    "kalita_wave" to ("Kalita Wave" to "waves"),
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
 * The built-in, credited recipe catalogue — the core's
 * `builtin_brew_recipes` (bundled, read-only, `builtin:` ids), parsed once.
 * Native; call from UI code only, never from a JVM unit test.
 */
val BUILTIN_BREW_RECIPES: List<BrewRecipe> by lazy {
    CoreJson.decodeFromString<List<BrewRecipe>>(builtinBrewRecipesJson())
}

/**
 * The "+ New recipe" starting point for a method — the core's
 * `blank_recipe` (preset numbers, one pour, no credit), named with the UI's
 * copy ("Chemex recipe"). The runnable per-method defaults are the credited
 * built-ins, not this. [core] is the FFI call, injectable for JVM tests.
 */
fun newRecipeFor(
    method: String,
    nowMs: Long,
    newId: () -> String = ::newRecipeId,
    core: (String, String, Long) -> String = ::blankRecipeJson,
): BrewRecipe {
    val recipe = CoreJson.decodeFromString(BrewRecipe.serializer(), core(method, newId(), nowMs))
    return recipe.copy(name = "${methodShortLabel(method)} recipe")
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

/**
 * A step's display name: its own [BrewStep.label] when set ("Second pour"),
 * else its kind's label — the web guided panel's rule.
 */
fun stepLabel(step: BrewStep): String = step.label?.trim()?.takeIf { it.isNotEmpty() } ?: stepKindLabel(step.kind)

/**
 * Whether a step's duration is an expectation, not a countdown — the core's
 * `BrewStep::has_expected_duration` (a tap-to-finish drawdown with a
 * duration). The session never advances or cues it on time; the live card
 * shows "about 0:40 left", then "+0:12 over". The web's `hasExpectedDuration`.
 */
fun hasExpectedDuration(step: BrewStep?): Boolean =
    step != null && step.kind == BrewStepKind.Drawdown && step.advance == StepAdvance.Manual && step.durationS != null

/** "to 250 g · 0:45" / "about 0:55 · until you tap" (a drawdown's expected
 *  time) / "until you tap" — one spec line per step, the web's rule. */
fun stepSpec(step: BrewStep): String {
    val parts = mutableListOf<String>()
    step.targetWaterG?.let { parts.add("to ${it.roundToInt()} g") }
    val d = step.durationS
    if (d != null && hasExpectedDuration(step)) {
        parts.add("about ${formatClock(d * 1000)}")
        parts.add("until you tap")
    } else if (d != null) {
        parts.add(formatClock(d * 1000))
    }
    if (parts.isEmpty()) parts.add("until you tap")
    return parts.joinToString(" · ")
}

/** "3:05" — m:ss for any duration in ms; "12:00:00" (h:mm:ss) from an hour up
 *  (a cold brew's 12 h steep). */
fun formatClock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    val h = total / 3600
    return if (h > 0) {
        "%d:%02d:%02d".format(h, (total % 3600) / 60, total % 60)
    } else {
        "%d:%02d".format(total / 60, total % 60)
    }
}
