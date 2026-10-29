package coffee.crema.history

import coffee.crema.core.ShotBean
import coffee.crema.ui.components.personSuggestions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * History search + "same coffee" bean filter + person suggestions (dye2 audit
 * #1, #8, #9). Pure JVM; mirrors the web `search.vitest.ts`.
 */
class ShotFilterTest {
    private fun shot(
        id: String,
        bean: ShotBean? = null,
        at: Long = 1_700_000_000_000,
        barista: String? = null,
        drinker: String? = null,
    ) = StoredShot(
        id = id, completedAtMs = at, durationMs = 27_000, profileName = "Adaptive v3",
        bean = bean, barista = barista, drinker = drinker,
    )

    private fun bean(id: String, name: String, roaster: String?, grinder: String? = null) =
        ShotBean(beanId = id, name = name, roasterName = roaster, grinder = grinder)

    private fun filter(history: List<StoredShot>, query: String = "", beanFilter: String? = null) =
        filterAndSortShots(history, query, "all", null, "date", true, 0L, beanFilter).map { it.id }

    @Test
    fun `search reaches the bean, roaster, grinder and people`() {
        val s = shot("a", bean("b1", "Kenya AA", "Sey", grinder = "Niche Zero"), barista = "Ana", drinker = "Ben")
        for (q in listOf("kenya", "SEY", "niche", "ana", " ben ", "adaptive")) {
            assertTrue(shotMatchesQuery(s, q), q)
        }
        assertFalse(shotMatchesQuery(s, "ethiopia"))
        assertTrue(shotMatchesQuery(s, "  "))
    }

    @Test
    fun `search reaches the shot's own grinder model, like the web (cohort review 06 #14)`() {
        val imported = shot("a").copy(grinderModel = "Niche Zero")
        assertTrue(shotMatchesQuery(imported, "niche"))
        assertFalse(shotMatchesQuery(shot("b"), "niche"))
    }

    @Test
    fun `a coffee filter lists every bag of it, a bag filter only that bag`() {
        val history = listOf(
            shot("1", bean("bag-1", "Kenya AA", "Sey")),
            shot("2", bean("bag-2", "kenya  aa", "SEY")),
            shot("3", bean("bag-3", "Kenya AA", "Onyx")),
        )
        val key = COFFEE_FILTER_PREFIX + coffeeKey("Kenya AA", "Sey")
        assertEquals(listOf("1", "2"), filter(history, beanFilter = key).sorted())
        assertEquals(listOf("2"), filter(history, beanFilter = "bag-2"))
    }

    @Test
    fun `the live library wins, so a renamed bag stays with its coffee`() {
        val old = shot("1", bean("bag-1", "Kenia", "Sey"))
        val live: (String) -> Pair<String, String?>? = { if (it == "bag-1") "Kenya AA" to "Sey" else null }
        assertEquals(ShotCoffee("sey|kenya aa", "Kenya AA", "Sey"), shotCoffee(old, live))
        assertNull(shotCoffee(shot("2"), live))
        assertNull(coffeeKey(" ", "Sey"))
    }

    @Test
    fun `options list coffees by shot count, a deep-linked bag first, shared names disambiguated`() {
        val history = listOf(
            shot("1", bean("bag-1", "Kenya AA", "Sey")),
            shot("2", bean("bag-2", "Kenya AA", "Sey")),
            shot("3", bean("bag-3", "Kenya AA", "Onyx")),
        )
        val none: (String) -> Pair<String, String?>? = { null }
        val options = beanFilterOptions(history, none, current = null)
        assertEquals(listOf("Kenya AA · Sey", "Kenya AA · Onyx"), options.map { it.label })
        val scoped = beanFilterOptions(history, none, current = "bag-2")
        assertEquals(BeanFilterOption("bag-2", "This bag · Kenya AA"), scoped.first())
        assertEquals(3, scoped.size)
    }

    @Test
    fun `recent names are newest first and de-duplicated`() {
        val history = listOf(
            shot("1", at = 1, barista = "Ana"),
            shot("2", at = 3, barista = "ana "),
            shot("3", at = 2, barista = "Ben"),
            shot("4", at = 4, drinker = "Cy"),
        )
        assertEquals(listOf("ana", "Ben"), recentNames(history, "barista"))
        assertEquals(listOf("Cy"), recentNames(history, "drinker"))
        assertEquals(listOf("Ben"), personSuggestions(listOf("Ana", "Ben"), "b"))
        assertEquals(emptyList(), personSuggestions(listOf("Ana"), "ana"))
    }
}
