package coffee.crema.profiles

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Profiles' filter rail as two axes (geota/crema#124): the Hidden status
 * (archived built-ins) composes with the roast chip and the search instead of
 * replacing them, and every chip count is given the other axis.
 */
class ProfileFilterTest {
    private val profiles = listOf(
        CremaProfile(id = "v-light", name = "Bloom", roast = "light", pinned = true),
        CremaProfile(id = "v-dark", name = "Classic", roast = "dark"),
        CremaProfile(id = "h-light", name = "Gentle", roast = "light"),
        CremaProfile(id = "h-dark", name = "Turbo", roast = "dark", author = "Ana"),
    )
    private val hidden = setOf("h-light", "h-dark")

    private fun ids(status: String, roast: String? = null, query: String = "") =
        filterAndSortProfiles(profiles, hidden, query, status, roast, "name", false, null).map { it.id }

    @Test
    fun `hidden composes with a roast band`() {
        assertEquals(listOf("h-light"), ids("hidden", "light"))
        assertEquals(listOf("h-dark"), ids("hidden", "dark"))
    }

    @Test
    fun `hidden composes with the search`() {
        assertEquals(listOf("h-dark"), ids("hidden", query = "ana"))
        assertEquals(emptyList<String>(), ids("hidden", "light", query = "ana"))
    }

    @Test
    fun `the default view still leaves hidden built-ins out`() {
        assertEquals(listOf("v-light", "v-dark"), ids("all"))
        assertEquals(listOf("v-light"), ids("pinned"))
        assertEquals(listOf("v-light"), ids("all", "light"))
    }

    @Test
    fun `counts are faceted across the two axes`() {
        val light = profileChipCounts(profiles, hidden, "", "all", "light")
        assertEquals(1, light["all"])
        assertEquals(1, light["pinned"])
        assertEquals(1, light["hidden"])
        val hid = profileChipCounts(profiles, hidden, "", "hidden", null)
        assertEquals(1, hid["light"])
        assertEquals(1, hid["dark"])
        assertEquals(0, hid["medium"])
        assertEquals(2, hid["hidden"])
    }

    @Test
    fun `hidden falls back to all once nothing is hidden`() {
        assertEquals(listOf("v-light", "v-dark", "h-light", "h-dark").sorted(),
            filterAndSortProfiles(profiles, emptySet(), "", "hidden", null, "name", false, null).map { it.id }.sorted())
    }
}
