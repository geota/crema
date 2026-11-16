package coffee.crema.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.core.BrewHistoryStats
import coffee.crema.history.DayGroups
import coffee.crema.history.HistoryFacetView
import coffee.crema.history.HistoryFilters
import coffee.crema.history.IndexWindow
import coffee.crema.history.MemoryShotList
import coffee.crema.history.ShotList
import coffee.crema.history.filterAndSortShots
import coffee.crema.history.liveCoffeeLookup
import coffee.crema.history.liveTagLookup
import coffee.crema.history.memoryStats
import coffee.crema.history.shotCoffee
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/*
 * #310 A2: what the History screens read, from the stage-1 index while it can
 * answer and from the in-memory rows otherwise (`index_fallback`, SPEC §12),
 * with no visible difference between the two but speed. The tablet's
 * [coffee.crema.ui.screens.HistoryScreen] and the phone's
 * [coffee.crema.ui.phone.PhoneHistoryScreen] share it.
 */

/** A History screen's list and what goes with it. */
@Stable
class HistoryListState(
    val list: ShotList,
    /** The stat strip; null until it has answered. */
    val stats: BrewHistoryStats?,
    val facets: HistoryFacetView,
    /** The day groups' counts under the date sort (phone), newest group first; null until known or when not asked for. */
    val groups: List<Int>?,
    /** True on the index, false on the in-memory rows. */
    val fromIndex: Boolean,
)

/**
 * [filters] over the index ([MainViewModel.historyIndexReads]) or, while it
 * can't answer, over `ui.history`. A search waits [SEARCH_DEBOUNCE_MS] for
 * typing to pause. A new query keeps the last list on screen until its count
 * is in, so a filter change never flashes skeletons. [dayEdges] asks for the
 * phone's day groups.
 */
@Composable
fun rememberHistoryList(vm: MainViewModel, ui: MainUiState, filters: HistoryFilters, dayEdges: List<Long>?): HistoryListState {
    val reads by vm.historyIndexReads.collectAsStateWithLifecycle()
    val settled = rememberSettledFilters(filters)
    val liveCoffee = remember(ui.beans, ui.roasters) { liveCoffeeLookup(ui.beans, ui.roasters) }
    val liveTags = remember(ui.beans) { liveTagLookup(ui.beans) }
    val r = reads
    if (r != null) {
        val parent = rememberCoroutineScope()
        val query = settled.query()
        val window = remember(r, query) {
            val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[kotlinx.coroutines.Job]))
            IndexWindow(query, r, scope) to scope
        }
        DisposableEffect(window) { onDispose { window.second.cancel() } }
        var shown by remember(r) { mutableStateOf(window.first) }
        LaunchedEffect(window) {
            snapshotFlow { window.first.count != null || window.first.failed != null }.first { it }
            shown = window.first
        }
        LaunchedEffect(window, dayEdges) { window.first.dayEdges(dayEdges) }
        val w = if (shown.failed != null || window.first.count != null || window.first.failed != null) window.first else shown
        if (w.failed == null) {
            var lastStats by remember { mutableStateOf<BrewHistoryStats?>(null) }
            w.stats?.let { lastStats = it }
            val facets = w.facets?.let { f ->
                remember(f, settled.bean, liveCoffee) {
                    HistoryFacetView.fromIndex(f, settled.bean, liveCoffee) { w.rowAt(0)?.bean?.name }
                }
            }
            var lastFacets by remember { mutableStateOf(HistoryFacetView.EMPTY) }
            facets?.let { lastFacets = it }
            return HistoryListState(w, w.stats ?: lastStats, facets ?: lastFacets, w.groups.takeIf { dayEdges != null }, fromIndex = true)
        }
        // The index failed mid-read (closed for an erase, say): the in-memory rows, below.
    }
    val rows = rememberFilteredShots(ui.history, settled.search, settled, liveCoffee, liveTags) { q ->
        filterAndSortShots(
            ui.history, q, settled.range, settled.profile, settled.sort, settled.sortDesc, settled.nowMs, settled.bean, settled.method,
            coffeeOf = { shotCoffee(it, liveCoffee)?.key },
            pinnedOnly = settled.pinnedOnly,
            tagsAll = listOfNotNull(settled.tag),
            liveTags = liveTags,
        )
    }
    val list = remember(rows) { MemoryShotList(rows) }
    val stats by produceState<BrewHistoryStats?>(null, rows) { value = memoryStats(rows) }
    val facets by produceState(HistoryFacetView.EMPTY, ui.history, liveCoffee, liveTags, settled.bean) {
        value = withContext(Dispatchers.Default) { HistoryFacetView.fromRows(ui.history, liveCoffee, liveTags, settled.bean) }
    }
    val groups = remember(rows, dayEdges) { dayEdges?.let { DayGroups.counts(rows, it) } }
    return HistoryListState(list, stats, facets, groups, fromIndex = false)
}

/** [filters] with the search held until typing pauses [SEARCH_DEBOUNCE_MS]; every other filter at once. */
@Composable
private fun rememberSettledFilters(filters: HistoryFilters): HistoryFilters {
    var text by remember { mutableStateOf(filters.search) }
    val wanted = filters.search
    LaunchedEffect(wanted) {
        if (wanted != text) {
            delay(SEARCH_DEBOUNCE_MS)
            text = wanted
        }
    }
    return filters.copy(text = text)
}

/**
 * Tells [list] which rows [state] shows, as it scrolls: the window loads
 * pages around them. [toRow] maps a list item to a row position (headers
 * and the lead item map to the nearest row).
 */
@Composable
fun TrackVisibleRows(list: ShotList, state: LazyListState, toRow: (Int) -> Int = { it }) {
    val map by rememberUpdatedState(toRow)
    LaunchedEffect(list, state) {
        snapshotFlow {
            val items = state.layoutInfo.visibleItemsInfo
            if (items.isEmpty()) state.firstVisibleItemIndex to state.firstVisibleItemIndex + 16
            else items.first().index to items.last().index
        }
            .distinctUntilChanged()
            .collect { (first, last) -> list.visible(map(first).coerceAtLeast(0), map(last).coerceAtLeast(0)) }
    }
}

/**
 * Scrolls [state] to [id]'s row once, when [request] is set (a deep link, or
 * a detail closing): the position comes from the list ([ShotList.indexOf],
 * through `index_of` on the index). [toItem] maps the row to its list item.
 * [done] clears the request.
 */
@Composable
fun RevealRow(list: ShotList, state: LazyListState, request: String?, toItem: (Int) -> Int, done: () -> Unit) {
    val item by rememberUpdatedState(toItem)
    LaunchedEffect(list, request) {
        val id = request ?: return@LaunchedEffect
        snapshotFlow { list.count }.filter { it != null }.first()
        val at = list.indexOf(id)
        if (at != null) {
            val target = item(at)
            val visible = state.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == target } || visible.isEmpty()) state.scrollToItem(target)
        }
        done()
    }
}

/** A row not loaded yet: a placeholder of the row's height (SPEC §7). */
@Composable
fun HistoryRowSkeleton(height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
    )
}
