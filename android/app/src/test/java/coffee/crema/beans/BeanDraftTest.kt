package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanOrigin
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The editor's Save lands what the user changed, on the bag as it stands now (#324). */
class BeanDraftTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val opened: Bean = newBean("Geometry", null, roastLevel = null, roastedOn = null, nowMs = 1)
        .copy(remaining = 200f, bagSize = 250f, notes = "floral", origin = BeanOrigin(country = "Kenya", region = "Nyeri"))

    @Test
    fun `editor_save_keeps_debit`() {
        val current = opened.copy(remaining = 182f)
        val saved = applyBeanDraft(current, opened.copy(name = "Geometry II"), opened, json)
        assertEquals("Geometry II", saved.name)
        assertEquals("the debit stands", 182f, saved.remaining)
    }

    @Test
    fun `a field the user changed wins`() {
        val current = opened.copy(remaining = 182f)
        assertEquals(250f, applyBeanDraft(current, opened.copy(remaining = 250f), opened, json).remaining)
    }

    @Test
    fun `clearing a field clears it`() {
        assertNull(applyBeanDraft(opened, opened.copy(notes = null), opened, json).notes)
    }

    @Test
    fun `a change elsewhere stands when the user left the field alone`() {
        val current = opened.copy(notes = "from Visualizer", visualizerId = "vb-1")
        val saved = applyBeanDraft(current, opened.copy(name = "New"), opened, json)
        assertEquals("from Visualizer", saved.notes)
        assertEquals("vb-1", saved.visualizerId)
    }

    @Test
    fun `the origin merges field by field`() {
        val current = opened.copy(origin = BeanOrigin(country = "Kenya", region = "Kirinyaga"))
        val draft = opened.copy(origin = BeanOrigin(country = "Ethiopia", region = "Nyeri"))
        val saved = applyBeanDraft(current, draft, opened, json)
        assertEquals(BeanOrigin(country = "Ethiopia", region = "Kirinyaga"), saved.origin)
    }

    @Test
    fun `no change at all is the bag as it stands`() {
        val current = opened.copy(remaining = 1f)
        assertEquals(current, applyBeanDraft(current, opened, opened, json))
    }
}
