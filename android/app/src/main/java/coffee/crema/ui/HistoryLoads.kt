package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import coffee.crema.history.ShotRow
import coffee.crema.history.StoredShot
import coffee.crema.history.joined
import coffee.crema.history.withHeavy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * What the History screens compute from the rows, and what they load per shot
 * (#310 P4a, SPEC §4 and §5). The filter, the sort and the stats run on
 * [Dispatchers.Default], the last result on screen meanwhile; a search waits
 * [SEARCH_DEBOUNCE_MS] for typing to pause. A shot's curve, recipe and brew
 * series load when it's opened; a result for a shot no longer open is dropped.
 */

/** A search waits this long after the last keystroke (SPEC §5: both shells). */
const val SEARCH_DEBOUNCE_MS = 150L

/**
 * [filter] over the rows, off the main thread, re-run when [history] (the
 * rows' version, `ui.history.version`), [query] or any of [keys] changes. The first frame computes it in place, so the list
 * never flashes empty; after that the last result stays until the next is
 * ready. A change of [query] waits [SEARCH_DEBOUNCE_MS] first.
 */
@Composable
fun <T> rememberFilteredShots(
    history: Any?,
    query: String,
    vararg keys: Any?,
    filter: (query: String) -> List<T>,
): List<T> {
    val first = remember { FirstRun(query, filter(query)) }
    val result by produceState(first.result, history, query, *keys) {
        if (query != first.lastQuery) delay(SEARCH_DEBOUNCE_MS)
        first.lastQuery = query
        value = withContext(Dispatchers.Default) { filter(query) }
    }
    return result
}

private class FirstRun<T>(var lastQuery: String, val result: List<T>)

/**
 * [compute] over [key], off the main thread. The first frame computes it in
 * place ([initial]); after that the last result stays on screen until the next
 * is ready (the stats strip).
 */
@Composable
fun <T> rememberOffMain(key: Any?, initial: () -> T, compute: () -> T): T {
    val start = remember { initial() }
    val result by produceState(start, key) {
        value = withContext(Dispatchers.Default) { compute() }
    }
    return result
}

/**
 * [compute] over [key], off the main thread, null until it's ready. A new
 * [key] clears the last result first, so one shot's analysis never shows under
 * another's.
 */
@Composable
fun <T> rememberOffMain(key: Any?, compute: () -> T?): T? {
    val result by produceState<T?>(null, key) {
        value = null
        value = withContext(Dispatchers.Default) { compute() }
    }
    return result
}

/** A shot being read whole for the screen. */
sealed interface ShotLoad {
    data object Loading : ShotLoad

    /** [shot] under the row as it stands now. */
    data class Ready(val shot: StoredShot) : ShotLoad

    /** It couldn't be read just now; the screen says so rather than "no telemetry". */
    data object Unreadable : ShotLoad
}

/**
 * [row]'s shot whole, read with [load] (`loadShot`). A new row id shows
 * [ShotLoad.Loading]; the same row with a new summary (a backfilled curve)
 * loads again in place. The row's own fields win over the loaded copy's, so an
 * edit since shows at once.
 */
@Composable
fun rememberLoadedShot(row: ShotRow, load: suspend (String) -> StoredShot?): ShotLoad {
    val loaded by produceState<Pair<String, StoredShot?>?>(null, row.id, row.summary) {
        if (value?.first != row.id) value = null
        value = row.id to load(row.id)
    }
    return shotLoadOf(row, loaded)
}

/**
 * What the screen shows for [row] given the last load, [loaded] (the id it was
 * for, and its shot): a result for another shot, one a quick selection left
 * behind, is dropped, and the row shows as loading (SPEC §4).
 */
internal fun shotLoadOf(row: ShotRow, loaded: Pair<String, StoredShot?>?): ShotLoad = when {
    loaded == null || loaded.first != row.id -> ShotLoad.Loading
    loaded.second == null -> ShotLoad.Unreadable
    else -> ShotLoad.Ready(row.joined(loaded.second!!))
}

/**
 * Every one of [rows] whole (a compare: SPEC §4, load all selected, then
 * draw), or null until they all are. One that can't be read comes without its
 * curve.
 */
@Composable
fun rememberLoadedShots(rows: List<ShotRow>, load: suspend (String) -> StoredShot?): List<StoredShot>? {
    val loaded by produceState<List<StoredShot>?>(null, rows) {
        value = null
        value = loadWhole(rows, load)
    }
    return loaded
}

/** Each of [rows] whole, in order, under the row's own fields; one that can't be read comes without its curve. */
suspend fun loadWhole(rows: List<ShotRow>, load: suspend (String) -> StoredShot?): List<StoredShot> =
    rows.map { row -> load(row.id)?.let(row::joined) ?: row.withHeavy(emptyList(), null, null) }
