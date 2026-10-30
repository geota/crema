package coffee.crema.beans

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bean "Duplicate" action: the shell hands the bag to the core's
 * `duplicate_bean` and stores what comes back. The copy RULE (which links are
 * dropped) is pinned by the core tests (`bean::duplicate_bean_*`,
 * `bean_sync::duplicate_of_a_linked_bean_*`); the native core isn't loadable on
 * the JVM, so the core call is a fake returning the core's real output for a
 * linked bag, and these pin the wiring.
 */
class BeanDuplicateTest {
    /** `duplicate_bean_json` output for "Gesha" (Visualizer + Beanconqueror
     *  linked, pinned) → "bean:copy" at 1000, captured from the Rust core. */
    private val coreOut = """{"id":"bean:copy","name":"Gesha (copy)","roasterId":null,"roastedOn":null,"openedOn":null,"frozenOn":null,"defrostedOn":null,"roastLevel":null,"mix":null,"roastType":null,"decaf":false,"origin":{"country":null,"region":null,"farm":null,"farmer":null,"variety":null,"elevation":null,"processing":null,"harvestTime":null},"bagSize":0.0,"remaining":0.0,"qualityScore":"","tastingNotes":"","rating":0,"placeOfPurchase":null,"cost":null,"url":null,"notes":"","favourite":false,"archivedAt":null,"grinder":"","grinderSetting":"","tags":[],"linkedProfileId":null,"visualizerId":null,"canonicalCoffeeBagId":null,"canonicalRoasterId":null,"deletedAt":null,"beanconquerorId":null,"imageRef":null,"metadata":{},"createdAt":1000,"updatedAt":1000}"""

    private val linked = newBean("Gesha", null, null, null, 1).copy(
        id = "bean:orig",
        visualizerId = "vb-orig",
        beanconquerorId = "bc-orig",
        favourite = true,
    )

    @Test
    fun `duplicate sends the bag to the core and returns the core's copy`() {
        var sent: Triple<String, String, Long>? = null
        val copy = duplicateBean(linked, nowMs = 1000, newId = "bean:copy") { json, id, now ->
            sent = Triple(json, id, now)
            coreOut
        }
        val (json, id, now) = sent!!
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("bean:orig", obj.getValue("id").jsonPrimitive.content)
        assertEquals("vb-orig", obj.getValue("visualizerId").jsonPrimitive.content)
        assertEquals("bean:copy", id)
        assertEquals(1000L, now)

        assertEquals("bean:copy", copy.id)
        assertEquals("Gesha (copy)", copy.name)
        assertNull(copy.visualizerId)
        assertNull(copy.beanconquerorId)
        assertEquals(false, copy.favourite)
        assertEquals(1000L, copy.createdAt)
    }

    @Test
    fun `duplicate mints a fresh bean id by default`() {
        var sentId = ""
        duplicateBean(linked, nowMs = 1000) { _, id, _ -> sentId = id; coreOut }
        assertTrue(sentId.startsWith("bean:"))
        assertFalse(sentId == linked.id)
    }
}
