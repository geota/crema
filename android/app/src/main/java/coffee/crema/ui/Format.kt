package coffee.crema.ui

import androidx.compose.ui.graphics.Color
import coffee.crema.beans.freshnessVerdict
import coffee.crema.core.brewRatio

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

/**
 * Band-aware freshness status colour for the bean dot — the single palette
 * shared by the Beans library, the Brew bean block, and the phone bean card
 * (was three byte-divergent copies). The verdict, and its roast-band rest
 * window, comes from core via [freshnessVerdict]; this only maps the verdict
 * to a hue. Frozen → icy blue; best → green; ok → amber; bad → muted red;
 * unknown (no roast date) → neutral.
 */
fun freshnessColor(frozen: Boolean, level: Int?, days: Int?): Color =
    when (freshnessVerdict(frozen, level, days)) {
        "frozen" -> Color(0xFF7FB0E0)
        "best" -> Color(0xFF5FB87A)
        "ok" -> Color(0xFFDBA764)
        "bad" -> Color(0xFFC58B8B)
        else -> Color(0xFF8A8175)
    }
