package coffee.crema.beans

import coffee.crema.core.Bean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android side of the bean picker (geota/crema#301).
 *
 * Which bags show and in what order is decided by the core
 * (`de1_domain::bean_picker`, covered by its Rust tests). The native library is
 * not loaded on the JVM, so these pin what the shell owns: that the toggle, the
 * current bag and the search reach the core, that the core's order lands on the
 * rows, and what a failing call leaves on screen.
 */
class BeanPickerTest {
    private fun bag(id: String, archived: Long? = null, deleted: Long? = null) = Bean(
        id = id,
        name = id,
        metadata = JsonNull,
        createdAt = 0L,
        updatedAt = 0L,
        archivedAt = archived,
        deletedAt = deleted,
    )

    private val bags = listOf(bag("a"), bag("b"), bag("old", archived = 5), bag("tomb", deleted = 9))

    private fun answering(vararg ids: String, hasArchived: Boolean = false): (String, String, String) -> String = { _, _, _ ->
        """{"ids":[${ids.joinToString(",") { "\"$it\"" }}],"hasArchived":$hasArchived}"""
    }

    private val broken: (String, String, String) -> String = { _, _, _ -> error("no native library") }

    @Test fun theSelectionsReachTheCore() {
        var sent = ""
        beanPickerRows(bags, emptyList(), "old", includeArchived = true, search = "kenya") { _, _, q ->
            sent = q
            """{"ids":[],"hasArchived":false}"""
        }
        val query = Json.parseToJsonElement(sent).jsonObject
        assertEquals("old", query.getValue("currentId").jsonPrimitive.content)
        assertTrue(query.getValue("includeArchived").jsonPrimitive.boolean)
        assertEquals("kenya", query.getValue("search").jsonPrimitive.content)
    }

    @Test fun theToggleIsOffUnlessAsked() {
        var sent = ""
        beanPickerRows(bags, emptyList(), null, includeArchived = false, search = "") { _, _, q ->
            sent = q
            """{"ids":[],"hasArchived":false}"""
        }
        assertFalse(Json.parseToJsonElement(sent).jsonObject.getValue("includeArchived").jsonPrimitive.boolean)
    }

    @Test fun rowsFollowTheCoreOrder() {
        val rows = beanPickerRows(bags, emptyList(), null, false, "", answering("b", "a"))
        assertEquals(listOf("b", "a"), rows.beans.map { it.id })
    }

    @Test fun anIdTheLibraryDoesNotHoldIsDropped() {
        val rows = beanPickerRows(bags, emptyList(), null, false, "", answering("elsewhere", "a"))
        assertEquals(listOf("a"), rows.beans.map { it.id })
    }

    @Test fun hasArchivedComesFromTheCore() {
        assertTrue(beanPickerRows(bags, emptyList(), null, false, "", answering("a", hasArchived = true)).hasArchived)
        assertFalse(beanPickerRows(bags, emptyList(), null, false, "", answering("a")).hasArchived)
    }

    @Test fun aFailingCallStillHidesArchivedAndDeletedBags() {
        val rows = beanPickerRows(bags, emptyList(), null, includeArchived = false, search = "", core = broken)
        assertEquals(listOf("a", "b"), rows.beans.map { it.id })
        assertTrue(rows.hasArchived)
    }

    @Test fun aFailingCallHonoursTheToggle() {
        val rows = beanPickerRows(bags, emptyList(), null, includeArchived = true, search = "", core = broken)
        assertEquals(listOf("a", "b", "old"), rows.beans.map { it.id })
    }

    @Test fun aFailingCallKeepsAnArchivedCurrentBag() {
        val rows = beanPickerRows(bags, emptyList(), "old", includeArchived = false, search = "", core = broken)
        assertEquals(listOf("a", "b", "old"), rows.beans.map { it.id })
        assertFalse(rows.hasArchived)
    }

    @Test fun rankingAFilteredListAsksForEveryBagOnIt() {
        var sent = ""
        val ranked = rankBeansForPicker(listOf(bag("a"), bag("b")), "b") { _, _, q ->
            sent = q
            """{"ids":["b","a"],"hasArchived":false}"""
        }
        assertEquals(listOf("b", "a"), ranked.map { it.id })
        val query = Json.parseToJsonElement(sent).jsonObject
        assertEquals("b", query.getValue("currentId").jsonPrimitive.content)
        assertTrue(query.getValue("includeArchived").jsonPrimitive.boolean)
    }

    @Test fun anArchivedRowSaysSo() {
        assertTrue(beanPickerMeta(bag("old", archived = 5)).startsWith("Archived"))
    }

    @Test fun aRowWithNothingToSayShowsADash() {
        assertEquals("—", beanPickerMeta(bag("plain")))
    }
}
