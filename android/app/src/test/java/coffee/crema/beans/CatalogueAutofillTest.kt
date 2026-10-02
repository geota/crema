package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanOrigin
import coffee.crema.core.CatalogueAutofill
import coffee.crema.core.CatalogueCoffeeBag
import coffee.crema.core.CataloguePage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bean editors' Visualizer catalogue search: the debounced controller and
 * the editor-fields ⇄ core-Bean mapping around the core autofill call. The
 * autofill RULE (empty-only / replace-all) is pinned by the core tests
 * (`visualizer_catalogue`); here the UniFFI call is a JVM fake so these pin the
 * shell wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueAutofillTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun entry(name: String = "Ethiopia Guji Hambela") = CatalogueCoffeeBag(
        id = "cb-1",
        canonicalRoasterId = "cr-1",
        roasterName = "Onyx Coffee Lab",
        name = name,
        roastLevel = "Light",
        country = "Ethiopia",
        processing = "Washed",
        meta = "Ethiopia · Washed",
    )

    private fun page(vararg names: String) =
        CataloguePage(entries = names.map { entry(it) }, count = names.size.toUInt(), page = 1u, pages = 1u)

    private fun bean() = Bean(id = "bean:1", name = "", metadata = JsonNull, createdAt = 0L, updatedAt = 0L)

    private fun fields() = CatalogueFields(
        name = "", roaster = "", roast = null, country = "Kenya", region = "", farmer = "",
        variety = "", elevation = "", processing = "", harvestTime = "", tastingNotes = "",
        url = "", canonicalCoffeeBagId = null, canonicalRoasterId = null,
    )

    // ── controller ─────────────────────────────────────────────────────────

    @Test
    fun `typing is debounced into one search for the final trimmed query`() = runTest {
        val seen = mutableListOf<String>()
        val c = CatalogueSearchController(backgroundScope, { q -> seen += q; page(q) })
        c.setQuery("on")
        advanceTimeBy(100)
        c.setQuery("ony")
        advanceTimeBy(100)
        c.setQuery("onyx ")
        assertTrue(c.state.value.loading)
        advanceTimeBy(299)
        runCurrent()
        assertTrue(seen.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("onyx"), seen)
        assertEquals("onyx", c.state.value.results.single().name)
        assertFalse(c.state.value.loading)
    }

    @Test
    fun `queries under the minimum are never sent`() = runTest {
        val seen = mutableListOf<String>()
        val c = CatalogueSearchController(backgroundScope, { q -> seen += q; page(q) })
        c.setQuery(" o ")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(seen.isEmpty())
        assertEquals(CatalogueSearchController.State(query = "o"), c.state.value)
    }

    @Test
    fun `a slow earlier search never overwrites a newer one`() = runTest {
        val slow = CompletableDeferred<CataloguePage>()
        val c = CatalogueSearchController(backgroundScope, { q -> if (q == "slow") slow.await() else page(q) })
        c.setQuery("slow")
        advanceTimeBy(301)
        runCurrent()
        c.setQuery("fast")
        advanceTimeBy(301)
        runCurrent()
        slow.complete(page("slow"))
        runCurrent()
        assertEquals("fast", c.state.value.results.single().name)
    }

    @Test
    fun `a failure surfaces its message and clear resets`() = runTest {
        val c = CatalogueSearchController(backgroundScope, { throw IllegalStateException("Visualizer HTTP 500") })
        c.setQuery("onyx")
        advanceTimeBy(301)
        runCurrent()
        assertEquals("Visualizer HTTP 500", c.state.value.error)
        assertTrue(c.state.value.results.isEmpty())
        c.clear()
        assertEquals(CatalogueSearchController.State(), c.state.value)
    }

    @Test
    fun `a result's subline is roaster then country and process`() {
        assertEquals("Onyx Coffee Lab · Ethiopia · Washed", entry().subline())
        assertEquals("Onyx Coffee Lab", entry().copy(meta = "").subline())
    }

    // ── fields ⇄ core ──────────────────────────────────────────────────────

    @Test
    fun `the core gets the editor values, an unset roast and whether a roaster is typed`() {
        var sent: Triple<String, String, Pair<Boolean, Boolean>>? = null
        val fakeCore: (String, String, Boolean, Boolean) -> String = { b, e, roasterSet, replaceAll ->
            sent = Triple(b, e, roasterSet to replaceAll)
            json.encodeToString(CatalogueAutofill.serializer(), CatalogueAutofill(bean = json.decodeFromString(Bean.serializer(), b), filled = emptyList()))
        }
        autofillFromCatalogue(fields().copy(roaster = "Onyx"), bean(), entry(), replaceAll = true, core = fakeCore)
        val (beanJson, entryJson, flags) = sent!!
        val b = json.parseToJsonElement(beanJson).jsonObject
        assertEquals("Kenya", b["origin"]!!.jsonObject["country"]!!.jsonPrimitive.content)
        assertNull("untouched roast is unset, not the picker default", b["roastLevel"])
        assertEquals("cb-1", json.parseToJsonElement(entryJson).jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(true to true, flags)
    }

    @Test
    fun `the core result maps back onto every editor field and the links`() {
        val filledBean = bean().copy(
            name = "Ethiopia Guji Hambela",
            roastLevel = 2u,
            origin = BeanOrigin(country = "Kenya", region = "Guji", processing = "Washed"),
            tastingNotes = "Bergamot",
            url = "https://onyx.coffee/h",
            canonicalCoffeeBagId = "cb-1",
            canonicalRoasterId = "cr-1",
        )
        val fakeCore: (String, String, Boolean, Boolean) -> String = { _, _, _, _ ->
            json.encodeToString(
                CatalogueAutofill.serializer(),
                CatalogueAutofill(bean = filledBean, roasterName = "Onyx Coffee Lab", filled = listOf("name", "roaster", "origin.region")),
            )
        }
        val (f, filled) = autofillFromCatalogue(fields(), bean(), entry(), replaceAll = false, core = fakeCore)
        assertEquals("Ethiopia Guji Hambela", f.name)
        assertEquals("Onyx Coffee Lab", f.roaster)
        assertEquals(2, f.roast)
        assertEquals("Kenya", f.country)
        assertEquals("Guji", f.region)
        assertEquals("Washed", f.processing)
        assertEquals("", f.farmer)
        assertEquals("Bergamot", f.tastingNotes)
        assertEquals("https://onyx.coffee/h", f.url)
        assertEquals("cb-1", f.canonicalCoffeeBagId)
        assertEquals("cr-1", f.canonicalRoasterId)
        assertEquals(3, filled.size)
        assertEquals("Filled 3 fields from the catalogue.", catalogueFillStatus(filled.size))
        assertEquals("Linked — every field was already filled.", catalogueFillStatus(0))
    }

    @Test
    fun `a null roaster name from the core keeps the typed roaster`() {
        val fakeCore: (String, String, Boolean, Boolean) -> String = { b, _, _, _ ->
            json.encodeToString(CatalogueAutofill.serializer(), CatalogueAutofill(bean = json.decodeFromString(Bean.serializer(), b), filled = emptyList()))
        }
        val (f, _) = autofillFromCatalogue(fields().copy(roaster = "My roaster"), bean(), entry(), false, fakeCore)
        assertEquals("My roaster", f.roaster)
    }

    @Test
    fun `Save carries the catalogue links onto the bean`() {
        val draft = BeanDraft(
            name = "Hambela", roast = 2, mixSel = "single", roastTypeSel = "",
            roasted = "", opened = "", frozen = false, archived = false,
            decaf = false, pinned = false, bagSize = 0.0, remaining = 0.0,
            country = "", region = "", farm = "", farmer = "", variety = "",
            elevation = "", processing = "", harvestTime = "", grinder = "", grind = "",
            linkedProfileId = null, rating = 0, qualityScore = "", tastingNotes = "",
            placeOfPurchase = "", cost = "", url = "", notes = "", tags = emptyList(),
            canonicalCoffeeBagId = "cb-1", canonicalRoasterId = "cr-1",
        )
        val out = applyBeanEdits(bean(), draft)
        assertEquals("cb-1", out.canonicalCoffeeBagId)
        assertEquals("cr-1", out.canonicalRoasterId)
        val unlinked = applyBeanEdits(out, draft.copy(canonicalCoffeeBagId = null, canonicalRoasterId = null))
        assertNull(unlinked.canonicalCoffeeBagId)
        assertNull(unlinked.canonicalRoasterId)
    }
}
