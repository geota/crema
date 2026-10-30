package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanOrigin
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `applyBeanEdits` mapping tests, focused on the fields added for
 * geota/crema#77 (score / harvest / place of purchase / cost) — the ones a
 * Beanconqueror import populates but the editors used to drop on the floor.
 */
class BeanEditsTest {

    private fun bean() = Bean(
        id = "bean:test",
        name = "Yirgacheffe",
        metadata = JsonNull,
        createdAt = 0L,
        updatedAt = 0L,
    )

    /** A draft that leaves every field blank / default. */
    private fun blankDraft() = BeanDraft(
        name = "", roast = 5, mixSel = "single", roastTypeSel = "",
        roasted = "", opened = "", frozen = false, archived = false,
        decaf = false, pinned = false, bagSize = 0.0, remaining = 0.0,
        country = "", region = "", farm = "", farmer = "", variety = "",
        elevation = "", processing = "", harvestTime = "", grinder = "", grind = "",
        linkedProfileId = null, rating = 0, qualityScore = "", tastingNotes = "",
        placeOfPurchase = "", cost = "", url = "", notes = "", tags = emptyList(),
    )

    @Test
    fun `score, harvest, place and cost land on the bean`() {
        val out = applyBeanEdits(
            bean(),
            blankDraft().copy(
                qualityScore = " 88 ",
                harvestTime = "Nov 2025",
                placeOfPurchase = "Counter Culture · Durham",
                cost = "18.50",
            ),
        )
        assertEquals("88", out.qualityScore)
        assertEquals("Nov 2025", out.origin?.harvestTime)
        assertEquals("Counter Culture · Durham", out.placeOfPurchase)
        assertEquals(18.5f, out.cost!!, 0.0001f)
    }

    @Test
    fun `blank fields collapse to null (score to empty string)`() {
        val seeded = bean().copy(
            qualityScore = "A-",
            placeOfPurchase = "somewhere",
            cost = 12f,
            origin = BeanOrigin(harvestTime = "2024 Spring"),
        )
        val out = applyBeanEdits(seeded, blankDraft())
        assertEquals("", out.qualityScore)
        assertNull(out.origin?.harvestTime)
        assertNull(out.placeOfPurchase)
        assertNull(out.cost)
    }

    @Test
    fun `cost text round-trips through costText and parseCost`() {
        // What the editor seeds the field with must parse back to the same value.
        for (stored in listOf(18.5f, 12f, 0f, 1234.56f)) {
            assertEquals(stored, parseCost(costText(stored))!!, 0.001f)
        }
        assertEquals("", costText(null))
        assertEquals("12", costText(12f))
        assertEquals("18.5", costText(18.5f))
    }

    @Test
    fun `parseCost tolerates commas and rejects garbage`() {
        assertEquals(12.5f, parseCost("12,50")!!, 0.0001f)
        assertEquals(12.5f, parseCost(" 12.50 ")!!, 0.0001f)
        assertNull(parseCost(""))
        assertNull(parseCost("free"))
        assertNull(parseCost("-3"))
    }

    @Test
    fun `editing other fields preserves an unchanged imported cost and harvest`() {
        val imported = bean().copy(cost = 21.9f, origin = BeanOrigin(harvestTime = "Oct 2024"))
        // The editor seeds its fields from the bean; the user only renames.
        val out = applyBeanEdits(
            imported,
            blankDraft().copy(
                name = "Renamed",
                cost = costText(imported.cost),
                harvestTime = imported.origin?.harvestTime ?: "",
            ),
        )
        assertEquals("Renamed", out.name)
        assertEquals(21.9f, out.cost!!, 0.0001f)
        assertEquals("Oct 2024", out.origin?.harvestTime)
    }

    // ── Frozen switch (core set_bean_frozen) ──────────────────────────────
    // The rule itself is pinned by the core tests (`bean::toggling_frozen_*`);
    // the native core isn't loadable on the JVM, so these fake it.

    @Test
    fun `unchecking Frozen defrosts through the core and keeps the freeze date`() {
        val frozen = bean().copy(roastedOn = "2026-04-24", frozenOn = "2026-05-07")
        val calls = mutableListOf<Pair<Bean, Boolean>>()
        val out = applyBeanEdits(frozen, blankDraft().copy(name = "Yirgacheffe", roasted = "2026-04-24", frozen = false)) { b, to ->
            calls += b to to
            b.copy(defrostedOn = "2026-05-22")
        }
        assertEquals(listOf(frozen to false), calls)
        assertEquals("2026-05-07", out.frozenOn)
        assertEquals("2026-05-22", out.defrostedOn)
    }

    @Test
    fun `checking Frozen on a thawed bag refreezes through the core`() {
        val thawed = bean().copy(frozenOn = "2026-05-07", defrostedOn = "2026-05-12")
        val out = applyBeanEdits(thawed, blankDraft().copy(frozen = true)) { b, to ->
            assertTrue(to)
            b.copy(frozenOn = "2026-05-17", defrostedOn = null)
        }
        assertEquals("2026-05-17", out.frozenOn)
        assertNull(out.defrostedOn)
    }

    @Test
    fun `an unchanged Frozen switch keeps the dates and never calls the core`() {
        val thawed = bean().copy(frozenOn = "2026-05-07", defrostedOn = "2026-05-12")
        val out = applyBeanEdits(thawed, blankDraft()) { _, _ -> error("core called") }
        assertEquals("2026-05-07", out.frozenOn)
        assertEquals("2026-05-12", out.defrostedOn)
        val frozen = bean().copy(frozenOn = "2026-05-07")
        assertEquals("2026-05-07", applyBeanEdits(frozen, blankDraft().copy(frozen = true)) { _, _ -> error("core called") }.frozenOn)
    }

    /** `set_bean_frozen_json(.., false, 2026-05-22)` output for the bag below, captured from the Rust core. */
    private val coreDefrostOut = """{"id":"bean:f","name":"Gesha","roasterId":null,"roastedOn":"2026-04-24","openedOn":null,"frozenOn":"2026-05-07","defrostedOn":"2026-05-22","roastLevel":null,"mix":null,"roastType":null,"decaf":false,"origin":{"country":null,"region":null,"farm":null,"farmer":null,"variety":null,"elevation":null,"processing":null,"harvestTime":null},"bagSize":0.0,"remaining":0.0,"qualityScore":"","tastingNotes":"","rating":0,"placeOfPurchase":null,"cost":null,"url":null,"notes":"","favourite":false,"archivedAt":null,"grinder":"","grinderSetting":"","tags":[],"linkedProfileId":null,"visualizerId":null,"canonicalCoffeeBagId":null,"canonicalRoasterId":null,"deletedAt":null,"beanconquerorId":null,"imageRef":null,"metadata":{},"createdAt":0,"updatedAt":0}"""

    @Test
    fun `setBeanFrozen sends the bag to the core and returns the core's bag`() {
        val bag = bean().copy(id = "bean:f", name = "Gesha", roastedOn = "2026-04-24", frozenOn = "2026-05-07")
        var sent: Triple<String, Boolean, Long>? = null
        val out = setBeanFrozen(bag, false, 1_779_451_200_000) { json, to, now ->
            sent = Triple(json, to, now)
            coreDefrostOut
        }
        val (json, to, now) = sent!!
        assertTrue(json.contains("\"frozenOn\":\"2026-05-07\""))
        assertEquals(false, to)
        assertEquals(1_779_451_200_000, now)
        assertEquals("2026-05-07", out.frozenOn)
        assertEquals("2026-05-22", out.defrostedOn)
    }
}
