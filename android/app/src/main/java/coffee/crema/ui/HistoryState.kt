package coffee.crema.ui

import coffee.crema.history.ShotRow
import coffee.crema.history.methodOf
import coffee.crema.history.recentNames

/**
 * What the UI state holds of the shot history (#310 A3b): no list of rows.
 * The rows live in the history's store ([coffee.crema.history.HistoryStore.rows]);
 * the screens read a page of them through a window (the index's, or the
 * store's rows while it can't answer, `HistoryLists.kt`) and a row by id
 * through [get]. What's here is what the screens show about the whole
 * history, worked out off the main thread whenever its rows change, and the
 * [version] those answers are for, so a screen that reads a row by id
 * recomposes when it changes.
 */
class HistoryState(
    /** How many rows the history holds. */
    val count: Int = 0,
    /** The store's [coffee.crema.history.HistoryStore.rowsVersion] these answers were read at. */
    val version: Long = 0,
    /** Pinned rows. */
    val pinned: Int = 0,
    /** Rows with no Visualizer id. */
    val noVisualizerId: Int = 0,
    /** Any row of a method other than espresso: History says "brews", not "shots". */
    val anyNonEspresso: Boolean = false,
    /** Barista names, most recent first (the detail's suggestions). */
    val baristaNames: List<String> = emptyList(),
    /** Drinker names, most recent first. */
    val drinkerNames: List<String> = emptyList(),
    /** The newest row's id. */
    val newestId: String? = null,
    private val byId: (String) -> ShotRow? = { null },
) {
    val size: Int get() = count

    fun isEmpty(): Boolean = count == 0

    fun isNotEmpty(): Boolean = count > 0

    /** The row [id] as the store holds it now, or null when it holds none. Read with [version] as a key. */
    operator fun get(id: String): ShotRow? = byId(id)

    override fun equals(other: Any?): Boolean = other is HistoryState && other.version == version && other.count == count &&
        other.pinned == pinned && other.noVisualizerId == noVisualizerId && other.anyNonEspresso == anyNonEspresso &&
        other.baristaNames == baristaNames && other.drinkerNames == drinkerNames && other.newestId == newestId

    override fun hashCode(): Int = java.util.Objects.hash(version, count, pinned, noVisualizerId, anyNonEspresso, newestId)

    override fun toString(): String = "HistoryState(count=$count, version=$version)"

    companion object {
        /**
         * The state for [rows], newest first, read at [version]; [byId] reads
         * a row by id from the store. Runs over every row: off the main thread.
         */
        fun of(rows: List<ShotRow>, version: Long, byId: (String) -> ShotRow?): HistoryState = HistoryState(
            count = rows.size,
            version = version,
            pinned = rows.count { it.pinned },
            noVisualizerId = rows.count { it.visualizerId == null },
            anyNonEspresso = rows.any { it.methodOf != null && it.methodOf != "espresso" },
            baristaNames = recentNames(rows, "barista"),
            drinkerNames = recentNames(rows, "drinker"),
            newestId = rows.firstOrNull()?.id,
            byId = byId,
        )
    }
}
