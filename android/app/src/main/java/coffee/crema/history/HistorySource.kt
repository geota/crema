package coffee.crema.history

import coffee.crema.restore.NextShots

/*
 * #310 R1a (SQLite SPEC §9): the store a backup reads its shots from, and a
 * restore's step 7 writes them to. There are two. [ShotsDbSource] is P3a's
 * `shots.db`, the one in use: a backup walks the rows captured with its
 * snapshot. [SqliteHistorySource] is the SQLite store, which A5 switches to:
 * a backup walks the store's export walk, which holds deletes (S6), and a
 * restore removes only rows that existed before it began (M3).
 *
 * The exporter ([coffee.crema.ui.streamBackupZip]) and step 7
 * ([coffee.crema.restore.restoreShots]) read and write only through these,
 * so a backup of the same history is the same bytes from either store.
 */

/** One row of a walk: its id, and the row, or null when it won't decode (the backup leaves it out and says so). */
internal class WalkRow(val id: String, val row: ShotRow?)

/**
 * A walk over every shot a backup takes, fixed when it opens: the same rows
 * in the same order (newest first, ties by id descending) each time it's
 * read, each row as it is when read. A row deleted meanwhile is still read
 * (the SQLite store holds it until [close]). Blocking: off the main thread.
 */
internal interface ShotWalk : java.io.Closeable {
    /** The rows the walk holds. */
    val count: Int

    /** The next rows, up to [n]; empty at the end. */
    fun next(n: Int): List<WalkRow>

    /** Back to the first row: the same rows again, for a second read. */
    fun rewind()

    /** [row] whole: its curve and heavy parts. Throws [HistoryReadException] when a read failed just now. */
    fun full(row: ShotRow): StoredShot

    /** The walk is over. On the SQLite store this releases the deletes it held, details first (S6). */
    override fun close()
}

/** Where a backup's shots come from and a restore's go (#310 R1a). */
internal interface HistorySource {
    /** A walk over every shot a backup takes now. Blocking: off the main thread. */
    fun openWalk(): ShotWalk

    /**
     * Auto-backup's "changed?" shortcut (SPEC §9): a mark every history write
     * moves, or null when this store keeps no write counter. Blocking.
     */
    fun writeMark(): String?

    /**
     * A restore run begins, before its plan is made again (step 0): a row
     * written after this is never in its wipe (M3). Step 7 writes through
     * what this returns.
     */
    suspend fun beginRestore(): ShotsRestore
}

/**
 * A restore's step 7 on one store (P3 spec §7, SQLite SPEC §9): series
 * first, then the rows merged in batches with incoming rows winning, then
 * one explicit removal of rows the backup doesn't hold. A kill anywhere
 * leaves the union, and a re-run finishes it.
 */
internal interface ShotsRestore {
    /** Every row the store lists now. */
    suspend fun rows(): List<ShotRow>

    /** Whether [row] has its curve. */
    fun hasCurve(row: ShotRow): Boolean

    /** The row of a shot the plan writes whole. */
    fun rowFor(shot: StoredShot): ShotRow

    /** These shots' series, before any row names them. Returns how many failed. */
    suspend fun writeSeries(shots: List<StoredShot>): Int

    /** [next] over the rows as they are now, and into whatever shows them (P3a's view). */
    suspend fun settle(next: (List<ShotRow>) -> NextShots): NextShots

    /** [shot]'s heavy parts, for the merge to write with its row. */
    fun stage(shot: StoredShot)

    /** One merge batch: every row given, incoming over local, none deleted. False when it didn't land. */
    suspend fun merge(rows: List<ShotRow>): Boolean

    /**
     * The wipe: of [named] (the plan's removals, less the rows the merge left
     * listed), only rows the backup doesn't hold, as [backupIds] says.
     */
    suspend fun remove(named: List<String>, backupIds: Set<String>)
}

/** A walk over a list captured up front: P3a's, whose rows the snapshot took on the main thread. */
internal class ListWalk(private val rows: List<ShotRow>, private val read: (ShotRow) -> StoredShot) : ShotWalk {
    private var at = 0

    override val count: Int get() = rows.size

    override fun next(n: Int): List<WalkRow> {
        val out = rows.subList(at, minOf(rows.size, at + n)).map { WalkRow(it.id, it) }
        at += out.size
        return out
    }

    override fun rewind() {
        at = 0
    }

    override fun full(row: ShotRow): StoredShot = read(row)

    override fun close() = Unit
}

/**
 * P3a's `shots.db` as a [HistorySource]: a backup walks [rows], taken with
 * its snapshot, and reads each whole through [full]; step 7 goes through
 * [restore]. It keeps no write counter, so auto-backup builds and hashes.
 */
internal class ShotsDbSource(
    private val rows: List<ShotRow>,
    private val full: (ShotRow) -> StoredShot,
    private val restore: suspend () -> ShotsRestore = { error("this snapshot doesn't restore") },
) : HistorySource {
    override fun openWalk(): ShotWalk = ListWalk(rows, full)

    override fun writeMark(): String? = null

    override suspend fun beginRestore(): ShotsRestore = restore()
}
