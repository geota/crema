package coffee.crema.ui

import coffee.crema.beans.newBean
import coffee.crema.beans.newRoaster
import coffee.crema.history.StoredShot
import coffee.crema.history.row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plain update can't change the rows, and an edit works on the current ones
 * (geota/crema#324). That an edit built from a list the state handed out
 * doesn't compile is the type's job: [BeanRows] is not a [BeanView]. The shot
 * history's rows aren't in the UI state at all from #310 A3b: the store's
 * memory holds them, and its edits work on the rows as they stand
 * (`HistorySyncReadsTest`).
 */
class RowEditsTest {
    private fun shot(id: String, rating: Int? = null) =
        StoredShot(id = id, completedAtMs = 1, durationMs = 30_000, rating = rating).row()

    private val bean = newBean("Bag", null, roastLevel = null, roastedOn = null, nowMs = 1)
    private val roaster = newRoaster("Onyx", 1)

    private fun store() = UiStore(
        MainUiState(beans = rowViewOf(listOf(bean)), roasters = rowViewOf(listOf(roaster))),
    )

    @Test
    fun `stale_beans_copy_has_no_effect`() {
        val ui = store()
        val s = ui.value
        ui.edit({ it.copy(beans = it.beans.mapped { row -> row.copy(name = "Edited") }) })

        ui.update { it.copy(beans = s.beans, status = "kept") }

        assertEquals("the edit stands", "Edited", ui.value.beans.single().name)
        assertEquals("the rest of the copy lands", "kept", ui.value.status)
    }

    @Test
    fun `a plain update can't change the beans or the roasters either`() {
        val ui = store()
        val s = ui.value
        ui.edit({ it.copy(beans = it.beans.with(emptyList()), roasters = it.roasters.with(emptyList())) })
        ui.update { it.copy(beans = s.beans, roasters = s.roasters) }
        assertTrue(ui.value.beans.isEmpty())
        assertTrue(ui.value.roasters.isEmpty())
    }

    @Test
    fun `an edit starts from the rows as they stand, and lands the rest in one write`() {
        val ui = store()
        val other = bean.copy(id = "b")
        ui.edit({ it.copy(beans = it.beans.with(it.beans + other)) })
        ui.edit(
            { it.copy(beans = it.beans.filtered { row -> row.id != bean.id }) },
            { it.copy(status = "both") },
        )
        assertEquals(listOf("b"), ui.value.beans.map { it.id })
        assertEquals("both", ui.value.status)
    }

    @Test
    fun `an update that leaves the rows alone is the transform's own result`() {
        val ui = store()
        val before = ui.value
        ui.update { it }
        assertTrue(ui.value === before)
    }

    @Test
    fun `rows read as a list and compare as one`() {
        val view = rowViewOf(listOf(shot("a"), shot("b")))
        assertEquals(listOf(shot("a"), shot("b")), view)
        assertEquals(2, view.size)
        assertEquals("b", view[1].id)
        assertFalse(view.isEmpty())
        assertEquals(view, rowViewOf(listOf(shot("a"), shot("b"))))
    }
}
