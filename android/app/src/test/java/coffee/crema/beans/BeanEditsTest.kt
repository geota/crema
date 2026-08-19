package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanOrigin
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
