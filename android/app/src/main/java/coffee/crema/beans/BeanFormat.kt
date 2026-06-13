package coffee.crema.beans

import coffee.crema.core.daysOffRoast as coreDaysOffRoast
import coffee.crema.core.roastBand as coreRoastBand
import coffee.crema.core.roastFreshness as coreRoastFreshness

/*
 * Small shell-side bean display helpers, shared by the Beans library and the
 * Brew bean block. The canonical classifiers live in the core (de1_domain
 * `roast_band` / `days_off_roast` / `roast_freshness`, exposed via UniFFI);
 * these wrappers add only the shell-side presentation — a capitalised band
 * label, an Int day count, and the band-aware freshness verdict the colour dot
 * keys off. `freshnessColor` (the verdict → hue map) lives in `coffee.crema.ui`.
 */

/**
 * Currently-frozen test (web `isFrozen`): a bag is frozen while `frozenOn` is
 * set and no `defrostedOn` follows it. `frozenOn` alone is freeze HISTORY —
 * web keeps it after defrost — so checking only `frozenOn != null` misreads
 * defrosted bags (and any web-imported library) as still in the freezer.
 */
val coffee.crema.core.Bean.isFrozen: Boolean
    get() = frozenOn != null && defrostedOn == null

/** 1–3 light · 4–6 medium · 7–10 dark — capitalised for display. The
 *  classification comes from core `roastBand`; null when the level is unset. */
fun roastBand(level: Int?): String? =
    coreRoastBand(level)?.replaceFirstChar { it.uppercase() }

/**
 * Finer 5-band DISPLAY label (web `roastBand5`): Light / Med-light / Medium /
 * Med-dark / Dark over the 1..10 scale. Purely for tile pills + the roast
 * slider caption — every comparison/filter stays on the canonical 3-band
 * [roastBand]. (No core equivalent yet — tracked as issue 09.)
 */
fun roastBand5(level: Int?): String? = when {
    level == null -> null
    level <= 2 -> "Light"
    level <= 4 -> "Med-light"
    level == 5 -> "Medium"
    level <= 7 -> "Med-dark"
    else -> "Dark"
}

/**
 * Whole UTC days between the ISO `yyyy-mm-dd` roast date and today; null if
 * unparseable. Delegates to core `daysOffRoast` so web, Android, and the core
 * all report the same integer (device-local dates drifted ±1 around midnight).
 */
fun daysOffRoast(roastedOn: String?): Int? =
    coreDaysOffRoast(roastedOn, System.currentTimeMillis())?.toInt()

/**
 * Band-aware freshness verdict for the bean status dot — `"frozen"` while the
 * bag is frozen, else the core `roast_freshness` verdict (`"best"` / `"ok"` /
 * `"bad"`) for the bean's roast band and days off roast, or `null` when there
 * is no roast date. The rest window is **band-aware** (dark degasses fastest,
 * light slowest) — the same verdict the web shell shows, fixing the old
 * band-agnostic thresholds that read a stale dark roast as "peak". An unknown
 * roast level falls back to the medium window. [coffee.crema.ui.freshnessColor]
 * maps the verdict to a hue.
 */
fun freshnessVerdict(frozen: Boolean, level: Int?, days: Int?): String? = when {
    frozen -> "frozen"
    days == null -> null
    else -> coreRoastFreshness(coreRoastBand(level) ?: "medium", days.toLong())
}
