package coffee.crema.profiles

/*
 * Profile-library filter + sort — the facet fallback, search predicate, facet
 * filter, and sort that the tablet (ProfilesScreen) and phone (PhoneProfilesScreen)
 * share. Pure over the in-memory library so both shells produce identical results
 * (issue 28).
 */

/**
 * The facet actually in effect. The Hidden facet draws only the archived built-ins;
 * once nothing is hidden (e.g. the last one was just restored) it falls back to
 * "all" so the grid never strands on an empty hidden view. Both shells also key
 * their selected filter chip off this (not the raw `filter`).
 */
fun effectiveProfileFilter(filter: String, hiddenProfileIds: Set<String>): String =
    if (filter == "hidden" && hiddenProfileIds.isEmpty()) "all" else filter

/**
 * The profile library after search + facet filter + sort. [filter] is the raw
 * facet (all / pinned / hidden / light / medium / dark) — the Hidden→All fallback
 * is applied internally via [effectiveProfileFilter]. [sort] is name (default) /
 * roast / pinned; [sortDesc] reverses the ascending base.
 */
fun filterAndSortProfiles(
    profiles: List<CremaProfile>,
    hiddenProfileIds: Set<String>,
    query: String,
    filter: String,
    sort: String,
    sortDesc: Boolean,
): List<CremaProfile> {
    val effectiveFilter = effectiveProfileFilter(filter, hiddenProfileIds)
    val filtered = profiles.filter { p ->
        val isHidden = p.id in hiddenProfileIds
        (query.isBlank() ||
            p.name.contains(query, ignoreCase = true) ||
            p.tags.any { it.contains(query, ignoreCase = true) } ||
            p.notes.contains(query, ignoreCase = true) ||
            p.author.contains(query, ignoreCase = true) ||
            (p.roast?.contains(query, ignoreCase = true) == true)) &&
            when (effectiveFilter) {
                // Hidden draws only archived built-ins; every other facet excludes them.
                "hidden" -> isHidden
                "pinned" -> !isHidden && p.pinned
                "all" -> !isHidden
                else -> !isHidden && p.roast?.equals(effectiveFilter, ignoreCase = true) == true
            }
    }
    val roastOrder = mapOf("light" to 0, "medium" to 1, "dark" to 2)
    val asc = when (sort) {
        "roast" -> filtered.sortedBy { roastOrder[it.roast?.lowercase()] ?: 3 }
        "pinned" -> filtered.sortedBy { if (it.pinned) 0 else 1 }
        else -> filtered.sortedBy { it.name.lowercase() }
    }
    return if (sortDesc) asc.reversed() else asc
}
