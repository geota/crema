package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanOrigin
import coffee.crema.core.CatalogueCoffeeBag
import coffee.crema.core.CatalogueField
import coffee.crema.core.CataloguePick
import coffee.crema.core.CataloguePickRoaster
import coffee.crema.core.CatalogueRoaster
import coffee.crema.core.CatalogueRoasterAutofill
import coffee.crema.core.Roaster
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue clash prompt's shell logic on Android: labels + body copy,
 * the [CatalogueClashGate] decision (no clash → apply at once; a clash → wait
 * for Keep mine / Use catalogue / dismiss), the bag- and roaster-pick flows
 * around the core, and the picked-roaster write. The clash RULES are the
 * core's (`visualizer_catalogue` tests); the UniFFI calls are JVM fakes here.
 */
class CatalogueClashTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val entry = CatalogueCoffeeBag(
        id = "cb-1", canonicalRoasterId = "cr-1", roasterName = "Onyx Coffee Lab",
        name = "Ethiopia Guji Hambela", processing = "Washed", meta = "",
    )
    private val onyx = CatalogueRoaster(id = "cr-1", name = "Onyx Coffee Lab", website = "https://onyx", country = "USA")

    private fun bean() = Bean(id = "bean:1", name = "", metadata = JsonNull, createdAt = 0L, updatedAt = 0L)

    private fun roaster(id: String, name: String) =
        Roaster(id = id, name = name, notes = "", metadata = JsonNull, createdAt = 0L, updatedAt = 0L)

    private fun fields(processing: String = "Natural", roaster: String = "") = CatalogueFields(
        name = "", roaster = roaster, roast = null, country = "", region = "", farmer = "",
        variety = "", elevation = "", processing = processing, harvestTime = "", tastingNotes = "",
        url = "", canonicalCoffeeBagId = null, canonicalRoasterId = null,
    )

    // ── copy ───────────────────────────────────────────────────────────────

    @Test
    fun `labels join naturally and long lists end with and N more`() {
        assertEquals("A", joinFieldLabels(listOf("A")))
        assertEquals("A and B", joinFieldLabels(listOf("A", "B")))
        assertEquals("A, B and C", joinFieldLabels(listOf("A", "B", "C")))
        assertEquals("A, B, C, D and E", joinFieldLabels(listOf("A", "B", "C", "D", "E")))
        assertEquals("A, B, C, D and 2 more", joinFieldLabels(listOf("A", "B", "C", "D", "E", "F")))
    }

    @Test
    fun `the body names the clashing fields with human labels`() {
        assertEquals(
            "The catalogue has different values for Roast level, Process and Tasting notes.",
            clashMessage(listOf(CatalogueField.RoastLevel, CatalogueField.Processing, CatalogueField.TastingNotes)),
        )
        assertEquals(
            "The catalogue has different values for Process and Roaster website.",
            clashMessage(listOf(CatalogueField.Processing, CatalogueField.RoasterWebsite)),
        )
        assertEquals(
            "The catalogue has different values for Name and Country.",
            clashMessage(listOf(CatalogueField.RoasterName, CatalogueField.RoasterCountry), ROASTER_FORM_FIELD_LABELS),
        )
        // Every field id has a label on both forms.
        CatalogueField.entries.forEach {
            assertTrue(it.name, BAG_FORM_FIELD_LABELS.containsKey(it) && ROASTER_FORM_FIELD_LABELS.containsKey(it))
        }
    }

    // ── gate ───────────────────────────────────────────────────────────────

    @Test
    fun `no clash applies empty-fields-only at once without a prompt`() {
        val gate = CatalogueClashGate()
        val applied = mutableListOf<Boolean>()
        gate.offer(emptyList()) { applied += it }
        assertEquals(listOf(false), applied)
        assertNull(gate.pending)
    }

    @Test
    fun `a clash waits for the answer - keep mine, use catalogue, or dismiss`() {
        val gate = CatalogueClashGate()
        val applied = mutableListOf<Boolean>()
        val clashes = listOf(CatalogueField.Processing)

        gate.offer(clashes) { applied += it }
        assertEquals(clashes, gate.pending?.clashes)
        assertTrue(applied.isEmpty())
        gate.resolve(ClashChoice.KeepMine)
        assertEquals(listOf(false), applied)
        assertNull(gate.pending)

        gate.offer(clashes) { applied += it }
        gate.resolve(ClashChoice.UseCatalogue)
        assertEquals(listOf(false, true), applied)

        gate.offer(clashes) { applied += it }
        gate.resolve(null)
        assertEquals("dismiss applies nothing", listOf(false, true), applied)
        assertNull(gate.pending)
        gate.resolve(ClashChoice.KeepMine) // nothing pending → no-op
        assertEquals(listOf(false, true), applied)
    }

    // ── bag pick ───────────────────────────────────────────────────────────

    private fun fakePick(roaster: CataloguePickRoaster?): (String, String, String, String, String, Boolean) -> String =
        { b, _, _, _, _, replaceAll ->
            val inBean = json.decodeFromString(Bean.serializer(), b)
            val proc = if (replaceAll) "Washed" else inBean.origin?.processing ?: "Washed"
            val out = inBean.copy(name = "Ethiopia Guji Hambela", origin = (inBean.origin ?: BeanOrigin()).copy(processing = proc), canonicalCoffeeBagId = "cb-1")
            json.encodeToString(CataloguePick.serializer(), CataloguePick(bean = out, filled = listOf("name"), roaster = roaster))
        }

    @Test
    fun `the clash check sends the editor values, the roaster input, the directory and the fetched roaster`() {
        var sent: List<String>? = null
        val core: (String, String, String, String, String) -> String = { b, e, r, rs, f ->
            sent = listOf(b, e, r, rs, f)
            """["processing","roasterWebsite"]"""
        }
        val input = CataloguePickInput(fields(roaster = "Onyx"), bean(), entry, listOf(roaster("roaster:1", "Onyx")), onyx)
        assertEquals(listOf(CatalogueField.Processing, CatalogueField.RoasterWebsite), catalogueClashes(input, core))
        val (b, e, r, rs, f) = sent!!
        assertEquals("Natural", json.parseToJsonElement(b).jsonObject["origin"]!!.jsonObject["processing"]!!.jsonPrimitive.content)
        assertEquals("cb-1", json.parseToJsonElement(e).jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Onyx", r)
        assertEquals("roaster:1", json.parseToJsonElement(rs).jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("https://onyx", json.parseToJsonElement(f).jsonObject["website"]!!.jsonPrimitive.content)
        // A failed roaster lookup goes over as JSON null (name + link only).
        var fetchedArg: String? = null
        assertTrue(catalogueClashes(input.copy(fetched = null)) { _, _, _, _, fetched -> fetchedArg = fetched; "[]" }.isEmpty())
        assertEquals("null", fetchedArg)
    }

    @Test
    fun `offerBagPick - no clash fills at once, keep mine and use catalogue pick the mode, dismiss changes nothing`() {
        val seed = CataloguePickRoaster(
            name = "Onyx Coffee Lab", isNew = true,
            roaster = roaster("", "Onyx Coffee Lab").copy(website = "https://onyx", country = "USA", catalogueRoasterId = "cr-1"),
            filled = listOf(CatalogueField.RoasterWebsite, CatalogueField.RoasterCountry),
        )
        val input = { CataloguePickInput(fields(), bean(), entry, emptyList(), onyx) }
        val pick: (CataloguePickInput, Boolean) -> CatalogueBagPick = { i, all -> pickFromCatalogue(i, all, fakePick(seed)) }
        val results = mutableListOf<CatalogueBagPick>()
        var errors = 0

        val gate = CatalogueClashGate()
        offerBagPick(gate, input, { results += it }, { errors++ }, clashes = { emptyList() }, pick = pick)
        assertEquals(1, results.size)
        assertEquals("Natural", results[0].fields.processing)
        assertEquals("Onyx Coffee Lab", results[0].fields.roaster)
        assertEquals("cb-1", results[0].fields.canonicalCoffeeBagId)
        assertEquals(3, results[0].filledCount) // name + two seeded roaster fields
        assertEquals(seed, results[0].roaster)

        offerBagPick(gate, input, { results += it }, { errors++ }, clashes = { listOf(CatalogueField.Processing) }, pick = pick)
        assertEquals(1, results.size)
        gate.resolve(ClashChoice.KeepMine)
        assertEquals("Natural", results[1].fields.processing)

        offerBagPick(gate, input, { results += it }, { errors++ }, clashes = { listOf(CatalogueField.Processing) }, pick = pick)
        gate.resolve(ClashChoice.UseCatalogue)
        assertEquals("Washed", results[2].fields.processing)

        offerBagPick(gate, input, { results += it }, { errors++ }, clashes = { listOf(CatalogueField.Processing) }, pick = pick)
        gate.resolve(null)
        assertEquals(3, results.size)
        assertEquals(0, errors)

        offerBagPick(gate, input, { results += it }, { errors++ }, clashes = { error("core") }, pick = pick)
        assertEquals(1, errors)
        assertNull(gate.pending)
    }

    @Test
    fun `a picked seed becomes a fresh row with every catalogue field and an existing row is kept`() {
        val seed = CataloguePickRoaster(
            name = "Onyx Coffee Lab", isNew = true,
            roaster = roaster("", "Onyx Coffee Lab").copy(website = "https://onyx", country = "USA", catalogueRoasterId = "cr-1"),
            filled = emptyList(),
        )
        val row = pickedRoasterRow(seed) { name -> roaster("roaster:new", name).copy(createdAt = 5L) }
        assertEquals("roaster:new", row.id)
        assertEquals(5L, row.createdAt)
        assertEquals("https://onyx", row.website)
        assertEquals("USA", row.country)
        assertEquals("cr-1", row.catalogueRoasterId)
        assertNull(row.canonicalRoasterId)
        val existing = seed.copy(isNew = false, roaster = roaster("roaster:1", "Onyx Coffee Lab").copy(canonicalRoasterId = "roaster:canon"))
        assertEquals(existing.roaster, pickedRoasterRow(existing) { error("not minted") })
        // Held until Save only while the roaster input still names it.
        assertTrue(seed.stillNamedBy(" onyx coffee lab "))
        assertFalse(seed.stillNamedBy("Sey"))
    }

    // ── roaster form ───────────────────────────────────────────────────────

    @Test
    fun `roaster form fields round-trip the core and keep the dup pointer`() {
        val base = roaster("roaster:1", "Onyx").copy(canonicalRoasterId = "roaster:canon", city = "Tulsa")
        val f = RoasterFields(name = "Onyx", website = "", city = "Tulsa", country = "Canada", notes = "n", catalogueRoasterId = null)
        val asCore = f.toRoaster(base)
        assertEquals("roaster:canon", asCore.canonicalRoasterId)
        assertNull(asCore.website)
        var sentReplace: Boolean? = null
        val autofill: (String, String, Boolean) -> String = { r, _, all ->
            sentReplace = all
            val inR = json.decodeFromString(Roaster.serializer(), r)
            val out = inR.copy(website = "https://onyx", country = if (all) "USA" else inR.country, catalogueRoasterId = "cr-1")
            json.encodeToString(CatalogueRoasterAutofill.serializer(), CatalogueRoasterAutofill(out, listOf(CatalogueField.RoasterWebsite)))
        }
        val gate = CatalogueClashGate()
        val applied = mutableListOf<Pair<RoasterFields, Int>>()
        val clashes: (String, String) -> String = { _, _ -> """["roasterCountry"]""" }
        offerRoasterPick(
            gate, { f }, base, onyx, { nf, n -> applied += nf to n }, { error("no error") },
            clashes = { catalogueRoasterClashes(it, base, onyx, clashes) },
            autofill = { nf, all -> autofillRoasterFromCatalogue(nf, base, onyx, all, autofill) },
        )
        assertEquals(listOf(CatalogueField.RoasterCountry), gate.pending?.clashes)
        gate.resolve(ClashChoice.KeepMine)
        assertEquals(false, sentReplace)
        assertEquals("Canada", applied.single().first.country)
        assertEquals("https://onyx", applied.single().first.website)
        assertEquals("cr-1", applied.single().first.catalogueRoasterId)
        assertEquals(1, applied.single().second)
        offerRoasterPick(
            gate, { f }, base, onyx, { nf, n -> applied += nf to n }, { error("no error") },
            clashes = { catalogueRoasterClashes(it, base, onyx, clashes) },
            autofill = { nf, all -> autofillRoasterFromCatalogue(nf, base, onyx, all, autofill) },
        )
        gate.resolve(ClashChoice.UseCatalogue)
        assertEquals("USA", applied.last().first.country)
        offerRoasterPick(
            gate, { f }, base, onyx, { nf, n -> applied += nf to n }, { error("no error") },
            clashes = { catalogueRoasterClashes(it, base, onyx, clashes) },
            autofill = { nf, all -> autofillRoasterFromCatalogue(nf, base, onyx, all, autofill) },
        )
        gate.resolve(null)
        assertEquals(2, applied.size)
        // A blank draft is a valid core input for a new roaster.
        assertEquals("", draftRoaster().name)
        assertEquals(listOf(CatalogueField.RoasterCountry), json.decodeFromString(ListSerializer(CatalogueField.serializer()), clashes("", "")))
    }
}
