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
fun filterAndSortShots(
    history: List<StoredShot>,
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
    coffeeOf: (StoredShot) -> String? = { shotCoffee(it) { null }?.key },
): List<StoredShot> {
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
        matchesSearch && matchesRange && matchesProfile && matchesBean && matchesMethod
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

/**
 * True when the shot's text fields contain [query] (case-insensitive): profile,
 * brew method, recipe, notes, next-time plan, the bean snapshot's roaster /
 * name / grinder, the shot's own grinder model, barista and drinker. A blank
 * query matches everything. Mirrors the web `shotMatchesQuery`.
 */
fun shotMatchesQuery(s: StoredShot, query: String): Boolean {
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
fun shotCoffee(s: StoredShot, live: (beanId: String) -> Pair<String, String?>?): ShotCoffee? {
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
    history: List<StoredShot>,
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
fun recentNames(history: List<StoredShot>, field: String): List<String> {
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
