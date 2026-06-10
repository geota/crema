package coffee.crema.beans

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/*
 * Small shell-side bean display helpers, shared by the Beans library and the
 * Brew bean block. v1 computes these in Kotlin; the canonical classifiers live
 * in the core (de1_domain `roast_band` / `days_off_roast` / `roast_freshness`)
 * and should move behind FFI later (a tracked follow-up), at which point the
 * freshness band/colour comes from the core too.
 */

/**
 * Currently-frozen test (web `isFrozen`): a bag is frozen while `frozenOn` is
 * set and no `defrostedOn` follows it. `frozenOn` alone is freeze HISTORY —
 * web keeps it after defrost — so checking only `frozenOn != null` misreads
 * defrosted bags (and any web-imported library) as still in the freezer.
 */
val coffee.crema.core.Bean.isFrozen: Boolean
    get() = frozenOn != null && defrostedOn == null

/** 1–3 light · 4–6 medium · 7–10 dark (mirrors the core `roast_band`); null when unset. */
fun roastBand(level: Int?): String? = when {
    level == null -> null
    level <= 3 -> "Light"
    level <= 6 -> "Medium"
    else -> "Dark"
}

/**
 * Finer 5-band DISPLAY label (web `roastBand5`): Light / Med-light / Medium /
 * Med-dark / Dark over the 1..10 scale. Purely for tile pills + the roast
 * slider caption — every comparison/filter stays on the canonical 3-band
 * [roastBand].
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
 * Whole days between the ISO `yyyy-mm-dd` roast date and today; null if
 * unparseable. UTC calendar days — the same basis as the core
 * `days_off_roast` and the web shell, so all three report the same integer
 * (device-local dates drifted ±1 around midnight).
 */
fun daysOffRoast(roastedOn: String?): Int? = roastedOn?.let { date ->
    runCatching {
        ChronoUnit.DAYS.between(LocalDate.parse(date), LocalDate.now(ZoneOffset.UTC)).toInt().coerceAtLeast(0)
    }.getOrNull()
}
