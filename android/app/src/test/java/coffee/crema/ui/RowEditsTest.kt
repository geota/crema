package coffee.crema.ui

import coffee.crema.beans.newBean
import coffee.crema.beans.newRoaster
import coffee.crema.history.StoredShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plain update can't change the rows, and an edit works on the current ones
 * (geota/crema#324). That `editHistory { historyWith(x, s.history) }` doesn't
 * compile is the type's job: [HistoryRows] is not a [HistoryView].
 */
class RowEditsTest {
    private fun shot(id: String, rating: Int? = null) =
        StoredShot(id = id, completedAtMs = 1, durationMs = 30_000, rating = rating)

    private val bean = newBean("Bag", null, roastLevel = null, roastedOn = null, nowMs = 1)
    private val roaster = newRoaster("Onyx", 1)

    private fun store() = UiStore(
        MainUiState(history = rowViewOf(listOf(shot("a"))), beans = rowViewOf(listOf(bean)), roasters = rowViewOf(listOf(roaster))),
    )

    @Test
    fun `stale_history_copy_has_no_effect`() {
        val ui = store()
        val s = ui.value
        ui.edit({ it.copy(history = it.history.mapped { row -> row.copy(rating = 5) }) })

        ui.update { it.copy(history = s.history, status = "kept") }

        assertEquals("the edit stands", 5, ui.value.history.single().rating)
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
        ui.edit({ it.copy(history = it.history.with(it.history + shot("b"))) })
        ui.edit(
            { it.copy(history = it.history.filtered { row -> row.id != "a" }) },
            { it.copy(status = "both") },
        )
        assertEquals(listOf("b"), ui.value.history.map { it.id })
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
