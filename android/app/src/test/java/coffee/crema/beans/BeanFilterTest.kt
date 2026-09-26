package coffee.crema.beans

import coffee.crema.core.Bean
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `filterAndSortBeans` semantics around archived bags and the roaster scope
 * (geota/crema#86): unscoped, "All" hides archived bags; scoped to a roaster,
 * it shows the whole shelf. (`beanFilterCounts` follows the same rule but its
 * roast-band counts go through the core FFI, so it is not exercised here.)
 */
class BeanFilterTest {

    private fun bean(id: String, roaster: String?, archived: Boolean = false, favourite: Boolean = false) = Bean(
        id = id,
        name = id,
        metadata = JsonNull,
        createdAt = 0L,
        updatedAt = 0L,
        roasterId = roaster,
        archivedAt = if (archived) 1L else null,
        favourite = favourite,
    )

    private val beans = listOf(
        bean("a-live", "r:a"),
        bean("a-archived", "r:a", archived = true, favourite = true),
        bean("b-live", "r:b"),
        bean("b-archived", "r:b", archived = true),
    )

    private fun ids(filter: String, roasterId: String? = null) =
        filterAndSortBeans(beans, emptyList(), SearchResults.INACTIVE, filter, "name", false, null, roasterId)
            .map { it.id }.sorted()

    @Test
    fun `unscoped All hides archived bags`() {
        assertEquals(listOf("a-live", "b-live"), ids("all"))
        assertEquals(listOf("a-archived", "b-archived"), ids("archived"))
        assertEquals(emptyList<String>(), ids("favourite"))
    }

    @Test
    fun `roaster scope shows the whole shelf, archived included`() {
        assertEquals(listOf("a-archived", "a-live"), ids("all", "r:a"))
        assertEquals(listOf("a-archived"), ids("archived", "r:a"))
        assertEquals(listOf("a-live"), ids("active", "r:a"))
        assertEquals(listOf("a-archived"), ids("favourite", "r:a"))
        assertEquals(listOf("b-archived", "b-live"), ids("all", "r:b"))
    }

    @Test
    fun `roaster card label hints at archived bags`() {
        assertEquals("2 bags · 1 archived", roasterBagCountLabel(beans, "r:a"))
        assertEquals("1 bag", roasterBagCountLabel(listOf(bean("x", "r:c")), "r:c"))
    }
}
