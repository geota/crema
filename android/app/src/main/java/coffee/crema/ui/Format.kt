package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import coffee.crema.beans.freshnessVerdict
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.core.barToPsi
import coffee.crema.core.brewRatio
import coffee.crema.core.celsiusToFahrenheit
import coffee.crema.core.gramsToOz
import coffee.crema.core.mlToFlOz

/**
 * Canonical brew-ratio label `1:N` with **one decimal** (e.g. `1:2.4`), or
 * `1:—` when the pair is undefined (non-positive dose, missing yield, or a
 * non-finite quotient).
 *
 * The number comes from core `brewRatio` (`de1_domain::brew_ratio`) — the same
 * implementation the web shell reaches through wasm (`$lib/utils/ratio`). One
 * formatter, one precision, one source of truth: no `%.1f`-vs-`%.2f` drift
 * between screens or shells.
 */
fun formatRatio(dose: Float?, yieldOut: Float?): String {
    if (dose == null || yieldOut == null) return "1:—"
    val r = brewRatio(dose, yieldOut) ?: return "1:—"
    return "1:%.1f".format(r)
}

/** [Double] overload for call sites holding `Double` dose/yield values. */
fun formatRatio(dose: Double?, yieldOut: Double?): String =
    formatRatio(dose?.toFloat(), yieldOut?.toFloat())

// ── Unit-aware display formatting (issue 44) ─────────────────────────────────
//
// Crema stores everything canonical — grams / °C / bar / ml. These convert a
// canonical reading to the user's chosen unit and format it, mirroring the web
// shell's `$lib/settings/format`. The arithmetic rides the core's UniFFI
// conversions (`de1_domain::units`), so the two shells share one set of
// constants — no Kotlin-side drift. Unit strings match the web vocabulary
// (`"g"`/`"oz"`, `"C"`/`"F"`, `"bar"`/`"psi"`, `"ml"`/`"floz"`).

/** Placeholder for a missing reading — matches the dashboards' em dash. */
private const val DASH = "—"

/** A formatted measurement split into its numeric string and unit label. */
data class Measurement(val value: String, val unit: String)

private fun Float.isPresentReading(): Boolean = isFinite()

/** Convert a weight (canonical grams) to the chosen unit (`"g"`/`"oz"`). The
 *  unit label stays honest even when the reading is missing. */
fun convertWeight(grams: Float?, unit: String): Measurement {
    val oz = unit == "oz"
    val label = if (oz) "oz" else "g"
    if (grams == null || !grams.isPresentReading()) return Measurement(DASH, label)
    return if (oz) Measurement("%.2f".format(gramsToOz(grams)), "oz")
    else Measurement("%.1f".format(grams), "g")
}

/** Convert a temperature (canonical °C) to the chosen unit (`"C"`/`"F"`). */
fun convertTemp(celsius: Float?, unit: String): Measurement {
    val f = unit == "F"
    val label = if (f) "°F" else "°C"
    if (celsius == null || !celsius.isPresentReading()) return Measurement(DASH, label)
    return if (f) Measurement("%.1f".format(celsiusToFahrenheit(celsius)), "°F")
    else Measurement("%.1f".format(celsius), "°C")
}

/** Convert a pressure (canonical bar) to the chosen unit (`"bar"`/`"psi"`). */
fun convertPressure(bar: Float?, unit: String): Measurement {
    val psi = unit == "psi"
    val label = if (psi) "psi" else "bar"
    if (bar == null || !bar.isPresentReading()) return Measurement(DASH, label)
    return if (psi) Measurement("%.0f".format(barToPsi(bar)), "psi")
    else Measurement("%.1f".format(bar), "bar")
}

/** Convert a volume (canonical ml) to the chosen unit (`"ml"`/`"floz"`). */
fun convertVolume(ml: Float?, unit: String): Measurement {
    val floz = unit == "floz"
    val label = if (floz) "fl oz" else "ml"
    if (ml == null || !ml.isPresentReading()) return Measurement(DASH, label)
    return if (floz) Measurement("%.1f".format(mlToFlOz(ml)), "fl oz")
    else Measurement("%.0f".format(ml), "ml")
}

private fun Measurement.joined(): String = if (unit.isNotEmpty()) "$value $unit" else value

/** Format a weight (canonical grams) as one string in the chosen unit, e.g. "18.0 g". */
fun formatWeight(grams: Float?, unit: String): String = convertWeight(grams, unit).joined()

/** Format a temperature (canonical °C) as one string in the chosen unit, e.g. "93.0 °C". */
fun formatTemp(celsius: Float?, unit: String): String = convertTemp(celsius, unit).joined()

/** Format a pressure (canonical bar) as one string in the chosen unit, e.g. "9.0 bar". */
fun formatPressure(bar: Float?, unit: String): String = convertPressure(bar, unit).joined()

/** Format a volume (canonical ml) as one string in the chosen unit, e.g. "250 ml". */
fun formatVolume(ml: Float?, unit: String): String = convertVolume(ml, unit).joined()

/**
 * One compact-relative "time ago" label, shared by both Android shells (issue 43):
 * `just now` / `5m ago` / `2h ago` / `6d ago` / `4w ago` / `3mo ago` / `2y ago`.
 *
 * Thresholds match the web `relativeLastUsed` (`$lib/profiles/model`) exactly, so a
 * given shot reads identically on web ShotRow, web Profiles, tablet History, and
 * phone History. [now] defaults to the wall clock; pass it for deterministic tests.
 */
fun relativeAgo(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val min = ((now - epochMs) / 60_000L).coerceAtLeast(0L)
    if (min < 1L) return "just now"
    if (min < 60L) return "${min}m ago"
    val hours = min / 60L
    if (hours < 24L) return "${hours}h ago"
    val days = hours / 24L
    if (days < 7L) return "${days}d ago"
    val weeks = days / 7L
    if (weeks < 5L) return "${weeks}w ago"
    val months = days / 30L
    if (months < 12L) return "${months}mo ago"
    return "${days / 365L}y ago"
}

/**
 * Band-aware freshness status colour for the bean dot — the single palette
 * shared by the Beans library, the Brew bean block, and the phone bean card
 * (was three byte-divergent copies). The verdict, and its roast-band rest
 * window, comes from core via [freshnessVerdict]; this only maps the verdict
 * to a hue. Frozen → icy blue; best → green; ok → amber; bad → muted red;
 * unknown (no roast date) → neutral.
 */
@Composable
fun freshnessColor(frozen: Boolean, level: Int?, days: Int?): Color {
    val c = CremaTheme.freshness
    return when (freshnessVerdict(frozen, level, days)) {
        "frozen" -> c.frozen
        "best" -> c.best
        "ok" -> c.ok
        "bad" -> c.bad
        else -> c.unknown
    }
}
