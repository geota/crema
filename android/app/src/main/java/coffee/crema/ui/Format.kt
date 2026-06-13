package coffee.crema.ui

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
