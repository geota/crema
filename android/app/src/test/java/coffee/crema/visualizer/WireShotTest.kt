package coffee.crema.visualizer

import coffee.crema.core.ShotBean
import coffee.crema.history.StoredShot
import coffee.crema.history.beanLabel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the shot → Visualizer wire assembler. No device / FFI: the
 * assembler is plain Kotlin + kotlinx.serialization. Covers issue 06 — the bean
 * snapshot (roaster / roast date / roast level) is the single source for both the
 * wire and the "Roaster · Name" display ([beanLabel]).
 */
class WireShotTest {
    // A runtime UByte: a literal `7.toUByte()` trips a Kotlin const-evaluation
    // ICE (the "UByte const-eval gotcha"); routing through a fn keeps it non-const.
    private fun ub(n: Int): UByte = n.toUByte()

    private fun shotWithBean(bean: ShotBean?) =
        StoredShot(id = "shot:1", completedAtMs = 1_790_000_000_000, durationMs = 28_000, bean = bean)

    @Test
    fun `wire emits the full bean snapshot`() {
        val shot = shotWithBean(
            ShotBean(
                beanId = "bean:b3",
                name = "Monarch",
                roasterName = "Onyx Coffee Lab",
                roastedOn = "2026-05-20",
                roastLevel = ub(7),
                tags = listOf("daily"),
            ),
        )
        val bean = wireShotJson(shot)["bean"]!!.jsonObject
        assertEquals("Monarch", bean["name"]!!.jsonPrimitive.content)
        assertEquals("Onyx Coffee Lab", bean["roasterName"]!!.jsonPrimitive.content)
        // The two fields Android used to emit as null (issue 06).
        assertEquals("2026-05-20", bean["roastedOn"]!!.jsonPrimitive.content)
        assertEquals(7, bean["roastLevel"]!!.jsonPrimitive.int)
        assertEquals("bean:b3", bean["beanId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `metadata beans is the flat label derived from the snapshot`() {
        val shot = shotWithBean(ShotBean(name = "Monarch", roasterName = "Onyx Coffee Lab"))
        val meta = wireShotJson(shot)["metadata"]!!.jsonObject
        assertEquals("Onyx Coffee Lab · Monarch", meta["beans"]!!.jsonPrimitive.content)
    }

    @Test
    fun `beanLabel derives Roaster dot Name from the canonical snapshot`() {
        assertEquals(
            "Onyx Coffee Lab · Monarch",
            shotWithBean(ShotBean(name = "Monarch", roasterName = "Onyx Coffee Lab")).beanLabel,
        )
        // Falls back to whichever part is present.
        assertEquals("Monarch", shotWithBean(ShotBean(name = "Monarch", roasterName = null)).beanLabel)
        assertNull(shotWithBean(null).beanLabel)
    }

    @Test
    fun `wire bean and metadata beans are null for a beanless shot`() {
        val wire = wireShotJson(shotWithBean(null))
        assertTrue(wire["bean"] is JsonNull)
        assertTrue(wire["metadata"]!!.jsonObject["beans"] is JsonNull)
    }

    @Test
    fun `shotFinalWeight falls back to the journal yield when there are no samples`() {
        val shot = StoredShot(id = "shot:4", completedAtMs = 1L, durationMs = 1L, yieldG = 36f)
        assertEquals(36f, shotFinalWeight(shot))
    }

    @Test
    fun `wire emits profileName and grinderModel when set (elvis-overwrite regression)`() {
        // These used `x?.let { put } ?: put(JsonNull)`, which always overwrote the
        // set value with null (put returns the previous value). Lock the fix.
        val shot = StoredShot(
            id = "shot:5",
            completedAtMs = 1L,
            durationMs = 1L,
            profileName = "Londinium Lever",
        )
        val wire = wireShotJson(shot, grinderModel = "Niche Zero")
        assertEquals("Londinium Lever", wire["profileName"]!!.jsonPrimitive.content)
        assertEquals("Niche Zero", wire["grinderModel"]!!.jsonPrimitive.content)
    }
}
