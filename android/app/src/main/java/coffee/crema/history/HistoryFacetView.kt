package coffee.crema.history

import coffee.crema.core.HistoryFacets

/**
 * The History filter options as the screens draw them (#310 A2): the profile
 * pills, the Pinned chip, the method, bean and tag dropdowns. From the
 * index's facets ([fromIndex], SPEC §7: each over the query minus its own
 * dimension), or from the in-memory rows on the fallback path ([fromRows]).
 */
data class HistoryFacetView(
    /** Profiles with shots, most first. */
    val profiles: List<Pair<String, Int>>,
    val pinned: Int,
    /** Methods with shots, most first ("espresso" for machine shots). */
    val methods: List<String>,
    /** The bean dropdown's choices after "All". */
    val beanOptions: List<BeanFilterOption>,
    /** Effective tags with shots, most first. */
    val tags: List<Pair<String, Int>>,
) {
    companion object {
        val EMPTY = HistoryFacetView(emptyList(), 0, emptyList(), emptyList(), emptyList())

        /**
         * [f] as the screens draw it. The coffees keep [beanFilterOptions]'s
         * rules: most shots first, a name two roasters share gets the roaster,
         * and a deep-linked bag ([current]) leads, named from the live library,
         * else [bagName] (a row of it), else "Unnamed bag".
         */
        fun fromIndex(
            f: HistoryFacets,
            current: String?,
            live: (beanId: String) -> Pair<String, String?>?,
            bagName: () -> String?,
        ): HistoryFacetView {
            val coffees = f.coffees.mapNotNull { c -> c.name?.trim()?.takeIf { it.isNotEmpty() }?.let { Triple(c, it, c.roaster?.trim()?.takeIf { r -> r.isNotEmpty() }) } }
                .sortedWith(compareByDescending<Triple<coffee.crema.core.HistoryCoffeeFacet, String, String?>> { it.first.count.toInt() }.thenBy { it.second.lowercase() })
            val nameCounts = coffees.groupingBy { it.second.lowercase() }.eachCount()
            val options = coffees.map { (c, name, roaster) ->
                val label = if ((nameCounts[name.lowercase()] ?: 0) > 1 && roaster != null) "$name · $roaster" else name
                BeanFilterOption(COFFEE_FILTER_PREFIX + c.coffeeKey, label)
            }
            val bag = current?.takeIf { !it.startsWith(COFFEE_FILTER_PREFIX) }
            val beanOptions = if (bag == null) options else {
                val name = live(bag)?.first?.trim()?.takeIf { it.isNotEmpty() } ?: bagName()?.trim()?.takeIf { it.isNotEmpty() } ?: "Unnamed bag"
                listOf(BeanFilterOption(bag, "This bag · $name")) + options
            }
            return HistoryFacetView(
                profiles = f.profiles.map { it.value to it.count.toInt() },
                pinned = f.pinned.toInt(),
                methods = f.methods.map { it.value },
                beanOptions = beanOptions,
                tags = f.tags.map { it.value to it.count.toInt() },
            )
        }

        /** The same choices over every in-memory row, as the screens made them before the index. */
        fun fromRows(
            history: List<ShotMeta>,
            live: (beanId: String) -> Pair<String, String?>?,
            liveTags: (beanId: String) -> List<String>?,
            current: String?,
        ): HistoryFacetView = HistoryFacetView(
            profiles = history.mapNotNull { it.profileName?.takeIf { n -> n.isNotEmpty() } }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value },
            pinned = history.count { it.pinned },
            methods = history.groupingBy { it.methodOf ?: "espresso" }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key },
            beanOptions = beanFilterOptions(history, live, current),
            tags = history.flatMap { effectiveTags(it, liveTags).distinct() }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value },
        )
    }
}

/** A live bag's tags by id, for the effective tags (SPEC §6). */
fun liveTagLookup(beans: List<coffee.crema.core.Bean>): (String) -> List<String>? {
    val byId = beans.associateBy({ it.id }, { it.tags.orEmpty() })
    return { id -> byId[id] }
}

/**
 * The live libraries the index's coffee scope, effective tags and custom
 * method labels read (SPEC §5, §6), from the app's bean library, roasters
 * (a merged duplicate roaster resolved, as [liveCoffeeLookup] does) and
 * custom methods.
 */
fun historyLiveOf(
    beans: List<coffee.crema.core.Bean>,
    roasters: List<coffee.crema.core.Roaster>,
    methods: List<coffee.crema.core.CustomBrewMethod>,
): coffee.crema.core.HistoryLive {
    val names = liveCoffeeLookup(beans, roasters)
    return coffee.crema.core.HistoryLive(
        beans = beans.map { b ->
            coffee.crema.core.HistoryLiveBean(beanId = b.id, name = b.name, roaster = names(b.id)?.second, tags = b.tags.orEmpty())
        },
        methods = methods.map { coffee.crema.core.HistoryLiveMethod(method = it.id, label = it.label) },
    )
}
