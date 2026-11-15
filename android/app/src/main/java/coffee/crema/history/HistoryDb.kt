package coffee.crema.history

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import coffee.crema.core.BrewSeries
import coffee.crema.core.ShotSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/*
 * `shots.db`, where the history's rows live from #310 P3a (SPEC §3): one table
 * of rows, a row's JSON without its series, and a small `meta` table for the
 * migration record. The framework `SQLiteOpenHelper`, not Room: one table and a
 * handful of statements. Series stay one file per shot ([SeriesFiles]).
 */

/** An older build opened a database a newer one wrote: it refuses to write. */
class HistoryTooNewException(found: Int) :
    IllegalStateException("shots.db is version $found, newer than this build's ${HistoryDb.VERSION}: update the app")

/**
 * The error handler SQLite calls on a damaged file. The framework's default
 * deletes the database, its `-wal` and its `-shm`, and the open then makes a
 * fresh empty one, which would read as a history of nothing. This one moves the
 * three aside as `shots.db.corrupt-<ms>`, deletes nothing, and says so
 * ([quarantined]), so the store can call itself lost (SPEC §6).
 */
internal class QuarantineHandler(private val now: () -> Long = System::currentTimeMillis) : DatabaseErrorHandler {
    /** The quarantined main file, once one was moved aside. */
    @Volatile var quarantined: File? = null
        private set

    override fun onCorruption(db: SQLiteDatabase) {
        val path = db.path
        runCatching { if (db.isOpen) db.close() }
        quarantine(File(path))
    }

    /**
     * Move [main] and its siblings aside; true when the main file moved (or
     * wasn't there). The siblings go first, and the main file only once none
     * of them is left beside it: a fresh `shots.db` opened next to the old
     * `-wal` could replay its frames (#408 N9). A sibling that won't rename is
     * copied, the copy checked, and the original deleted. If that fails, the
     * siblings moved so far go back and nothing else moves, so the store reads
     * as unavailable rather than opening a fresh file.
     */
    fun quarantine(main: File): Boolean {
        val stamp = now()
        val moved = ArrayList<Pair<File, File>>()
        for (suffix in SIBLINGS.filter { it.isNotEmpty() }) {
            val f = File(main.path + suffix)
            if (!f.exists()) continue
            val to = File("${main.path}$CORRUPT$stamp$suffix")
            if (!moveAside(f, to)) {
                for ((from, at) in moved.asReversed()) runCatching { renameFile(at, from) }
                return false
            }
            moved += f to to
        }
        if (!main.exists()) return true
        val to = File("${main.path}$CORRUPT$stamp")
        if (!renameFile(main, to)) {
            for ((from, at) in moved.asReversed()) runCatching { renameFile(at, from) }
            return false
        }
        quarantined = to
        return true
    }

    /** [f] to [to] by a rename, or else a checked copy and the original deleted. False when [f] stays. */
    private fun moveAside(f: File, to: File): Boolean {
        if (renameFile(f, to)) return true
        val copied = runCatching {
            f.copyTo(to, overwrite = true)
            to.readBytes().contentEquals(f.readBytes())
        }.getOrDefault(false)
        if (copied && deleteFile(f)) return true
        runCatching { to.delete() }
        return false
    }

    companion object {
        const val CORRUPT = ".corrupt-"
        val SIBLINGS = listOf("", "-wal", "-shm", "-journal")

        /** Test seams: a rename or a delete that fails. */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var renameFile: (File, File) -> Boolean = { from, to -> from.renameTo(to) }

        @androidx.annotation.VisibleForTesting
        @Volatile internal var deleteFile: (File) -> Boolean = { it.delete() }
    }
}

/** `shots.db`. [created] tells a new file from one that was there. */
internal class HistoryDb(
    context: Context,
    name: String,
    val handler: QuarantineHandler = QuarantineHandler(),
) : SQLiteOpenHelper(context, name, null, VERSION, handler) {

    /** `onCreate` ran on this helper: the file is new (or replaced a quarantined one). */
    @Volatile var created = false
        private set

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // WAL with full sync: a commit is on disk when it returns.
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        created = true
        db.execSQL(
            "CREATE TABLE shots(id TEXT PRIMARY KEY, completed_at INTEGER NOT NULL, " +
                "row_json TEXT NOT NULL, profile_json TEXT, brew_series_json TEXT)",
        )
        db.execSQL("CREATE INDEX shots_by_date ON shots(completed_at DESC, id DESC)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the only one. Changes are additive only (SPEC §3).
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw HistoryTooNewException(oldVersion)
    }

    companion object {
        const val VERSION = 1
    }
}

/** A row as `shots.db` holds it: the shot without its series, and its summary. */
internal data class DbRow(val shot: StoredShot, val summary: ShotSummary?)

/**
 * The M4 hook (#310 A1, SQLite SPEC §3): what [HistoryTables.write] calls
 * around every write to `shots`, under `HistoryStore.lock`. [HistoryIndex]
 * is the one in the app. None of these may make the write fail: each
 * catches its own errors, and only a [SimulatedCrash] (tests) gets through.
 */
internal interface ShotsHook {
    /** Before the write: raise `pending`. */
    fun begin(): Any?

    /** The write committed as [ShotsWrite.seq]: mirror it, then lower `pending`. */
    fun committed(ticket: Any?, db: SQLiteDatabase, write: ShotsWrite)

    /** The write threw: what it left is unknown, so `pending` stays raised. */
    fun failed(ticket: Any?, error: Throwable)

    /** `shots.db` was set aside or deleted outside a write (a new store's clear). */
    fun replaced()

    object None : ShotsHook {
        override fun begin(): Any? = null
        override fun committed(ticket: Any?, db: SQLiteDatabase, write: ShotsWrite) = Unit
        override fun failed(ticket: Any?, error: Throwable) = Unit
        override fun replaced() = Unit
    }
}

/**
 * One transaction on `shots`, from [HistoryTables.write]: the only way to
 * write the table, so the hook sees every write (M4). It records the ids the
 * write touched, or that it cleared the table.
 */
internal class ShotsWrite(val db: SQLiteDatabase) {
    private val ids = LinkedHashSet<String>()

    /** The ids this write put, changed or deleted. */
    val touched: Set<String> get() = ids

    /** This write cleared the table: every row may have changed. */
    var cleared = false
        private set

    /**
     * The `index_seq` this write's transaction set in `meta` (#310 A1b): one
     * up from the one before, so every committed write has its own, and the
     * index records the last one it mirrored. Set once the block has run.
     */
    var seq: ULong = 0uL
        internal set

    fun touch(id: String) {
        ids += id
    }

    fun clearAll() {
        cleared = true
    }
}

/** The row codec and the statements over a [SQLiteDatabase]. */
internal class HistoryTables(private val json: Json, private val hook: ShotsHook = ShotsHook.None) {

    /**
     * Write `shots` in one transaction through [block], with the hook around
     * it (#310 A1, M4): `pending` up, the transaction, then the index and
     * `pending` down. Every writer of `shots` takes a [ShotsWrite], so there
     * is no other way in.
     *
     * The transaction also raises `index_seq` in `meta` (#310 A1b), so
     * `shots.db` itself records every write, whatever happens to the index:
     * the boot check compares it with the last seq the index mirrored. It is
     * read before [block] and set after, so a write that clears `meta`
     * keeps counting up.
     */
    fun <T> write(db: SQLiteDatabase, block: ShotsWrite.() -> T): T {
        val ticket = hook.begin()
        val w = ShotsWrite(db)
        val out = try {
            db.inTransaction {
                val before = indexSeq(this)
                val out = w.block()
                w.seq = before + 1uL
                putMeta(this, META_INDEX_SEQ, w.seq.toString())
                out
            }
        } catch (e: Throwable) {
            if (e !is SimulatedCrash) hook.failed(ticket, e)
            throw e
        }
        hook.committed(ticket, db, w)
        return out
    }

    /** `row_json`: the shot without `samples`, `profile` and `brewSeries`, plus its summary. */
    fun rowJson(shot: StoredShot, summary: ShotSummary): String {
        val light = json.encodeToJsonElement(StoredShot.serializer(), shot.copy(samples = emptyList(), profile = null, brewSeries = null)).jsonObject
        return JsonObject(light + (SUMMARY to json.encodeToJsonElement(ShotSummary.serializer(), summary))).toString()
    }

    fun decodeRow(rowJson: String): DbRow {
        val obj = json.parseToJsonElement(rowJson).jsonObject
        val summary = obj[SUMMARY]?.let { runCatching { json.decodeFromJsonElement(ShotSummary.serializer(), it) }.getOrNull() }
        return DbRow(json.decodeFromJsonElement(StoredShot.serializer(), JsonObject(obj - SUMMARY)), summary)
    }

    fun profileJson(shot: StoredShot): String? = shot.profile?.toString()

    fun brewSeriesJson(shot: StoredShot): String? = shot.brewSeries?.let { json.encodeToString(BrewSeries.serializer(), it) }

    fun decodeProfile(text: String?): JsonObject? = text?.let { json.parseToJsonElement(it).jsonObject }

    fun decodeBrewSeries(text: String?): BrewSeries? = text?.let { json.decodeFromString(BrewSeries.serializer(), it) }

    /** Every row, light (no profile, no brew series), newest first, ties by id. A row that
     *  won't decode (a newer build's, say) stays in the store, untouched, and is named in [unreadable]. */
    fun lightRows(db: SQLiteDatabase, unreadable: MutableList<String>? = null): List<DbRow> =
        db.rawQuery("SELECT id, row_json FROM shots ORDER BY completed_at DESC, id DESC", null).use { c ->
            val out = ArrayList<DbRow>(c.count)
            while (c.moveToNext()) {
                val row = runCatching { decodeRow(c.getString(1)) }.getOrNull()
                if (row != null) out += row else unreadable?.add(c.getString(0))
            }
            out
        }

    /** [ids]' profile and brew series, by id. */
    fun heavy(db: SQLiteDatabase, ids: Collection<String>): Map<String, Pair<JsonObject?, BrewSeries?>> {
        val out = HashMap<String, Pair<JsonObject?, BrewSeries?>>()
        for (chunk in ids.chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery("SELECT id, profile_json, brew_series_json FROM shots WHERE id IN ($marks)", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    out[c.getString(0)] = decodeProfile(c.getStringOrNull(1)) to decodeBrewSeries(c.getStringOrNull(2))
                }
            }
        }
        return out
    }

    /** All of a row, or null when it isn't there: inside a [write]. */
    fun full(w: ShotsWrite, id: String): DbRow? = full(w.db, id)

    /** All of a row, or null when it isn't there. */
    fun full(db: SQLiteDatabase, id: String): DbRow? =
        db.rawQuery("SELECT row_json, profile_json, brew_series_json FROM shots WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            val row = decodeRow(c.getString(0))
            row.copy(shot = row.shot.copy(profile = decodeProfile(c.getStringOrNull(1)), brewSeries = decodeBrewSeries(c.getStringOrNull(2))))
        }

    fun ids(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT id FROM shots", null).use { c ->
            val out = HashSet<String>(c.count)
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    fun count(db: SQLiteDatabase): Int = db.rawQuery("SELECT COUNT(*) FROM shots", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Write all of [shot]: its row and both heavy columns. */
    fun putFull(w: ShotsWrite, shot: StoredShot, summary: ShotSummary) {
        w.touch(shot.id)
        val values = ContentValues().apply {
            put("id", shot.id)
            put("completed_at", shot.completedAtMs)
            put("row_json", rowJson(shot, summary))
            put("profile_json", profileJson(shot))
            put("brew_series_json", brewSeriesJson(shot))
        }
        w.db.insertWithOnConflict("shots", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Write [shot]'s row, and each heavy column only when [shot] carries it: a
     * row from the view is light (#310 P4a), so a profile or brew series it
     * doesn't carry stays as stored. A row the table doesn't hold yet goes in
     * whole.
     */
    fun upsert(w: ShotsWrite, shot: StoredShot, summary: ShotSummary) {
        w.touch(shot.id)
        val values = ContentValues().apply {
            put("completed_at", shot.completedAtMs)
            put("row_json", rowJson(shot, summary))
            profileJson(shot)?.let { put("profile_json", it) }
            brewSeriesJson(shot)?.let { put("brew_series_json", it) }
        }
        if (w.db.update("shots", values, "id = ?", arrayOf(shot.id)) == 0) putFull(w, shot, summary)
    }

    /** The ids of the rows whose profile column is set. */
    fun idsWithProfile(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT id FROM shots WHERE profile_json IS NOT NULL", null).use { c ->
            val out = HashSet<String>(c.count)
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    /** Set [id]'s profile column alone: the row and its brew series stay as stored. */
    fun putProfile(w: ShotsWrite, id: String, profile: JsonObject) {
        w.touch(id)
        w.db.update("shots", ContentValues().apply { put("profile_json", profile.toString()) }, "id = ?", arrayOf(id))
    }

    /** The bytes [ids]' rows take in `shots`: row, profile and brew series (Free up space's preview, #310 P6a). */
    fun bytesOf(db: SQLiteDatabase, ids: Collection<String>): Long {
        var total = 0L
        for (chunk in ids.chunked(500)) {
            db.rawQuery(
                "SELECT SUM(LENGTH(row_json) + IFNULL(LENGTH(profile_json), 0) + IFNULL(LENGTH(brew_series_json), 0)) " +
                    "FROM shots WHERE id IN (${chunk.joinToString(",") { "?" }})",
                chunk.toTypedArray(),
            ).use { c -> if (c.moveToFirst()) total += c.getLong(0) }
        }
        return total
    }

    fun delete(w: ShotsWrite, ids: Collection<String>) {
        for (id in ids) w.touch(id)
        for (chunk in ids.chunked(500)) {
            w.db.delete("shots", "id IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray())
        }
    }

    fun clear(w: ShotsWrite) {
        w.clearAll()
        w.db.delete("shots", null, null)
        w.db.delete("meta", null, null)
    }

    /** [ids]' `row_json` as stored, by id: what the index mirrors (A1). An id the table lacks is absent. */
    fun rawRows(db: SQLiteDatabase, ids: Collection<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (chunk in ids.chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery("SELECT id, row_json FROM shots WHERE id IN ($marks)", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
            }
        }
        return out
    }

    /** Up to [limit] rows' ids and `row_json` as stored, by id, after [afterId]: the full diff's batches (A1). */
    fun rawPage(db: SQLiteDatabase, afterId: String?, limit: Int): List<Pair<String, String>> {
        val c = if (afterId == null) {
            db.rawQuery("SELECT id, row_json FROM shots ORDER BY id LIMIT $limit", null)
        } else {
            db.rawQuery("SELECT id, row_json FROM shots WHERE id > ? ORDER BY id LIMIT $limit", arrayOf(afterId))
        }
        return c.use {
            val out = ArrayList<Pair<String, String>>(it.count)
            while (it.moveToNext()) out += it.getString(0) to it.getString(1)
            out
        }
    }

    fun meta(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** The seq of the last write to `shots` ([write]), 0 before the first. */
    fun indexSeq(db: SQLiteDatabase): ULong = meta(db, META_INDEX_SEQ)?.toULongOrNull() ?: 0uL

    fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun android.database.Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    companion object {
        const val SUMMARY = "summary"
        const val META_MIGRATION = "migration"

        /** The hook's count of writes to `shots` (#310 A1b), and SPEC §11 step 5's `source_seq` at the flip. */
        const val META_INDEX_SEQ = "index_seq"
    }
}

/** Run [block] in one transaction: it commits only if [block] returns. */
internal inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val out = block()
        setTransactionSuccessful()
        return out
    } finally {
        endTransaction()
    }
}
