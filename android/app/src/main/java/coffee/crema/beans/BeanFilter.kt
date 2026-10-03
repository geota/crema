package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanFilterQuery
import coffee.crema.core.BeanFilterResult
import coffee.crema.core.BeanRoastCounts
import coffee.crema.core.BeanStatusCounts
import coffee.crema.core.BeanStatusFilter
import coffee.crema.core.filterBeans as coreFilterBeans
import coffee.crema.pinActiveThenFavourite
import kotlinx.serialization.json.Json

/*
 * Bean-library filter + sort — what the tablet (BeansScreen) and phone
 * (PhoneBeansScreen) share.
 *
 * The FACETS — status (archived is one more status, geota/crema#124), "include
 * archived", roast band, tags, the roaster scope and the search's matches — and
 * every chip's count run in the core (`de1_domain::bean_filter`, via the FFI),
 * the same function the web PWA calls, so the three lists and their badges
 * agree. Before #124 this file held one single-select facet: picking Archived
 * dropped the roast chip and every roast chip silently hid archived bags, so
 * the archive could be sorted but never filtered.
 *
 * The SORT stays here (the shells offer their own keys), over the core's ids.
 */

/** Wire codec for the facet FFI round-trip. */
private val filterJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * The filter rail's selections — independent axes that compose.
 *
 * [status] is all / active / frozen / favourite / archived; [roast] is light /
 * medium / dark or null; [tags] must all be on the bag; [includeArchived] mixes
 * archived bags (dimmed) into All / Favourite; [roasterId] is the #86 shelf.
 */
data class BeanFacets(
    val status: String = "all",
    val includeArchived: Boolean = false,
    val roast: String? = null,
    val tags: Set<String> = emptySet(),
    val roasterId: String? = null,
)

/** [BeanFacets] + the current search as the core's [BeanFilterQuery]. */
fun BeanFacets.toCoreQuery(hits: SearchResults): BeanFilterQuery = BeanFilterQuery(
    status = BeanStatusFilter.entries.firstOrNull { it.string == status } ?: BeanStatusFilter.All,
    includeArchived = includeArchived,
    roast = roast,
    tags = tags.toList(),
    roasterId = roasterId,
    matchIds = hits.matchedIds(),
)

/** An empty result: no rows, zero counts. */
private fun emptyFilterResult(ids: List<String> = emptyList()) = BeanFilterResult(
    ids = ids,
    statusCounts = BeanStatusCounts(0u, 0u, 0u, 0u, 0u),
    roastCounts = BeanRoastCounts(0u, 0u, 0u),
    tagCounts = emptyList(),
    archivedHidden = 0u,
    showingArchived = false,
)

/**
 * Run the core facet filter. [core] is the FFI call (`(beansJson, queryJson) →
 * resultJson`), injectable so the plumbing is testable on the JVM, where the
 * native library is not loaded. A failing call degrades to the unfiltered
 * library with zero counts — a filter must never make the beans vanish.
 */
fun filterBeanFacets(
    beans: List<Bean>,
    facets: BeanFacets,
    hits: SearchResults,
    core: (String, String) -> String = { b, q -> coreFilterBeans(b, q) },
): BeanFilterResult {
    if (beans.isEmpty()) return emptyFilterResult()
    return runCatching {
        val query = filterJson.encodeToString(BeanFilterQuery.serializer(), facets.toCoreQuery(hits))
        filterJson.decodeFromString(BeanFilterResult.serializer(), core(libraryBeansJson(beans), query))
    }.getOrElse { emptyFilterResult(beans.map { it.id }) }
}

/**
 * The bags the core kept ([ids]), sorted: [sort] is freshest (default) / name /
 * roast / rating / remaining, [sortDesc] reverses it. While [hits] is active,
 * relevance becomes the primary order and the loaded/favourite pinning is
 * suspended (a search is a question; the answer is the best match). Otherwise
 * the loaded ([activeId]) bean is pinned to the top and favourites above the
 * rest (via [pinActiveThenFavourite]) — [sort] is the WITHIN-group order.
 */
fun sortFilteredBeans(
    beans: List<Bean>,
    ids: List<String>,
    hits: SearchResults,
    sort: String,
    sortDesc: Boolean,
    activeId: String?,
): List<Bean> {
    val keep = ids.toHashSet()
    val visible = beans.filter { it.id in keep }
    val asc = when (sort) {
        "name" -> visible.sortedBy { it.name.lowercase() }
        "roast" -> visible.sortedBy { it.roastLevel?.toInt() ?: Int.MAX_VALUE }
        "rating" -> visible.sortedBy { it.rating?.toInt() ?: 0 }
        "remaining" -> visible.sortedBy { it.remaining }
        else -> visible.sortedBy { beanDaysOffRoast(it) ?: Int.MAX_VALUE } // freshest first
    }
    val sorted = if (sortDesc) asc.reversed() else asc
    // Relevance first while searching; `sortedByDescending` is stable, so the
    // sort above survives as the tiebreak between equally-relevant bags.
    if (hits.active) return sorted.sortedByDescending { hits.score(it.id) }
    // Loaded bean to the top, then favourites, then the rest; [sort] above is the
    // within-group order. Shared with profiles + the Brew pickers.
    return sorted.pinActiveThenFavourite({ it.id == activeId }, { it.favourite == true })
}

/**
 * Chip counts keyed by chip id (all / active / favourite / frozen / archived /
 * light / medium / dark), from the same core result as the list — so a badge
 * always equals what tapping its chip shows, given the other selections.
 */
fun BeanFilterResult.chipCounts(): Map<String, Int> = mapOf(
    "all" to statusCounts.all.toInt(),
    "active" to statusCounts.active.toInt(),
    "favourite" to statusCounts.favourite.toInt(),
    "frozen" to statusCounts.frozen.toInt(),
    "archived" to statusCounts.archived.toInt(),
    "light" to roastCounts.light.toInt(),
    "medium" to roastCounts.medium.toInt(),
    "dark" to roastCounts.dark.toInt(),
)

/**
 * Whether the "Include archived" chip changes anything for [facets]: only
 * under All / Favourite (Archived is already archived-only, Active / Frozen
 * never include them) and outside a roaster shelf (which always does).
 */
fun includeArchivedApplies(facets: BeanFacets): Boolean =
    facets.roasterId == null && (facets.status == "all" || facets.status == "favourite")

/** "N bags · M archived" — the roaster-card bag count, archived hinted (#86). */
fun roasterBagCountLabel(beans: List<Bean>, roasterId: String): String {
    val mine = beans.filter { it.roasterId == roasterId }
    val archived = mine.count { it.archivedAt != null }
    val base = "${mine.size} ${if (mine.size == 1) "bag" else "bags"}"
    return if (archived > 0) "$base · $archived archived" else base
}

/**
 * Brew-PICKER order: the same active→favourite→rest grouping as the library
 * [sortFilteredBeans] (both via [pinActiveThenFavourite]), but over a list the
 * caller already filtered (non-archived) — within-group order is store order.
 */
fun rankBeansForPicker(beans: List<Bean>, activeId: String?): List<Bean> =
    beans.pinActiveThenFavourite({ it.id == activeId }, { it.favourite == true })
