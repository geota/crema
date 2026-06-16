package coffee.crema.history

import java.util.Calendar

/*
 * Shot-history filter + sort — the search predicate, date-range filter, profile
 * filter, and sort that the tablet (HistoryScreen) and phone (PhoneHistoryScreen)
 * share. Pure over the stored log so both shells list shots identically (issue 28).
 * The sort *fields* are `historySortKeys` (ui.screens); this owns the predicate.
 */

/**
 * The shot log after search + date-range + profile filter + sort.
 *
 * [range] is today / 7d / 30d / all; [sort] is date (default) / rating / profile /
 * bean / yield / time. [now] is the current epoch-ms (passed in for testability);
 * "today" counts shots since local midnight.
 */
fun filterAndSortShots(
    history: List<StoredShot>,
    query: String,
    range: String,
    profileFilter: String?,
    sort: String,
    sortDesc: Boolean,
    now: Long,
): List<StoredShot> {
    val dayMs = 24L * 60L * 60L * 1000L
    val startOfDay = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val filtered = history.filter { s ->
        val matchesSearch = query.isBlank() ||
            (s.profileName?.contains(query, ignoreCase = true) == true) ||
            (s.beanLabel?.contains(query, ignoreCase = true) == true) ||
            (s.notes?.contains(query, ignoreCase = true) == true)
        val matchesRange = when (range) {
            "today" -> s.completedAtMs >= startOfDay
            "7d" -> s.completedAtMs >= now - 7L * dayMs
            "30d" -> s.completedAtMs >= now - 30L * dayMs
            else -> true
        }
        val matchesProfile = profileFilter == null || s.profileName == profileFilter
        matchesSearch && matchesRange && matchesProfile
    }
    val asc = when (sort) {
        "rating" -> filtered.sortedBy { it.rating ?: 0 }
        "profile" -> filtered.sortedBy { it.profileName?.lowercase() ?: "" }
        "bean" -> filtered.sortedBy { it.beanLabel?.lowercase() ?: "" }
        "yield" -> filtered.sortedBy { it.yieldG ?: 0f }
        "time" -> filtered.sortedBy { it.durationMs }
        else -> filtered.sortedBy { it.completedAtMs }
    }
    return if (sortDesc) asc.reversed() else asc
}
