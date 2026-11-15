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

/** The row codec and the statements over a [SQLiteDatabase]. */
internal class HistoryTables(private val json: Json) {

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
    fun putFull(db: SQLiteDatabase, shot: StoredShot, summary: ShotSummary) {
        val values = ContentValues().apply {
            put("id", shot.id)
            put("completed_at", shot.completedAtMs)
            put("row_json", rowJson(shot, summary))
            put("profile_json", profileJson(shot))
            put("brew_series_json", brewSeriesJson(shot))
        }
        db.insertWithOnConflict("shots", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Write [shot]'s row, and each heavy column only when [shot] carries it: a
     * row from the view is light (#310 P4a), so a profile or brew series it
     * doesn't carry stays as stored. A row the table doesn't hold yet goes in
     * whole.
     */
    fun upsert(db: SQLiteDatabase, shot: StoredShot, summary: ShotSummary) {
        val values = ContentValues().apply {
            put("completed_at", shot.completedAtMs)
            put("row_json", rowJson(shot, summary))
            profileJson(shot)?.let { put("profile_json", it) }
            brewSeriesJson(shot)?.let { put("brew_series_json", it) }
        }
        if (db.update("shots", values, "id = ?", arrayOf(shot.id)) == 0) putFull(db, shot, summary)
    }

    /** The ids of the rows whose profile column is set. */
    fun idsWithProfile(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT id FROM shots WHERE profile_json IS NOT NULL", null).use { c ->
            val out = HashSet<String>(c.count)
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    /** Set [id]'s profile column alone: the row and its brew series stay as stored. */
    fun putProfile(db: SQLiteDatabase, id: String, profile: JsonObject) {
        db.update("shots", ContentValues().apply { put("profile_json", profile.toString()) }, "id = ?", arrayOf(id))
    }

    fun delete(db: SQLiteDatabase, ids: Collection<String>) {
        for (chunk in ids.chunked(500)) {
            db.delete("shots", "id IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray())
        }
    }

    fun clear(db: SQLiteDatabase) {
        db.delete("shots", null, null)
        db.delete("meta", null, null)
    }

    fun meta(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun android.database.Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    companion object {
        const val SUMMARY = "summary"
        const val META_MIGRATION = "migration"
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
