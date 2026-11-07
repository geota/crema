package coffee.crema.ui

import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.history.StoredShot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * How the history and the bean library change (geota/crema#324). Rows edited
 * from a list read earlier undo whatever landed in between: a rating set while a
 * shot assembled, a bag debit during a bean sync. So a change is a delta on the
 * current rows, inside the update.
 *
 * Two rules hold that up. [UiStore] is the only write to `MainUiState`, and a
 * plain update keeps the current rows whatever its copy says, so
 * `updateUi { it.copy(history = s.history) }`, the bug in its simplest form, has
 * no effect. And rows come in two types. A [RowView] is what the state hands out,
 * read as a List. A [Rows] is what an edit passes in and takes back, and only
 * [UiStore.edit] holds one, so `editHistory { historyWith(x, s.history) }` doesn't
 * compile. Keeping `rows` past the lambda stays possible but deliberate.
 */

/** Rows as the state hands them out: a read. */
sealed interface RowView<T> : List<T>

/** The current rows, inside an edit. */
sealed interface Rows<T> : List<T>

typealias HistoryView = RowView<StoredShot>
typealias HistoryRows = Rows<StoredShot>
typealias BeanView = RowView<Bean>
typealias BeanRows = Rows<Bean>
typealias RoasterView = RowView<Roaster>
typealias RoasterRows = Rows<Roaster>

private class ViewOf<T>(val rows: List<T>) : AbstractList<T>(), RowView<T> {
    override val size: Int get() = rows.size
    override fun get(index: Int): T = rows[index]
}

private class EditOf<T>(val rows: List<T>) : AbstractList<T>(), Rows<T> {
    override val size: Int get() = rows.size
    override fun get(index: Int): T = rows[index]
}

/** Rows for a state that isn't the app's: the defaults, tests, fixtures. A write
 *  of one still has no effect outside [UiStore.edit]. */
fun <T> rowViewOf(rows: List<T> = emptyList()): RowView<T> = ViewOf(rows)

/** Rows for an edit outside [UiStore], in tests of what an edit does. */
fun <T> rowsForTest(rows: List<T>): Rows<T> = EditOf(rows)

/** These rows with [transform] applied to each. */
fun <T> Rows<T>.mapped(transform: (T) -> T): Rows<T> = EditOf(map(transform))

/** These rows without those [predicate] refuses. */
fun <T> Rows<T>.filtered(predicate: (T) -> Boolean): Rows<T> = EditOf(filter(predicate))

/** [next], worked out from these rows, as the result of an edit. */
fun <T> Rows<T>.with(next: List<T>): Rows<T> = EditOf(next)

/** The stale shape: a list read from the state before this edit began. */
@Deprecated("Build the result from the edit's rows, not from a list the state handed out.", level = DeprecationLevel.ERROR)
@Suppress("UNUSED_PARAMETER")
fun <T> Rows<T>.with(stale: RowView<T>): Rows<T> = error("unreachable")

/** Everything [UiStore.edit] can change, at once. */
data class LibraryRows(val history: HistoryRows, val beans: BeanRows, val roasters: RoasterRows)

/** Change rows in one atomic write: see [UiStore.edit]. */
typealias EditRows = (rows: (LibraryRows) -> LibraryRows, rest: (MainUiState) -> MainUiState) -> Unit

/** The one write to `MainUiState`. */
class UiStore(initial: MainUiState) {
    private val flow = MutableStateFlow(initial)

    val state: StateFlow<MainUiState> = flow.asStateFlow()
    val value: MainUiState get() = flow.value

    /** Apply [transform] to the current state. The rows stay as they are now. */
    fun update(transform: (MainUiState) -> MainUiState) {
        flow.update { current -> transform(current).keepingRowsOf(current) }
    }

    /**
     * Apply [rows] to the current rows, and [rest] to the rest of the state, in
     * one write. They run on the state as it stands, and again if another write
     * lands first, so neither should have an effect of its own.
     */
    fun edit(rows: (LibraryRows) -> LibraryRows, rest: (MainUiState) -> MainUiState = { it }) {
        flow.update { current ->
            val edited = rows(LibraryRows(current.history.forEdit(), current.beans.forEdit(), current.roasters.forEdit()))
            rest(current).copy(
                history = edited.history.forState(),
                beans = edited.beans.forState(),
                roasters = edited.roasters.forState(),
            )
        }
    }

    private fun MainUiState.keepingRowsOf(current: MainUiState): MainUiState =
        if (history === current.history && beans === current.beans && roasters === current.roasters) this
        else copy(history = current.history, beans = current.beans, roasters = current.roasters)

    private fun <T> RowView<T>.forEdit(): Rows<T> = EditOf((this as ViewOf<T>).rows)

    private fun <T> Rows<T>.forState(): RowView<T> = ViewOf((this as EditOf<T>).rows)
}
