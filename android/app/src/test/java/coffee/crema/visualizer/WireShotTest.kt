package coffee.crema.visualizer

import coffee.crema.core.ShotBean
import coffee.crema.history.StoredShot
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the shot → Visualizer wire assembler. No device / FFI: the
 * assembler is plain Kotlin + kotlinx.serialization. Covers issue 06 — the bean
 * snapshot (roaster / roast date / roast level) now reaches the wire instead of
 * the old `null`s.
 */
class WireShotTest {
    // A runtime UByte: a literal `7.toUByte()` trips a Kotlin const-evaluation
    // ICE (the "UByte const-eval gotcha"); routing through a fn keeps it non-const.
    private fun ub(n: Int): UByte = n.toUByte()

    @Test
    fun `wire emits the full bean snapshot when present`() {
        val shot = StoredShot(
            id = "shot:1",
            completedAtMs = 1_790_000_000_000,
            durationMs = 28_000,
            beanName = "Onyx Coffee Lab · Monarch",
            bean = ShotBean(
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
    fun `wire falls back to the flat label for legacy shots without a snapshot`() {
        val shot = StoredShot(
            id = "shot:2",
            completedAtMs = 1L,
            durationMs = 1L,
            beanName = "Sweet Bloom · Geometry",
            bean = null,
        )
        val bean = wireShotJson(shot)["bean"]!!.jsonObject
        assertEquals("Geometry", bean["name"]!!.jsonPrimitive.content)
        assertEquals("Sweet Bloom", bean["roasterName"]!!.jsonPrimitive.content)
        // Still unknown for legacy shots — but no longer a regression vs. before.
        assertTrue(bean["roastLevel"] is JsonNull)
        assertTrue(bean["roastedOn"] is JsonNull)
    }

    @Test
    fun `wire bean is null for a beanless shot`() {
        val shot = StoredShot(id = "shot:3", completedAtMs = 1L, durationMs = 1L)
        assertTrue(wireShotJson(shot)["bean"] is JsonNull)
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
