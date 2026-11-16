package coffee.crema.history

import java.util.Calendar

/*
 * Shot-history filter + sort — the search predicate, date-range filter, profile
 * filter, bean filter, and sort that the tablet (HistoryScreen) and phone
 * (PhoneHistoryScreen) share. Pure over the stored log so both shells list
 * shots identically (issue 28). The sort *fields* are `historySortKeys`
 * (ui.screens); this owns the predicate.
 */

/**
 * The shot log after search + date-range + profile filter + bean filter + sort.
 *
 * [range] is today / 7d / 30d / all; [sort] is date (default) / rating / profile /
 * bean / yield / time. [beanFilter] is a bag id (the bean detail's "See all N
 * shots" — scoped by the shot's frozen bean snapshot id), a
 * [COFFEE_FILTER_PREFIX] coffee key (every bag of one coffee, resolved through
 * [coffeeOf]), or null = no bean filter. [now] is the current epoch-ms (passed
 * in for testability); "today" counts shots since local midnight.
 */
fun <T : ShotMeta> filterAndSortShots(
    history: List<T>,
    query: String,
    range: String,
    profileFilter: String?,
    sort: String,
    sortDesc: Boolean,
    now: Long,
    beanFilter: String? = null,
    /**
     * Brew Log method scope (issue #10) — a normalized method string;
     * `"espresso"` covers machine shots + manual espresso logs. Null =
     * no method filter.
     */
    methodFilter: String? = null,
    /** A shot's coffee key ([shotCoffee] with the live library); snapshot-only by default. */
    coffeeOf: (ShotMeta) -> String? = { shotCoffee(it) { null }?.key },
    /** Only pinned shots (#311). */
    pinnedOnly: Boolean = false,
    /**
     * Tags every listed shot carries (AND), through its effective tags
     * ([effectiveTags]): its own, else its live bag's ([liveTags]). #310
     * SPEC §6, owner question 2.
     */
    tagsAll: List<String> = emptyList(),
    /** A live bag's tags, by bag id; none by default. */
    liveTags: (beanId: String) -> List<String>? = { null },
): List<T> {
    val dayMs = 24L * 60L * 60L * 1000L
    val startOfDay = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val filtered = history.filter { s ->
        val matchesSearch = shotMatchesQuery(s, query)
        val matchesRange = when (range) {
            "today" -> s.completedAtMs >= startOfDay
            "7d" -> s.completedAtMs >= now - 7L * dayMs
            "30d" -> s.completedAtMs >= now - 30L * dayMs
            else -> true
        }
        val matchesProfile = profileFilter == null || s.profileName == profileFilter
        val matchesBean = when {
            beanFilter == null -> true
            beanFilter.startsWith(COFFEE_FILTER_PREFIX) -> coffeeOf(s) == beanFilter.removePrefix(COFFEE_FILTER_PREFIX)
            else -> s.bean?.beanId == beanFilter
        }
        val matchesMethod = methodFilter == null || (s.methodOf ?: "espresso") == methodFilter
        val matchesTags = tagsAll.isEmpty() || effectiveTags(s, liveTags).let { tags -> tagsAll.all { it in tags } }
        matchesSearch && matchesRange && matchesProfile && matchesBean && matchesMethod && matchesTags && (!pinnedOnly || s.pinned)
    }
    // SPEC §6's total order: the key, then the time, then the id, all the
    // sort's way, so ties come newest first under a descending sort and oldest
    // first under an ascending one, as the index orders them (#310 A2,
    // REVIEW-448 note 6).
    val byKey: Comparator<T> = when (sort) {
        "rating" -> compareBy { it.rating ?: 0 }
        "profile" -> compareBy { it.profileName?.lowercase() ?: "" }
        "bean" -> compareBy { it.beanLabel?.lowercase() ?: "" }
        "yield" -> compareBy { it.yieldG ?: 0f }
        "time" -> compareBy { it.durationMs }
        else -> compareBy { 0 }
    }
    val asc = byKey.thenBy { it.completedAtMs }.thenBy { it.id }
    return filtered.sortedWith(if (sortDesc) asc.reversed() else asc)
}

/**
 * A shot's effective tags (#310 SPEC §6): its own when it has any, else its
 * live bag's ([liveTags], by bag id). Matched exactly, as the core does.
 */
fun effectiveTags(s: ShotMeta, liveTags: (beanId: String) -> List<String>?): List<String> =
    s.tags.ifEmpty { s.bean?.beanId?.let(liveTags).orEmpty() }

/**
 * [nowMs] floored to the minute — the `now` the History screens pass to
 * [filterAndSortShots], so the memoized result stays valid across the many
 * recompositions a minute can bring instead of re-filtering and re-sorting the
 * whole log on each. Range edges move by under a minute, which no range can
 * show.
 */
fun minuteBucket(nowMs: Long): Long = nowMs - Math.floorMod(nowMs, 60_000L)

/** Milliseconds from [nowMs] to the next [minuteBucket]; never 0, so a loop that
 *  waits this long always moves on. */
fun msToNextMinute(nowMs: Long): Long = 60_000L - Math.floorMod(nowMs, 60_000L)

/**
 * True when the shot's text fields contain [query] (case-insensitive): profile,
 * brew method, recipe, notes, next-time plan, the bean snapshot's roaster /
 * name / grinder, the shot's own grinder model, barista and drinker. A blank
 * query matches everything. Mirrors the web `shotMatchesQuery`.
 */
fun shotMatchesQuery(s: ShotMeta, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return listOf(
        s.profileName,
        coffee.crema.brew.methodLabel(s.methodOf),
        s.recipeName,
        s.notes,
        s.nextPlan,
        s.beanLabel,
        s.bean?.grinder,
        s.grinderModel,
        s.barista,
        s.drinker,
    ).any { it?.contains(q, ignoreCase = true) == true }
}

/** Prefix that marks a bean-filter value as a coffee key, not a bag id. */
const val COFFEE_FILTER_PREFIX = "coffee:"

/**
 * The grouping key for "the same coffee": roaster + coffee name, trimmed,
 * case-folded and whitespace-collapsed (the roaster duplicate merge's name
 * rule). Null without a coffee name. Mirrors the web `coffeeKey`.
 */
fun coffeeKey(name: String?, roaster: String?): String? {
    fun norm(v: String?) = v.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")
    val n = norm(name)
    if (n.isEmpty()) return null
    return "${norm(roaster)}|$n"
}

/** The coffee a shot was pulled with. */
data class ShotCoffee(val key: String, val name: String, val roaster: String?)

/**
 * Resolve the coffee a shot was pulled with: a bag still in the library answers
 * via [live] (name + roaster, so a rename keeps every bag's shots together and
 * a merged duplicate roaster resolves to its canonical row); otherwise the
 * frozen snapshot answers. Mirrors the web `shotCoffee`.
 */
fun shotCoffee(s: ShotMeta, live: (beanId: String) -> Pair<String, String?>?): ShotCoffee? {
    val bean = s.bean ?: return null
    val fromLive = bean.beanId?.let(live)
    val name = (fromLive?.first ?: bean.name).trim()
    val roaster = (if (fromLive != null) fromLive.second else bean.roasterName)?.trim()?.takeIf { it.isNotEmpty() }
    val key = coffeeKey(name, roaster) ?: return null
    return ShotCoffee(key, name, roaster)
}

/** One bean-filter choice: the filter value and its label. */
data class BeanFilterOption(val key: String, val label: String)

/**
 * The bean filter's choices after "All": when [current] is a single bag (the
 * bean detail's deep link), a "This bag · name" entry first, then every coffee
 * with shots, most shots first. A coffee name two roasters share gets the
 * roaster appended. Picking a coffee lists every bag of it.
 */
fun beanFilterOptions(
    history: List<ShotMeta>,
    live: (beanId: String) -> Pair<String, String?>?,
    current: String?,
): List<BeanFilterOption> {
    val coffees = history.mapNotNull { shotCoffee(it, live) }
    val counts = coffees.groupingBy { it.key }.eachCount()
    val firstByKey = coffees.distinctBy { it.key }
    val nameCounts = firstByKey.groupingBy { it.name.lowercase() }.eachCount()
    val options = firstByKey
        .sortedWith(compareByDescending<ShotCoffee> { counts[it.key] ?: 0 }.thenBy { it.name.lowercase() })
        .map { c ->
            val label = if ((nameCounts[c.name.lowercase()] ?: 0) > 1 && c.roaster != null) "${c.name} · ${c.roaster}" else c.name
            BeanFilterOption(COFFEE_FILTER_PREFIX + c.key, label)
        }
    val bag = current?.takeIf { !it.startsWith(COFFEE_FILTER_PREFIX) } ?: return options
    val bagName = live(bag)?.first?.trim()?.takeIf { it.isNotEmpty() }
        ?: history.firstOrNull { it.bean?.beanId == bag }?.bean?.name?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Unnamed bag"
    return listOf(BeanFilterOption(bag, "This bag · $bagName")) + options
}

/**
 * Names already used for [field] (`"barista"` / `"drinker"`), most recent
 * first, de-duplicated case-insensitively (newest spelling wins). Feeds the
 * shot editor's suggestions. Mirrors the web `recentNames`.
 */
fun recentNames(history: List<ShotMeta>, field: String): List<String> {
    val seen = HashSet<String>()
    return history.sortedByDescending { it.completedAtMs }.mapNotNull { s ->
        val name = (if (field == "barista") s.barista else s.drinker)?.trim()?.takeIf { it.isNotEmpty() }
        name?.takeIf { seen.add(it.lowercase()) }
    }
}

/**
 * The live library lookup [shotCoffee] wants: a bag's name and roaster name,
 * with a merged duplicate roaster (`canonicalRoasterId`) resolved to the row it
 * was merged into. Null for a bag no longer in the library.
 */
fun liveCoffeeLookup(
    beans: List<coffee.crema.core.Bean>,
    roasters: List<coffee.crema.core.Roaster>,
): (String) -> Pair<String, String?>? {
    val beansById = beans.associateBy { it.id }
    val roastersById = roasters.associateBy { it.id }
    return { id ->
        beansById[id]?.let { bean ->
            val roaster = bean.roasterId?.let { roastersById[it] }
            val canonical = roaster?.canonicalRoasterId?.let { roastersById[it] } ?: roaster
            bean.name to canonical?.name
        }
    }
}
