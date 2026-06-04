package coffee.crema.beans

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * Small shell-side bean display helpers, shared by the Beans library and the
 * Brew bean block. v1 computes these in Kotlin; the canonical classifiers live
 * in the core (de1_domain `roast_band` / `days_off_roast` / `roast_freshness`)
 * and should move behind FFI later (a tracked follow-up), at which point the
 * freshness band/colour comes from the core too.
 */

/** 1–3 light · 4–6 medium · 7–10 dark (mirrors the core `roast_band`); null when unset. */
fun roastBand(level: Int?): String? = when {
    level == null -> null
    level <= 3 -> "Light"
    level <= 6 -> "Medium"
    else -> "Dark"
}

/** Whole days between the ISO `yyyy-mm-dd` roast date and today; null if unparseable. */
fun daysOffRoast(roastedOn: String?): Int? = roastedOn?.let { date ->
    runCatching {
        ChronoUnit.DAYS.between(LocalDate.parse(date), LocalDate.now()).toInt().coerceAtLeast(0)
    }.getOrNull()
}
