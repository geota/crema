package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.pinActiveThenFavourite

/*
 * Bean-library filter + sort — the search predicate, facet filter, sort, and
 * filter-chip counts that the tablet (BeansScreen) and phone (PhoneBeansScreen)
 * share. Pure functions over the in-memory library so both shells produce
 * identical results (issue 28).
 */

/**
 * The bean library after search + facet filter + sort, then grouped with the
 * loaded ([activeId]) bean pinned to the top and favourites above the rest (via
 * [pinActiveThenFavourite]) — [sort] becomes the WITHIN-group order.
 *
 * [filter] is one of all / active / favourite / frozen / archived / light /
 * medium / dark; [sort] is freshest (default) / name / roast / rating /
 * remaining. Archived bags are hidden from every facet EXCEPT "archived" — the
 * dedicated chip is the only place they surface (web semantics).
 */
fun filterAndSortBeans(
    beans: List<Bean>,
    roasters: List<Roaster>,
    query: String,
    filter: String,
    sort: String,
    sortDesc: Boolean,
    activeId: String?,
): List<Bean> {
    val roasterNameOf: (Bean) -> String? = { b -> roasters.firstOrNull { it.id == b.roasterId }?.name }
    val visible = beans.filter { b ->
        val matchesSearch = query.isBlank() ||
            b.name.contains(query, ignoreCase = true) ||
            (roasterNameOf(b)?.contains(query, ignoreCase = true) == true) ||
            (b.origin?.country?.contains(query, ignoreCase = true) == true) ||
            (b.origin?.region?.contains(query, ignoreCase = true) == true) ||
            (b.origin?.farm?.contains(query, ignoreCase = true) == true) ||
            (b.origin?.farmer?.contains(query, ignoreCase = true) == true) ||
            (b.origin?.variety?.contains(query, ignoreCase = true) == true) ||
            (b.tastingNotes?.contains(query, ignoreCase = true) == true)
        val matchesFilter = when (filter) {
            "archived" -> b.archivedAt != null
            "active" -> b.archivedAt == null && !b.isFrozen
            "favourite" -> b.archivedAt == null && b.favourite == true
            "frozen" -> b.archivedAt == null && b.isFrozen
            "light", "medium", "dark" ->
                b.archivedAt == null && roastBand(b.roastLevel?.toInt())?.equals(filter, ignoreCase = true) == true
            else -> b.archivedAt == null // "All" excludes archived
        }
        matchesSearch && matchesFilter
    }
    val asc = when (sort) {
        "name" -> visible.sortedBy { it.name.lowercase() }
        "roast" -> visible.sortedBy { it.roastLevel?.toInt() ?: Int.MAX_VALUE }
        "rating" -> visible.sortedBy { it.rating?.toInt() ?: 0 }
        "remaining" -> visible.sortedBy { it.remaining }
        else -> visible.sortedBy { daysOffRoast(it.roastedOn) ?: Int.MAX_VALUE } // freshest first
    }
    val sorted = if (sortDesc) asc.reversed() else asc
    // Loaded bean to the top, then favourites, then the rest; [sort] above is the
    // within-group order. Shared with profiles + the Brew pickers.
    return sorted.pinActiveThenFavourite({ it.id == activeId }, { it.favourite == true })
}

/**
 * Brew-PICKER order: the same active→favourite→rest grouping as the library
 * [filterAndSortBeans] (both via [pinActiveThenFavourite]), but over a list the
 * caller already filtered (non-archived) — within-group order is store order.
 */
fun rankBeansForPicker(beans: List<Bean>, activeId: String?): List<Bean> =
    beans.pinActiveThenFavourite({ it.id == activeId }, { it.favourite == true })

/**
 * Count for each filter chip, keyed by facet id. Every status/roast facet counts
 * only NON-archived bags (their predicates exclude archived), and "archived"
 * counts the archived ones. This is the single source that settled the phone
 * "All" badge (was `beans.size`, incl. archived) drifting from the tablet's
 * non-archived count while both lists already hid archived bags (issue 28).
 */
fun beanFilterCounts(beans: List<Bean>): Map<String, Int> {
    val nonArchived = beans.filter { it.archivedAt == null }
    return mapOf(
        "all" to nonArchived.size,
        "active" to nonArchived.count { !it.isFrozen },
        "favourite" to nonArchived.count { it.favourite == true },
        "frozen" to nonArchived.count { it.isFrozen },
        "archived" to beans.count { it.archivedAt != null },
        "light" to nonArchived.count { roastBand(it.roastLevel?.toInt()).equals("light", ignoreCase = true) },
        "medium" to nonArchived.count { roastBand(it.roastLevel?.toInt()).equals("medium", ignoreCase = true) },
        "dark" to nonArchived.count { roastBand(it.roastLevel?.toInt()).equals("dark", ignoreCase = true) },
    )
}
