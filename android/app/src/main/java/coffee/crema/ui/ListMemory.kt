package coffee.crema.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * ListMemory — where every list's scroll position and view state (search,
 * sort, filters, open item) lives, so leaving a list and coming back keeps
 * your place (issue #123).
 *
 * The lists used to own that state locally, which dropped it in every common
 * round trip: the phone swaps a detail screen in place of its list (Beans,
 * History), the editors are separate nav destinations (Beans, Profiles), a tab
 * or filter switch takes a list out of composition, and rotating across the
 * 840dp breakpoint swaps the phone and tablet hosts. One instance is hoisted to
 * MainActivity above both hosts (see [rememberListMemory]) and handed down via
 * [LocalListMemory], so none of those tear it down.
 *
 * **Scroll.** Each list asks for its state by a key naming the list *and* its
 * filter (`beans/bags|archived|…`), so each filter keeps its own place. The
 * live [LazyListState] / [LazyGridState] objects are kept here, not
 * re-created, which means Compose's own key tracking still applies when the
 * list comes back: if items were archived, deleted or re-sorted while the list
 * was away, it re-finds the first visible item by its key, and if that item is
 * gone it stays at the same index — landing next to where it was.
 *
 * **Anchors.** Alongside the live states each key records an [Anchor] — the
 * first visible item's key and offset. It seeds a list that has no live state
 * of its own: the other shell's list after a phone↔tablet swap (a LazyColumn
 * there, a grid here), or any list after process death (the anchors and view
 * values are saveable, the live states are not). Seeding resolves the anchor's
 * item key to its current index, so it lands on the same item, not the same
 * pixel offset.
 *
 * **View values.** [string] / [optString] / [bool] give screens a
 * `MutableState` backed by one shared, saveable map, keyed without a shell
 * prefix so the phone and tablet screens share them through a host swap.
 */
@Stable
class ListMemory internal constructor(
    anchors: Map<String, Anchor> = emptyMap(),
    values: Map<String, String> = emptyMap(),
    scrolls: Map<String, Int> = emptyMap(),
) {
    /**
     * Where a list was: the first visible item's [itemKey] (null when that
     * item has no string key, e.g. a header) at [index] / [offset]. [writer]
     * names the live state that recorded it; a live state that didn't is stale.
     */
    data class Anchor(val itemKey: String?, val index: Int, val offset: Int, val writer: String = "")

    private val anchors = HashMap(anchors)
    private val savedScrolls = HashMap(scrolls)
    private val lists = HashMap<String, LazyListState>()
    private val grids = HashMap<String, LazyGridState>()
    private val scrollStates = HashMap<String, ScrollState>()
    private val values = mutableStateMapOf<String, String>().apply { putAll(values) }

    /** The last recorded anchor for [key], if any. */
    fun anchor(key: String): Anchor? = anchors[key]

    /** Record where the list [key] is now. */
    fun record(key: String, anchor: Anchor) {
        anchors[key] = anchor
    }

    /**
     * Index + offset to seed a new state for [key]: the anchor's item at its
     * current index ([indexOfKey]), else the anchor's raw index (the item is
     * gone — land next to where it was), else the top.
     */
    fun resolve(key: String, indexOfKey: (String) -> Int?): Pair<Int, Int> {
        val a = anchors[key] ?: return 0 to 0
        val found = a.itemKey?.let(indexOfKey)
        return when {
            found != null -> found to a.offset.coerceAtLeast(0)
            a.itemKey == null -> a.index.coerceAtLeast(0) to a.offset.coerceAtLeast(0)
            else -> a.index.coerceAtLeast(0) to 0
        }
    }

    /** The live list state for [key] in [shell] (seeded from the anchor when new or stale). */
    fun listState(shell: String, key: String, indexOfKey: (String) -> Int?): LazyListState {
        val live = liveKey("list", shell, key)
        lists[live]?.takeIf { isCurrent(key, live) }?.let { return it }
        val (i, o) = resolve(key, indexOfKey)
        return LazyListState(i, o).also { lists[live] = it }
    }

    /** The live grid state for [key] in [shell] (seeded from the anchor when new or stale). */
    fun gridState(shell: String, key: String, indexOfKey: (String) -> Int?): LazyGridState {
        val live = liveKey("grid", shell, key)
        grids[live]?.takeIf { isCurrent(key, live) }?.let { return it }
        val (i, o) = resolve(key, indexOfKey)
        return LazyGridState(i, o).also { grids[live] = it }
    }

    /** A plain (non-lazy) scroll position for [key] in [shell] — Settings pages. */
    fun scrollState(shell: String, key: String): ScrollState {
        val live = liveKey("scroll", shell, key)
        return scrollStates.getOrPut(live) { ScrollState(savedScrolls[live] ?: 0) }
    }

    private fun isCurrent(key: String, live: String): Boolean {
        val a = anchors[key] ?: return true
        return a.writer == live
    }

    // ── View values ─────────────────────────────────────────────────────

    /** A string view value (search text, sort key, filter id). */
    fun string(key: String, default: String): MutableState<String> = Entry(
        get = { values[key] ?: default },
        set = { v -> if (v == default) values.remove(key) else values[key] = v },
    )

    /** An optional string view value (null = unset, e.g. "no bean filter"). */
    fun optString(key: String): MutableState<String?> = Entry(
        get = { values[key] },
        set = { v -> if (v == null) values.remove(key) else values[key] = v },
    )

    /** A boolean view value (sort direction, a revealed section). */
    fun bool(key: String, default: Boolean): MutableState<Boolean> = Entry(
        get = { values[key]?.toBooleanStrictOrNull() ?: default },
        set = { v -> if (v == default) values.remove(key) else values[key] = v.toString() },
    )

    private class Entry<T>(private val get: () -> T, private val set: (T) -> Unit) : MutableState<T> {
        override var value: T
            get() = get()
            set(v) = set(v)
        override fun component1(): T = value
        override fun component2(): (T) -> Unit = { value = it }
    }

    companion object {
        private fun liveKey(type: String, shell: String, key: String) = "$type:$shell:$key"

        private const val A = "a"
        private const val V = "v"
        private const val S = "s"
        private const val NO_KEY = "\u0000"

        /**
         * Saves the anchors, view values and plain scroll offsets (process
         * death). Live lazy states are not saved; they re-seed from the anchors,
         * and an anchor's writer is dropped so nothing restored looks current.
         */
        val Saver: Saver<ListMemory, Any> = listSaver(
            save = { m ->
                buildList {
                    m.anchors.forEach { (k, a) -> addAll(listOf(A, k, a.itemKey ?: NO_KEY, a.index.toString(), a.offset.toString())) }
                    m.values.forEach { (k, v) -> addAll(listOf(V, k, v)) }
                    val scrolls = m.savedScrolls + m.scrollStates.mapValues { it.value.value }
                    scrolls.forEach { (k, v) -> addAll(listOf(S, k, v.toString())) }
                }
            },
            restore = { flat ->
                val anchors = HashMap<String, Anchor>()
                val values = HashMap<String, String>()
                val scrolls = HashMap<String, Int>()
                var i = 0
                while (i < flat.size) {
                    when (flat[i]) {
                        A -> {
                            val itemKey = flat[i + 2].takeIf { it != NO_KEY }
                            anchors[flat[i + 1]] = Anchor(itemKey, flat[i + 3].toIntOrNull() ?: 0, flat[i + 4].toIntOrNull() ?: 0)
                            i += 5
                        }
                        V -> { values[flat[i + 1]] = flat[i + 2]; i += 3 }
                        S -> { scrolls[flat[i + 1]] = flat[i + 2].toIntOrNull() ?: 0; i += 3 }
                        else -> i = flat.size // unknown layout — keep what we have
                    }
                }
                ListMemory(anchors, values, scrolls)
            },
        )
    }
}

/** The app's [ListMemory]; MainActivity provides the hoisted one. */
val LocalListMemory = staticCompositionLocalOf { ListMemory() }

/** Which host is composing ("phone" / "tablet") — scopes live scroll states per shell. */
val LocalListShell = staticCompositionLocalOf { "phone" }

/** The hoisted, saveable [ListMemory] (MainActivity, above both nav hosts). */
@Composable
fun rememberListMemory(): ListMemory = rememberSaveable(saver = ListMemory.Saver) { ListMemory() }

/**
 * A [LazyListState] for the list [key] (screen + filter) that survives the
 * list leaving composition — a detail swapped in, an editor pushed, a tab or
 * filter switch, a phone↔tablet host swap. [itemCount] is the number of data
 * rows (a restore waits for them to load); [indexOfKey] maps an item key to its
 * index in the LazyColumn, headers included.
 */
@Composable
fun rememberListMemoryState(key: String, itemCount: Int, indexOfKey: (String) -> Int?): LazyListState {
    val memory = LocalListMemory.current
    val shell = LocalListShell.current
    val state = remember(memory, shell, key) { memory.listState(shell, key, indexOfKey) }
    TrackAnchor(
        memory, key, "list:$shell:$key", state, itemCount, indexOfKey,
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        firstKey = { state.layoutInfo.visibleItemsInfo.firstOrNull()?.key },
        total = { state.layoutInfo.totalItemsCount },
        scrollTo = { i, o -> state.scrollToItem(i, o) },
    )
    return state
}

/** The [LazyGridState] twin of [rememberListMemoryState] (tablet grids). */
@Composable
fun rememberGridMemoryState(key: String, itemCount: Int, indexOfKey: (String) -> Int?): LazyGridState {
    val memory = LocalListMemory.current
    val shell = LocalListShell.current
    val state = remember(memory, shell, key) { memory.gridState(shell, key, indexOfKey) }
    TrackAnchor(
        memory, key, "grid:$shell:$key", state, itemCount, indexOfKey,
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        firstKey = { state.layoutInfo.visibleItemsInfo.firstOrNull()?.key },
        total = { state.layoutInfo.totalItemsCount },
        scrollTo = { i, o -> state.scrollToItem(i, o) },
    )
    return state
}

/** A [ScrollState] for a plain scrolling page [key] that survives it leaving composition. */
@Composable
fun rememberScrollMemoryState(key: String): ScrollState {
    val memory = LocalListMemory.current
    val shell = LocalListShell.current
    return remember(memory, shell, key) { memory.scrollState(shell, key) }
}

/** [ListMemory.string] as a remembered state. */
@Composable
fun rememberListString(key: String, default: String): MutableState<String> {
    val memory = LocalListMemory.current
    return remember(memory, key) { memory.string(key, default) }
}

/** [ListMemory.optString] as a remembered state. */
@Composable
fun rememberListOptString(key: String): MutableState<String?> {
    val memory = LocalListMemory.current
    return remember(memory, key) { memory.optString(key) }
}

/** [ListMemory.bool] as a remembered state. */
@Composable
fun rememberListBool(key: String, default: Boolean): MutableState<Boolean> {
    val memory = LocalListMemory.current
    return remember(memory, key) { memory.bool(key, default) }
}

/**
 * Keeps [key]'s anchor current as the list scrolls, and finishes a restore that
 * had to wait for data (a cold start after process death seeds the state before
 * the library has loaded; the anchor is applied once rows exist).
 */
@Composable
private fun TrackAnchor(
    memory: ListMemory,
    key: String,
    writer: String,
    state: Any,
    itemCount: Int,
    indexOfKey: (String) -> Int?,
    position: () -> Pair<Int, Int>,
    firstKey: () -> Any?,
    total: () -> Int,
    scrollTo: suspend (Int, Int) -> Unit,
) {
    val currentIndexOfKey by rememberUpdatedState(indexOfKey)
    // A restore is pending while the state was seeded from an anchor before
    // any row existed — recording now would overwrite the anchor with "top".
    var pending by remember(state) {
        val a = memory.anchor(key)
        mutableStateOf(itemCount == 0 && a != null && a.writer != writer)
    }
    val hasRows = itemCount > 0
    LaunchedEffect(state, hasRows) {
        if (pending && hasRows) {
            val (i, o) = memory.resolve(key, currentIndexOfKey)
            scrollTo(i, o)
            pending = false
        }
    }
    LaunchedEffect(state, key) {
        snapshotFlow { position() to total() }.collect { (pos, count) ->
            if (!pending && count > 0) {
                memory.record(key, ListMemory.Anchor(firstKey() as? String, pos.first, pos.second, writer))
            }
        }
    }
}

// ── List keys ───────────────────────────────────────────────────────────
// One place for the keys, shared by the phone and tablet screens so a host
// swap finds the other shell's anchor. A key names the list and everything
// that changes its rows or their order: each filter / sort keeps its own place.

/**
 * Beans → Bags: per facet combination (#124: status, include-archived, roast,
 * tags — via [BeanFacets], which also carries the roaster shelf) and sort.
 */
fun beansListKey(facets: coffee.crema.beans.BeanFacets, sort: String, desc: Boolean): String =
    "beans/bags|${facets.status}|${facets.includeArchived}|${facets.roast.orEmpty()}|" +
        "${facets.tags.sorted().joinToString(",")}|${facets.roasterId.orEmpty()}|$sort|$desc"

/** Beans → Roasters directory. */
fun roastersListKey(showDuplicates: Boolean): String = "beans/roasters|$showDuplicates"

/** History: per range, profile, bean and method filter and sort. */
fun historyListKey(
    range: String,
    profile: String?,
    bean: String?,
    method: String?,
    sort: String,
    desc: Boolean,
): String = "history|$range|${profile.orEmpty()}|${bean.orEmpty()}|${method.orEmpty()}|$sort|$desc"

/** Profiles (with the guided-brew recipes below them): per status + roast facet (#124) and sort. */
fun profilesListKey(status: String, roast: String?, sort: String, desc: Boolean, showHiddenRecipes: Boolean): String =
    "profiles|$status|${roast.orEmpty()}|$sort|$desc|$showHiddenRecipes"
