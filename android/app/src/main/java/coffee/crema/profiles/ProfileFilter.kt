package coffee.crema.profiles

import coffee.crema.pinActiveThenFavourite

/*
 * Profile-library filter + sort — the facet fallback, search predicate, facet
 * filter, and sort that the tablet (ProfilesScreen) and phone (PhoneProfilesScreen)
 * share. Pure over the in-memory library so both shells produce identical results
 * (issue 28).
 */

/**
 * The status actually in effect. The Hidden status draws only the archived
 * built-ins; once nothing is hidden (e.g. the last one was just restored) it
 * falls back to "all" so the grid never strands on an empty hidden view. Both
 * shells also key their selected status chip off this (not the raw `status`).
 */
fun effectiveProfileFilter(status: String, hiddenProfileIds: Set<String>): String =
    if (status == "hidden" && hiddenProfileIds.isEmpty()) "all" else status

/** Search over name / tags / notes / author / roast (blank = all). */
private fun CremaProfile.matchesQuery(query: String): Boolean =
    query.isBlank() ||
        name.contains(query, ignoreCase = true) ||
        tags.any { it.contains(query, ignoreCase = true) } ||
        notes.contains(query, ignoreCase = true) ||
        author.contains(query, ignoreCase = true) ||
        (roast?.contains(query, ignoreCase = true) == true)

/** Status axis: Hidden draws only archived built-ins; every other status excludes them. */
private fun CremaProfile.matchesStatus(status: String, hiddenProfileIds: Set<String>): Boolean {
    val isHidden = id in hiddenProfileIds
    return when (status) {
        "hidden" -> isHidden
        "pinned" -> !isHidden && pinned
        else -> !isHidden
    }
}

private fun CremaProfile.matchesRoast(roast: String?): Boolean =
    roast == null || this.roast?.equals(roast, ignoreCase = true) == true

/**
 * The profile library after search + status + roast + sort (geota/crema#124:
 * two independent axes, so Hidden + Light is "hidden light-roast profiles";
 * they used to share one single-select facet, and browsing Hidden dropped the
 * roast filter). [status] is the raw status (all / pinned / hidden) — the
 * Hidden→All fallback is applied internally via [effectiveProfileFilter];
 * [roast] is light / medium / dark or null. [sort] is name (default) / roast /
 * pinned; [sortDesc] reverses the ascending base.
 */
fun filterAndSortProfiles(
    profiles: List<CremaProfile>,
    hiddenProfileIds: Set<String>,
    query: String,
    status: String,
    roast: String?,
    sort: String,
    sortDesc: Boolean,
    activeId: String?,
): List<CremaProfile> {
    val effectiveStatus = effectiveProfileFilter(status, hiddenProfileIds)
    val filtered = profiles.filter { p ->
        p.matchesQuery(query) && p.matchesStatus(effectiveStatus, hiddenProfileIds) && p.matchesRoast(roast)
    }
    val roastOrder = mapOf("light" to 0, "medium" to 1, "dark" to 2)
    val asc = when (sort) {
        "roast" -> filtered.sortedBy { roastOrder[it.roast?.lowercase()] ?: 3 }
        "pinned" -> filtered.sortedBy { if (it.pinned) 0 else 1 }
        else -> filtered.sortedBy { it.name.lowercase() }
    }
    val sorted = if (sortDesc) asc.reversed() else asc
    // Loaded profile to the top, then pinned favourites, then the rest; [sort]
    // above is the within-group order. Shared with beans + the Brew pickers.
    return sorted.pinActiveThenFavourite({ it.id == activeId }, { it.pinned })
}

/**
 * Faceted chip counts keyed by chip id: each status chip (all / pinned /
 * hidden) counts given the roast + search, each roast chip (light / medium /
 * dark) given the status + search — so a badge always equals what tapping its
 * chip would show.
 */
fun profileChipCounts(
    profiles: List<CremaProfile>,
    hiddenProfileIds: Set<String>,
    query: String,
    status: String,
    roast: String?,
): Map<String, Int> {
    val searched = profiles.filter { it.matchesQuery(query) }
    val effectiveStatus = effectiveProfileFilter(status, hiddenProfileIds)
    val inRoast = searched.filter { it.matchesRoast(roast) }
    val inStatus = searched.filter { it.matchesStatus(effectiveStatus, hiddenProfileIds) }
    return buildMap {
        listOf("all", "pinned", "hidden").forEach { s -> put(s, inRoast.count { it.matchesStatus(s, hiddenProfileIds) }) }
        listOf("light", "medium", "dark").forEach { r -> put(r, inStatus.count { it.matchesRoast(r) }) }
    }
}

/**
 * Brew-PICKER order: the same active→pinned→rest grouping as the library
 * [filterAndSortProfiles] (both via [pinActiveThenFavourite]), over a list the
 * caller already filtered (non-hidden) — within-group order is store order. The
 * phone Brew dropdown + tablet switch popup share it. "Surfaced differently,
 * ranked the same." Mirrors the web HeaderPicker.
 */
fun rankProfilesForPicker(profiles: List<CremaProfile>, activeId: String?): List<CremaProfile> =
    profiles.pinActiveThenFavourite({ it.id == activeId }, { it.pinned })
