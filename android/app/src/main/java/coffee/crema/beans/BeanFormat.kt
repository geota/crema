package coffee.crema.beans

import coffee.crema.core.daysOffRoast as coreDaysOffRoast
import coffee.crema.core.roastBand as coreRoastBand
import coffee.crema.core.roastBand5 as coreRoastBand5
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
 * Finer 5-band DISPLAY label: Light / Med-light / Medium / Med-dark / Dark over
 * the 1..10 scale. Purely for tile pills + the roast slider caption — every
 * comparison/filter stays on the canonical 3-band [roastBand]. Delegates to core
 * `roastBand5` so web and Android share the one mapping; null when unset.
 */
fun roastBand5(level: Int?): String? = coreRoastBand5(level)

/**
 * Whole UTC days between the ISO `yyyy-mm-dd` roast date and today; null if
 * unparseable. Delegates to core `daysOffRoast` so web, Android, and the core
 * all report the same integer (device-local dates drifted ±1 around midnight).
 * A plain calendar diff — for a bag's roast age use [beanDaysOffRoast], which
 * pauses the count while frozen.
 */
fun daysOffRoast(roastedOn: String?): Int? =
    coreDaysOffRoast(roastedOn, null, null, System.currentTimeMillis())?.toInt()

/**
 * A bag's days off roast with the core's freeze-pause: days spent frozen don't
 * age the bag (still frozen → the count stops at `frozenOn`; thawed → the
 * frozen span is subtracted). Use this wherever the number describes a *bag*
 * rather than a bare date — it is what makes the day count agree with the
 * "Paused (frozen)" label sitting next to it. Mirrors web `beanDaysOffRoast`.
 */
fun beanDaysOffRoast(bean: coffee.crema.core.Bean): Int? =
    coreDaysOffRoast(bean.roastedOn, bean.frozenOn, bean.defrostedOn, System.currentTimeMillis())?.toInt()

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
