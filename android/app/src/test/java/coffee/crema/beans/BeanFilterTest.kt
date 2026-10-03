package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanStatusFilter
import coffee.crema.core.SearchHit
import coffee.crema.ui.BeansViewState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android side of the bean-library facets (geota/crema#124).
 *
 * Which bags pass the facets — and every chip count — is decided by the core
 * (`de1_domain::bean_filter`, covered by its Rust tests: archived + roast,
 * archived + search, include-archived counts, the roaster scope…). The native
 * library is not loaded on the JVM, so these tests pin what the shell owns:
 * that the chip state composes instead of one chip replacing another (the
 * #124 bug — Archived and Light were one single-select value), that the
 * composed selection reaches the core intact alongside the search, that the
 * core's counts land on the right chips, and the sort over the core's ids.
 */
class BeanFilterTest {

    private fun bean(
        id: String,
        roaster: String? = null,
        archived: Boolean = false,
        favourite: Boolean = false,
        level: Int? = null,
    ) = Bean(
        id = id,
        name = id,
        metadata = JsonNull,
        createdAt = 0L,
        updatedAt = 0L,
        roasterId = roaster,
        archivedAt = if (archived) 1L else null,
        favourite = favourite,
        roastLevel = level?.toUByte(),
    )

    private val beans = listOf(
        bean("a-live", "r:a", level = 2),
        bean("a-archived", "r:a", archived = true, favourite = true, level = 2),
        bean("b-live", "r:b", level = 8),
        bean("b-archived", "r:b", archived = true, level = 8),
    )

    /** A fake core that records the query it was sent and returns [result]. */
    private class RecordingCore(private val result: String) : (String, String) -> String {
        var query: String? = null
        override fun invoke(beansJson: String, queryJson: String): String {
            query = queryJson
            return result
        }
    }

    private val sampleResult = """
        {"ids":["a-archived"],
         "statusCounts":{"all":1,"active":1,"frozen":0,"favourite":0,"archived":1},
         "roastCounts":{"light":1,"medium":0,"dark":1},
         "tagCounts":[{"tag":"washed","count":1}],
         "archivedHidden":0,"showingArchived":true}
    """.trimIndent()

    @Test
    fun `archived and a roast band are separate chips that compose`() {
        val s = BeansViewState()
        s.selectStatus("archived")
        s.selectRoast("light")
        assertEquals("archived", s.status)
        assertEquals("light", s.roast)
        // Picking a roast first and then Archived keeps the roast, too.
        val t = BeansViewState()
        t.selectRoast("dark")
        t.selectStatus("archived")
        assertEquals("dark", t.roast)
        assertEquals("archived", t.status)
        // Re-tapping clears just that axis.
        t.selectRoast("dark")
        assertNull(t.roast)
        assertEquals("archived", t.status)
        t.selectStatus("archived")
        assertEquals("all", t.status)
    }

    @Test
    fun `phone chip ids address every axis independently`() {
        val s = BeansViewState()
        s.onChip("s:archived")
        s.onChip("r:medium")
        s.onChip("t:washed")
        assertTrue(s.isChipSelected("s:archived"))
        assertTrue(s.isChipSelected("r:medium"))
        assertTrue(s.isChipSelected("t:washed"))
        assertFalse(s.isChipSelected("s:all"))
        s.onChip("x:include-archived")
        assertTrue(s.isChipSelected("x:include-archived"))
        assertTrue(s.hasFilters)
        s.onChip("x:clear")
        assertFalse(s.hasFilters)
        assertTrue(s.isChipSelected("s:all"))
    }

    @Test
    fun `archived plus roast plus search all reach the core query`() {
        val s = BeansViewState(status = "archived", roast = "light", tags = setOf("washed"))
        val core = RecordingCore(sampleResult)
        val hits = SearchResults.of(listOf(SearchHit("a-archived", 1f, emptyList())))
        val result = filterBeanFacets(beans, s.facets(scopeId = null), hits, core)
        val q = Json.parseToJsonElement(core.query!!).jsonObject
        assertEquals("archived", q["status"]!!.jsonPrimitive.content)
        assertEquals("light", q["roast"]!!.jsonPrimitive.content)
        assertEquals(listOf("washed"), q["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("a-archived"), q["matchIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(q["includeArchived"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("a-archived"), result.ids)
    }

    @Test
    fun `no query sends no match ids, and include-archived is sent`() {
        val core = RecordingCore(sampleResult)
        filterBeanFacets(beans, BeanFacets(includeArchived = true, roasterId = "r:a"), SearchResults.INACTIVE, core)
        val q = Json.parseToJsonElement(core.query!!).jsonObject
        assertTrue(q["matchIds"] == null || q["matchIds"] is JsonNull)
        assertTrue(q["includeArchived"]!!.jsonPrimitive.boolean)
        assertEquals("r:a", q["roasterId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the core's counts land on the matching chips`() {
        val result = filterBeanFacets(beans, BeanFacets(), SearchResults.INACTIVE, RecordingCore(sampleResult))
        val counts = result.chipCounts()
        assertEquals(1, counts["archived"])
        assertEquals(1, counts["light"])
        assertEquals(1, counts["dark"])
        assertEquals(0, counts["medium"])
        assertEquals("washed", result.tagCounts.single().tag)
        assertTrue(result.showingArchived)
    }

    @Test
    fun `a failing core call shows the whole library rather than nothing`() {
        val result = filterBeanFacets(beans, BeanFacets(status = "archived"), SearchResults.INACTIVE) { _, _ -> error("ffi") }
        assertEquals(beans.map { it.id }, result.ids)
    }

    @Test
    fun `status strings map onto the core enum`() {
        assertEquals(BeanStatusFilter.Archived, BeanFacets(status = "archived").toCoreQuery(SearchResults.INACTIVE).status)
        assertEquals(BeanStatusFilter.All, BeanFacets(status = "bogus").toCoreQuery(SearchResults.INACTIVE).status)
    }

    @Test
    fun `include archived only applies where it changes the list`() {
        assertTrue(includeArchivedApplies(BeanFacets(status = "all")))
        assertTrue(includeArchivedApplies(BeanFacets(status = "favourite")))
        assertFalse(includeArchivedApplies(BeanFacets(status = "archived")))
        assertFalse(includeArchivedApplies(BeanFacets(status = "active")))
        assertFalse(includeArchivedApplies(BeanFacets(status = "all", roasterId = "r:a")))
    }

    @Test
    fun `sort keeps only the core's ids and pins the loaded bag then favourites`() {
        val ids = listOf("b-archived", "a-archived", "b-live")
        val byName = sortFilteredBeans(beans, ids, SearchResults.INACTIVE, "name", false, activeId = "b-live")
        assertEquals(listOf("b-live", "a-archived", "b-archived"), byName.map { it.id })
        val roastDesc = sortFilteredBeans(beans, ids, SearchResults.INACTIVE, "roast", true, activeId = null)
        assertEquals(listOf("a-archived", "b-archived", "b-live"), roastDesc.map { it.id })
    }

    @Test
    fun `opening a shelf starts unfiltered`() {
        val s = BeansViewState(status = "archived", roast = "dark", includeArchived = true)
        s.openShelf("r:a")
        assertEquals("all", s.status)
        assertNull(s.roast)
        assertFalse(s.includeArchived)
        assertEquals("r:a", s.facets(s.roasterScopeId).roasterId)
    }

    @Test
    fun `the saver round-trips every axis and splits a legacy roast facet`() {
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val s = BeansViewState(status = "archived", roast = "light", tags = setOf("a", "b"), includeArchived = true)
        val saved = with(BeansViewState.Saver) { scope.save(s) }!!
        val back = BeansViewState.Saver.restore(saved)!!
        assertEquals("archived", back.status)
        assertEquals("light", back.roast)
        assertEquals(setOf("a", "b"), back.tags)
        assertTrue(back.includeArchived)
        // A pre-#124 saved state held one facet, possibly a roast band.
        val legacy = BeansViewState.Saver.restore(listOf("bags", "medium", null, null, "false", ""))!!
        assertEquals("all", legacy.status)
        assertEquals("medium", legacy.roast)
    }

    @Test
    fun `roaster card label hints at archived bags`() {
        assertEquals("2 bags · 1 archived", roasterBagCountLabel(beans, "r:a"))
        assertEquals("1 bag", roasterBagCountLabel(listOf(bean("x", "r:c")), "r:c"))
    }
}
