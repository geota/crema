package coffee.crema.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import coffee.crema.core.ShotSummary
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SafeLoad
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.StoreHealth
import coffee.crema.persist.Stores
import coffee.crema.persist.eraseStore
import coffee.crema.persist.loadStore
import coffee.crema.persist.saveStore
import coffee.crema.ui.TelemetrySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/*
 * The shot history's store (#310 P3a, SPEC §3 and §6). Rows live in `shots.db`
 * (SQLite), series in one file per shot. Three rules hold:
 *
 * 1. Deleting is explicit. [save] only upserts, by a diff against what it last
 *    wrote; a row goes only through [delete], which names it.
 * 2. Not loaded is not empty. The rows reach the app through [loadAll]; until
 *    then nothing here reads a list as the history.
 * 3. The old copy stays. After migration nothing writes to or deletes
 *    `shots.json` or the `.json` series files.
 *
 * The view the app shows is capped at [MAX_SHOTS] unpinned rows, as before;
 * the store keeps every row. A row past the cap is hidden ([hiddenRows]), light
 * (no profile, brew series or samples), and comes back when the cap lifts.
 *
 * If the migration can't finish, the app runs on the legacy backend: today's
 * `shots.json`, with its cap, retried at the next two launches and then at most
 * once a day.
 */

/** How the history's store is (SPEC §2 `history.health`). */
enum class HistoryHealth {
    /** Nothing has opened it yet. */
    LOADING,
    OK,
    /** The migration didn't finish: `shots.json`, with the cap. */
    LEGACY,
    /** The store is there and wouldn't open. Nothing is written to it. */
    UNAVAILABLE,
    /** The store was there and is gone or damaged. Nothing is written or migrated until the user chooses. */
    LOST,
}

/** `history-store.json`: whether the rows moved, kept outside the store so a lost store can't pass for a new install. */
@Serializable
internal data class HistoryMarker(
    val state: String,
    val failures: Int = 0,
    val nextTryAtMs: Long = 0,
    val lastError: String? = null,
    val atMs: Long = 0,
)

/** The store's own migration record, in its `meta` table. */
@Serializable
internal data class MigrationRecord(
    val state: String,
    val sourceHash: String = "",
    val sourceIds: List<String> = emptyList(),
    /** Late rows that arrived with no series: their old build writes the series after the row. */
    val lateIds: List<String> = emptyList(),
    val duplicates: Int = 0,
)

/** What a load gave: the rows in view, newest first, and how many are hidden past the cap. */
data class HistoryLoad(val view: List<StoredShot>, val hiddenCount: Int, val health: HistoryHealth)

/** What a lost store can be rebuilt from, for the banner. */
data class LostChoices(
    val snapshotAtMs: Long?,
    val snapshotRows: Int?,
    val oldCopyAtMs: Long?,
    val oldCopyRows: Int?,
)

/** A migration step a test can make fail (each verify check has one), or stop at as a crash would. */
@androidx.annotation.VisibleForTesting
enum class MigrationFault {
    // Verify: each must fail its own check.
    DROP_ROW, EXTRA_ROW, ALTER_ROW, ALTER_PROFILE, ALTER_SERIES, ALTER_COUNT, ALTER_FINAL_WEIGHT,
    // Interruptions: the process dies here.
    CRASH_IN_TRANSACTION, CRASH_AFTER_COMMIT, CRASH_IN_SERIES,
    // A full disk while the rows are written.
    DISK_FULL,
}

/** The process died here (a test's stand-in for a kill). Never caught by the store. */
@androidx.annotation.VisibleForTesting
class SimulatedCrash(at: String) : Error("simulated crash at $at")

class HistoryStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)
    private val markerFile get() = File(context.filesDir, MARKER_NAME)
    private val snapshotFile get() = File(context.filesDir, SNAPSHOT_NAME)
    private val pendingFile get() = File(context.filesDir, PENDING_NAME)
    private val dbFile: File
        get() = runCatching { context.getDatabasePath(NEXT_DB_NAME) }.getOrNull()
            ?: File(context.filesDir.parentFile, "databases/$NEXT_DB_NAME")
    private val seriesDir get() = File(context.filesDir, SERIES_DIR)
    private val series = SeriesFiles(File(context.filesDir, SERIES_DIR), json)
    private val tables = HistoryTables(json)

    /** Loads, saves and deletes run on IO and may overlap; one at a time. */
    private val lock = Mutex()

    private enum class Mode { UNOPENED, SQL, LEGACY, BLOCKED }

    @Volatile private var mode = Mode.UNOPENED

    @Volatile private var helper: HistoryDb? = null

    /** How the store is, this session. */
    @Volatile var health: HistoryHealth = HistoryHealth.LOADING
        private set

    /** Why the last migration failed, or the store wouldn't open: Settings shows it. */
    @Volatile var lastError: String? = null
        private set

    /** What a lost store can be rebuilt from, while [health] is [HistoryHealth.LOST]. */
    @Volatile var lostChoices: LostChoices? = null
        private set

    // ── The SQL backend's memory of what it wrote ─────────────────────────────

    /** Each row as `shots.db` holds it (series stripped), by id: the save's diff, and the hidden rows. */
    private val stored = ConcurrentHashMap<String, StoredShot>()

    /** Each row's stored summary. */
    private val summaries = ConcurrentHashMap<String, ShotSummary>()

    /** Rows loaded without their profile and brew series: a save of one keeps the stored ones. */
    private val light: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The last object seen for each row in a save: the same object is unchanged. */
    private val seen = ConcurrentHashMap<String, StoredShot>()

    /** Ids whose series is on disk, in any of its files. */
    private val onDisk: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Rows whose series read back with another count than their stored summary: the next save corrects it. */
    private val staleSummary: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Rows a delete has taken out of the store, or is about to. */
    private val deleting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Bumped on every change to [stored], for [hiddenRows]'s cache. */
    @Volatile private var version = 0L
    @Volatile private var hiddenCache: Triple<Long, Set<String>, List<StoredShot>>? = null

    /** The day of the last rows snapshot. */
    @Volatile private var snapshotDay: LocalDate? = null

    /** Rows whose curve couldn't be written: they keep it in memory, and the next save tries again. */
    @Volatile var unsavedCurves: Set<String> = emptySet()
        private set

    /** Rows that went to the pending journal instead of the store. */
    @Volatile var journaled: Set<String> = emptySet()
        private set

    /** This session's move found `shots.json` damaged (kept aside): an unread row may be a pinned one. */
    @Volatile var sourceDamaged: Boolean = false
        private set

    /** Rows the store holds that this build couldn't read: left as they are, never in a backup made now. */
    @Volatile var unreadableRows: List<String> = emptyList()
        private set

    /** Shots whose series file was unreadable at load (kept aside and reported). */
    @Volatile var unreadableSeries: Set<String> = emptySet()
        private set

    // ── The legacy backend (shots.json), as before P3a ───────────────────────

    /** Series files that already hold the in-memory series (legacy). */
    private val written = mutableSetOf<String>()

    // ── Open, migrate, load ──────────────────────────────────────────────────

    /** Whether the rows are in SQLite this session. */
    val usesSql: Boolean get() = mode == Mode.SQL

    /** The cap the store keeps, for a restore's plan: none from P3a, or legacy's. */
    val capacity: Int? get() = if (mode == Mode.LEGACY) MAX_SHOTS else null

    /** The rows' load, as the restore and the backups read it. */
    val storeHealth: StoreHealth
        get() = when (health) {
            HistoryHealth.LOADING -> StoreHealth.LOADING
            HistoryHealth.OK -> StoreHealth.OK
            HistoryHealth.LEGACY -> Stores.health(file)
            HistoryHealth.UNAVAILABLE -> StoreHealth.UNAVAILABLE
            HistoryHealth.LOST -> StoreHealth.DAMAGED
        }

    /** Every row (view and hidden) with its series: for tests and small tools. */
    suspend fun load(): List<StoredShot> {
        val loaded = loadAll()
        val all = loaded.view + hiddenRows(loaded.view.mapTo(HashSet()) { it.id }).map { loadFull(it) }
        val seriesById = loadSeries(all.filter { it.samples.isEmpty() }.map { it.id })
        return all.map { row -> seriesById[row.id]?.let { row.copy(samples = it) } ?: row }.sortedWith(ORDER)
    }

    /** The rows (view only, the legacy backend's whole list), without their series. Opens the store first. */
    suspend fun loadRows(): List<StoredShot> = loadAll().view

    /**
     * Open the store, migrating on the first launch, and give the rows in view.
     * Rows past the cap stay in the store, hidden. On the legacy backend the
     * rows are `shots.json`'s, and saves stay refused until [openSaves].
     */
    suspend fun loadAll(): HistoryLoad = withContext(Dispatchers.IO) {
        holdOpen?.await()
        lock.withLock {
            guarded {
                openLocked(force = false)
                loadOpened()
            }
        }
    }

    private fun loadOpened(): HistoryLoad = when (mode) {
        Mode.SQL -> sqlLoad()
        Mode.LEGACY -> HistoryLoad(legacyLoad(), 0, health)
        else -> HistoryLoad(emptyList(), 0, health)
    }

    /** [block], and if anything unforeseen goes wrong, an unavailable store rather than a crash at every launch. */
    private inline fun guarded(block: () -> HistoryLoad): HistoryLoad = try {
        block()
    } catch (e: SimulatedCrash) {
        throw e
    } catch (e: Throwable) {
        persistLine("history open failed: $e")
        unavailable(e.message ?: e.javaClass.simpleName)
        HistoryLoad(emptyList(), 0, health)
    }

    /** Try again: a failed migration (Settings), or a store that wouldn't open (the banner, resume). */
    suspend fun retry(): HistoryLoad = withContext(Dispatchers.IO) {
        lock.withLock {
            if (health == HistoryHealth.LOST) return@withLock HistoryLoad(emptyList(), 0, health)
            closeDb()
            mode = Mode.UNOPENED
            guarded {
                openLocked(force = true)
                loadOpened()
            }
        }
    }

    /** Open for a save or a delete that came before any load (tests plant rows this way). */
    private fun ensureOpenLocked() {
        if (mode == Mode.UNOPENED) openLocked(force = false)
    }

    private fun openLocked(force: Boolean) {
        if (mode != Mode.UNOPENED && !force) return
        if (legacyOnlyForTest) {
            toLegacy()
            return
        }
        val marker = readMarker()
        when {
            marker?.state == STATE_LOST -> lost(null)
            marker?.state == STATE_DONE -> openDone()
            else -> {
                // A commit whose marker a kill cut off: the store's own flag says done.
                if (dbFile.exists() && storeSaysDone()) {
                    writeMarker(HistoryMarker(STATE_DONE, atMs = now()))
                    openDone()
                    return
                }
                if (!force && marker?.state == STATE_FAILED && now() < marker.nextTryAtMs) {
                    lastError = marker.lastError
                    toLegacy()
                    return
                }
                migrate(marker)
            }
        }
    }

    /** Whether an existing `shots.db` holds a finished migration. Opens nothing it can't read. */
    private fun storeSaysDone(): Boolean = runCatching {
        val db = db().writableDatabase
        decodeRecord(tables.meta(db, TABLE_META_KEY))?.state == STATE_DONE
    }.getOrElse {
        closeDb()
        false
    }

    /** The marker says done: open the store, or say why not. Never migrates. */
    private fun openDone() {
        if (!dbFile.exists()) {
            lost("The shot history's database is missing.")
            return
        }
        val h = db()
        val db = try {
            h.writableDatabase
        } catch (e: HistoryTooNewException) {
            unavailable(e.message ?: "update the app")
            return
        } catch (e: Exception) {
            if (h.handler.quarantined != null) lost("The shot history's database was damaged and has been kept aside.")
            else unavailable(e.message ?: e.javaClass.simpleName)
            return
        }
        if (h.handler.quarantined != null || h.created) {
            // The framework reopened a fresh, empty file in its place: not ours to read as the history.
            closeDb()
            if (h.handler.quarantined != null) context.deleteDatabase(NEXT_DB_NAME)
            lost("The shot history's database was damaged and has been kept aside.")
            return
        }
        val record = try {
            decodeRecord(tables.meta(db, TABLE_META_KEY))
        } catch (e: Exception) {
            // A read that failed says nothing about the store: try again later, not lost.
            unavailable(e.message ?: e.javaClass.simpleName)
            return
        }
        if (record?.state != STATE_DONE) {
            closeDb()
            lost("The shot history's database doesn't hold a finished copy.")
            return
        }
        runCatching { lateRows(db, record) }.onFailure { persistLine("late rows: ${it.message}") }
        runCatching { mergePending(db) }.onFailure { persistLine("pending journal: ${it.message}") }
        mode = Mode.SQL
        health = HistoryHealth.OK
        lastError = null
        Stores.recordHealth(dbFile, WHAT, StoreHealth.OK)
    }

    private fun lost(why: String?) {
        mode = Mode.BLOCKED
        health = HistoryHealth.LOST
        lastError = why ?: readMarker()?.lastError
        val m = readMarker()
        if (m?.state != STATE_LOST) writeMarker(HistoryMarker(STATE_LOST, lastError = lastError, atMs = now()))
        lostChoices = choices()
        Stores.recordHealth(dbFile, WHAT, StoreHealth.DAMAGED)
    }

    private fun unavailable(why: String) {
        closeDb()
        mode = Mode.BLOCKED
        health = HistoryHealth.UNAVAILABLE
        lastError = why
        Stores.recordHealth(dbFile, WHAT, StoreHealth.UNAVAILABLE)
    }

    private fun toLegacy() {
        closeDb()
        mode = Mode.LEGACY
        health = HistoryHealth.LEGACY
    }

    /** SPEC §6, steps 2 to 7, on Android: series first, then every row and the flag in one transaction. */
    private fun migrate(marker: HistoryMarker?) {
        try {
            migrateOrThrow()
            fault(MigrationFault.CRASH_AFTER_COMMIT)?.let { throw SimulatedCrash("after commit") }
            writeMarker(HistoryMarker(STATE_DONE, atMs = now()))
            openDone()
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: MigrationFailed) {
            fail(marker, e.message ?: "migration failed")
        } catch (e: Throwable) {
            // Out of memory included: a failure backs off, a crash would repeat at every launch.
            fail(marker, e.message ?: e.javaClass.simpleName)
        }
    }

    private class MigrationFailed(why: String) : Exception(why)

    private fun migrateOrThrow() {
        // Clear: a restart never builds on a partial copy.
        closeDb()
        context.deleteDatabase(NEXT_DB_NAME)
        if (legacyOnlyForTest) throw MigrationFailed("legacy only (test)")
        val outcome = loadStore(file, WHAT, open = false) {
            json.decodeFromString(ListSerializer(StoredShot.serializer()), it)
        }
        sourceDamaged = outcome is SafeLoad.Corrupt
        val source: List<StoredShot> = when (outcome) {
            is SafeLoad.Loaded -> outcome.value
            // Nothing to copy: a fresh install, or a file kept aside as damaged (as before P3a).
            SafeLoad.Absent, is SafeLoad.Corrupt -> emptyList()
            is SafeLoad.Unavailable -> throw MigrationFailed("Couldn't read shots.json: ${outcome.error}")
        }
        if (source.isNotEmpty()) {
            val need = (file.length() * 3) / 2
            val free = freeSpace()
            if (fault(MigrationFault.DISK_FULL) != null || free < need) {
                throw MigrationFailed("Not enough free space to move the shot history ($need bytes needed)")
            }
        }
        val firsts = LinkedHashMap<String, StoredShot>()
        for (s in source) firsts.putIfAbsent(s.id, s)
        val duplicates = source.size - firsts.size
        // Each row's series: inline wins, then its file. A damaged file keeps the row, unknown.
        val prepared = firsts.values.map { row ->
            val samples: List<TelemetrySample>?
            if (row.samples.isNotEmpty()) {
                samples = row.samples
            } else {
                samples = when (val read = series.read(row.id)) {
                    is SeriesRead.Found -> read.samples
                    SeriesRead.Absent -> emptyList()
                    SeriesRead.Corrupt -> null
                    is SeriesRead.Unavailable -> throw MigrationFailed("Couldn't read a shot's telemetry: ${read.error}")
                }
            }
            Prepared(row, samples)
        }
        // Inline series to their files first, so no row names a file that isn't there.
        var seriesWritten = 0
        for (p in prepared) {
            if (p.row.samples.isEmpty()) continue
            if (seriesWritten > 0 && fault(MigrationFault.CRASH_IN_SERIES) != null) throw SimulatedCrash("series")
            val curve = if (fault(MigrationFault.ALTER_SERIES) != null && p === prepared.firstOrNull { it.row.samples.isNotEmpty() }) {
                p.row.samples.drop(1)
            } else {
                p.row.samples
            }
            if (!series.write(p.row.id, curve)) throw MigrationFailed("Couldn't write a shot's telemetry")
            seriesWritten += 1
        }
        val hash = sha256(runCatching { file.readBytes() }.getOrDefault(ByteArray(0)))
        val db = db().writableDatabase
        db.inTransaction {
            tables.clear(this)
            var written = 0
            val rows = if (fault(MigrationFault.DROP_ROW) != null) prepared.dropLast(1) else prepared
            for (p in rows) {
                val withSeries = p.row.copy(samples = p.samples ?: emptyList())
                var summary = if (p.samples == null) unknownSeriesSummary(p.row) else summaryOf(withSeries, json)
                var row = p.row
                if (p === rows.lastOrNull()) {
                    if (fault(MigrationFault.ALTER_ROW) != null) row = row.copy(notes = (row.notes ?: "") + " (changed)")
                    if (fault(MigrationFault.ALTER_PROFILE) != null) row = row.copy(profile = JsonObject(mapOf("changed" to JsonPrimitive(true))))
                    if (fault(MigrationFault.ALTER_COUNT) != null) summary = summary.copy(sampleCount = (summary.sampleCount ?: 0u) + 1u)
                    if (fault(MigrationFault.ALTER_FINAL_WEIGHT) != null) summary = summary.copy(finalWeight = (summary.finalWeight ?: 0f) + 1f)
                }
                tables.putFull(this, row, summary)
                written += 1
                if (written == 1 && fault(MigrationFault.CRASH_IN_TRANSACTION) != null) throw SimulatedCrash("transaction")
            }
            if (fault(MigrationFault.EXTRA_ROW) != null) {
                val extra = StoredShot(id = "extra-${now()}", completedAtMs = 1, durationMs = 1)
                tables.putFull(this, extra, summaryOf(extra, json))
            }
            verify(this, prepared)?.let { throw MigrationFailed("Verify failed: $it") }
            tables.putMeta(
                this,
                TABLE_META_KEY,
                json.encodeToString(
                    MigrationRecord.serializer(),
                    MigrationRecord(STATE_DONE, hash, firsts.keys.toList(), duplicates = duplicates),
                ),
            )
        }
        // The helper that made the file says `created`: the open after uses its own.
        closeDb()
    }

    /** A source row, deduplicated, with its series: null when the file was damaged. */
    private class Prepared(val row: StoredShot, val samples: List<TelemetrySample>?)

    /**
     * Read everything back and compare (SPEC §6, Verify). Null when it all
     * matches, else which check failed. Runs inside the migration's
     * transaction, so a failure commits nothing.
     */
    private fun verify(db: SQLiteDatabase, source: List<Prepared>): String? {
        val rows = tables.lightRows(db).associateBy { it.shot.id }
        val sourceIds = source.mapTo(HashSet()) { it.row.id }
        if (rows.keys != sourceIds) {
            val missing = (sourceIds - rows.keys).size
            val extra = (rows.keys - sourceIds).size
            return "ids: $missing missing, $extra not in the source"
        }
        val heavy = tables.heavy(db, sourceIds)
        for (p in source) {
            val got = rows.getValue(p.row.id)
            if (got.shot != p.row.copy(samples = emptyList(), profile = null, brewSeries = null)) return "row ${p.row.id} differs"
            val (profile, brew) = heavy[p.row.id] ?: (null to null)
            if (profile != p.row.profile || brew != p.row.brewSeries) return "details of ${p.row.id} differ"
            val expected = p.samples
            if (expected != null) {
                val back = when (val read = series.read(p.row.id)) {
                    is SeriesRead.Found -> read.samples
                    SeriesRead.Absent -> emptyList()
                    else -> null
                }
                if (back != expected) return "series of ${p.row.id} differs"
            }
            val count = got.summary?.sampleCount
            if (count != expected?.size?.toUInt()) return "sample count of ${p.row.id}: $count, not ${expected?.size}"
            val old = signatureOfShot(p.row.copy(samples = expected ?: emptyList()))
            if (old != signatureOfRow(got.shot, got.summary)) return "signature of ${p.row.id} differs"
        }
        return null
    }

    /** Failure (SPEC §6): clear the new store, run on the legacy backend, and back off. */
    private fun fail(marker: HistoryMarker?, why: String) {
        persistLine("history migration failed: $why")
        closeDb()
        runCatching { context.deleteDatabase(NEXT_DB_NAME) }
        val failures = (marker?.takeIf { it.state == STATE_FAILED }?.failures ?: 0) + 1
        // The next two launches try again; after that, at most once a day.
        val next = if (failures < 3) 0L else now() + DAY_MS
        writeMarker(HistoryMarker(STATE_FAILED, failures = failures, nextTryAtMs = next, lastError = why, atMs = now()))
        lastError = why
        toLegacy()
    }

    /**
     * SPEC §6 steps 8 and 9, at each launch: rows an old build or tab added to
     * `shots.json` since the copy come in, unless their Visualizer copy is
     * already here; one that arrived before its series gets it once it lands.
     */
    private fun lateRows(db: SQLiteDatabase, record: MigrationRecord) {
        var next = record
        val bytes = runCatching { if (file.exists()) file.readBytes() else null }.getOrNull()
        val hash = bytes?.let(::sha256)
        if (hash != null && hash != record.sourceHash) {
            val blob = runCatching {
                json.decodeFromString(ListSerializer(StoredShot.serializer()), bytes.toString(Charsets.UTF_8))
            }.getOrNull()
            if (blob != null) {
                val known = record.sourceIds.toHashSet()
                val heldIds = tables.ids(db)
                val heldRemote = tables.lightRows(db).mapNotNullTo(HashSet()) { it.shot.visualizerId }
                val considered = mutableListOf<String>()
                val late = mutableListOf<String>()
                db.inTransaction {
                    for (row in blob) {
                        if (row.id in known || row.id in considered) continue
                        considered += row.id
                        if (row.id in heldIds) continue
                        if (row.visualizerId != null && row.visualizerId in heldRemote) continue
                        val samples = if (row.samples.isNotEmpty()) {
                            if (!series.write(row.id, row.samples)) continue
                            row.samples
                        } else {
                            when (val read = series.read(row.id)) {
                                is SeriesRead.Found -> read.samples
                                else -> emptyList()
                            }
                        }
                        tables.putFull(this, row.copy(samples = emptyList()), summaryOf(row.copy(samples = samples), json))
                        if (samples.isEmpty() && row.brewMethod == null) late += row.id
                    }
                }
                next = next.copy(sourceHash = hash, sourceIds = record.sourceIds + considered, lateIds = (record.lateIds + late).distinct())
            }
        }
        // Step 9: a late row's series may have landed since.
        if (next.lateIds.isNotEmpty()) {
            val still = mutableListOf<String>()
            db.inTransaction {
                for (id in next.lateIds) {
                    val row = tables.full(this, id) ?: continue
                    when (val read = series.read(id)) {
                        is SeriesRead.Found -> tables.putFull(this, row.shot, summaryOf(row.shot.copy(samples = read.samples), json))
                        else -> still += id
                    }
                }
            }
            next = next.copy(lateIds = still)
        }
        if (next != record) db.inTransaction { tables.putMeta(this, TABLE_META_KEY, json.encodeToString(MigrationRecord.serializer(), next)) }
    }

    /** Rows that went to the pending journal while the store was out of reach, merged now it's back. */
    private fun mergePending(db: SQLiteDatabase) {
        if (!pendingFile.exists()) return
        val rows = pendingFile.readLines().mapNotNull { line ->
            runCatching { json.decodeFromString(StoredShot.serializer(), line) }.getOrNull()
        }
        db.inTransaction {
            for (row in rows) {
                val samples = (series.read(row.id) as? SeriesRead.Found)?.samples ?: emptyList()
                val summary = summaryOf(row.copy(samples = samples), json)
                // A light row's journal line has no profile: the stored one stays.
                if (row.profile == null && row.brewSeries == null && tables.full(this, row.id) != null) tables.putLight(this, row, summary)
                else tables.putFull(this, row.copy(samples = emptyList()), summary)
            }
        }
        // Ours, not the old copy: it has landed in the store.
        pendingFile.delete()
        journaled = emptySet()
    }

    /** The rows in view now and every row's memory, from `shots.db`. */
    private fun sqlLoad(): HistoryLoad {
        val db = db().readableDatabase
        val bad = mutableListOf<String>()
        val all = tables.lightRows(db, bad)
        unreadableRows = bad
        if (bad.isNotEmpty()) persistLine("${bad.size} rows couldn't be read and stay in the store as they are")
        val (view, hidden) = splitView(all.map { it.shot })
        val heavy = tables.heavy(db, view.map { it.id })
        val full = view.map { row -> heavy[row.id]?.let { (p, b) -> row.copy(profile = p, brewSeries = b) } ?: row }
        stored.clear(); summaries.clear(); light.clear(); seen.clear(); deleting.clear(); pendingHidden.clear()
        for (r in all) r.summary?.let { summaries[r.shot.id] = it }
        for (r in full) stored[r.id] = r
        for (r in hidden) {
            stored[r.id] = r
            light += r.id
        }
        // A curve on disk is known from the summary; the file isn't read until it's wanted.
        for (r in all) if (r.summary.hasCurve()) onDisk += r.shot.id
        version++
        snapshotDay = snapshotFile.takeIf { it.exists() }?.lastModified()?.let { localDateOf(it, ZoneId.systemDefault()) }
        return HistoryLoad(full, hidden.size, health)
    }

    // ── Rows past the cap ────────────────────────────────────────────────────

    /** The rows the store holds that aren't in [viewIds]: past the cap, light, newest first. */
    fun hiddenRows(viewIds: Set<String>): List<StoredShot> {
        if (mode != Mode.SQL) return emptyList()
        val cached = hiddenCache
        if (cached != null && cached.first == version && cached.second == viewIds) return cached.third
        val rows = stored.values.filter { it.id !in viewIds && it.id !in deleting }.sortedWith(ORDER)
        hiddenCache = Triple(version, viewIds, rows)
        return rows
    }

    /** Every row id the store holds (SQL), in view or not. */
    fun storedIds(): Set<String> = if (mode == Mode.SQL) stored.keys - deleting else emptySet()

    /** Every row the store holds (SQL), light past the cap. */
    fun storedRows(): List<StoredShot> = if (mode == Mode.SQL) stored.values.filter { it.id !in deleting } else emptyList()

    /** The stored row [id], hidden or not, light when hidden. */
    fun storedRow(id: String): StoredShot? = if (mode == Mode.SQL && id !in deleting) stored[id] else null

    /** Whether [shot] has a machine curve, in memory or on disk. */
    fun hasCurve(shot: StoredShot): Boolean =
        shot.samples.isNotEmpty() || (mode == Mode.SQL && summaries[shot.id].hasCurve())

    /** Changes to rows past the cap that are in memory and not yet written ([flushHidden]). */
    private val pendingHidden = ConcurrentHashMap<String, StoredShot>()

    /**
     * Change a stored row past the cap, where no edit of the view can reach it:
     * in memory at once, so a reader right after sees it (a bound upload's
     * "is it gone"), and on disk by [flushHidden]. A light row carries no
     * profile, so a change that sets one only fills a profile the store lacks.
     * True when the row is here.
     */
    fun updateHidden(id: String, transform: (StoredShot) -> StoredShot): Boolean {
        if (mode != Mode.SQL || id in deleting) return false
        val prev = pendingHidden[id] ?: stored[id] ?: return false
        val next = transform(prev)
        if (next == prev) return true
        pendingHidden[id] = next
        stored[id] = next.stripped()
        version++
        return true
    }

    /** Write [id]'s change from [updateHidden]. */
    suspend fun flushHidden(id: String) = withContext(NonCancellable) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                var row = pendingHidden.remove(id) ?: return@withContext
                if (mode != Mode.SQL || id in deleting) return@withContext
                if (id in light && row.profile != null) {
                    // Fill only: a stored profile stays.
                    val held = runCatching { tables.full(db().readableDatabase, id) }.getOrNull()?.shot
                    if (held?.profile != null) row = row.copy(profile = null)
                }
                var curveless = false
                if (row.samples.isNotEmpty() && id !in onDisk) {
                    if (series.write(id, row.samples)) onDisk += id else curveless = true
                }
                val summary = summaryFor(row).let { if (curveless) it.copy(sampleCount = null) else it }
                if (curveless) unsavedCurves = unsavedCurves + id
                if (runCatching { writeRows(listOf(row to summary)) }.isFailure) journalRow(row)
            }
        }
    }

    /** A hidden row with its profile, brew series and series, for a backup. Itself when it isn't light. */
    fun loadFull(row: StoredShot): StoredShot {
        if (mode != Mode.SQL) return row
        var out = row
        if (row.id in light && row.profile == null && row.brewSeries == null) {
            val full = runCatching { tables.full(db().readableDatabase, row.id) }.getOrNull()
            if (full != null) out = out.copy(profile = full.shot.profile, brewSeries = full.shot.brewSeries)
        }
        if (out.samples.isEmpty() && summaries[row.id].hasCurve()) {
            (series.read(row.id) as? SeriesRead.Found)?.let { out = out.copy(samples = it.samples) }
        }
        return out
    }

    // ── Series ───────────────────────────────────────────────────────────────

    /** The series of each of [ids] that has a file, by id. On IO; at startup it runs after the rows are shown. */
    suspend fun loadSeries(ids: Collection<String>): Map<String, List<TelemetrySample>> =
        withContext(Dispatchers.IO) {
            holdSeriesLoad?.await()
            lock.withLock {
                val out = HashMap<String, List<TelemetrySample>>()
                for (id in ids) {
                    when (val read = series.read(id)) {
                        is SeriesRead.Found -> {
                            out[id] = read.samples
                            onDisk += id
                            written += read.file.name
                            val count = summaries[id]?.sampleCount
                            if (mode == Mode.SQL && count != read.samples.size.toUInt()) staleSummary += id
                        }
                        SeriesRead.Corrupt, is SeriesRead.Unavailable -> unreadableSeries = unreadableSeries + id
                        SeriesRead.Absent -> Unit
                    }
                }
                out
            }
        }

    /** A restore's step 7, first half: these shots' series files, before any row names them. Returns how many failed. */
    suspend fun writeSeriesOf(shots: List<StoredShot>): Int = withContext(NonCancellable) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                var failed = 0
                for (shot in shots) {
                    if (shot.samples.isEmpty()) continue
                    if (series.write(shot.id, shot.samples)) {
                        onDisk += shot.id
                        written += series.gzFile(shot.id).name
                    } else {
                        failed++
                    }
                }
                failed
            }
        }
    }

    /** The series directory, for a restore's directory sync. */
    val seriesDirectory: File get() = seriesDir

    // ── Save and delete ──────────────────────────────────────────────────────

    /** The rows are in the UI state: the legacy backend's saves can't carry defaults now. */
    fun openSaves() = SafeFile.openSaves(file)

    /** Whether saves are being refused because the rows couldn't be read. */
    val savesRefused: Boolean
        get() = when (mode) {
            Mode.LEGACY -> SafeFile.isBlocked(file)
            Mode.BLOCKED -> true
            else -> false
        }

    /**
     * Save [shots], the history as the app shows it. On SQL only the rows that
     * changed since the last save are written, and nothing is deleted: a row
     * missing from [shots] stays in the store. On the legacy backend the list
     * is the file, as before P3a. True when it landed.
     */
    suspend fun save(shots: List<StoredShot>): Boolean {
        // The legacy backend's floor is checked at the call: a save that waits
        // out a load would otherwise land a snapshot from before it.
        val openAtCall = SafeFile.savesOpen(file) && !SafeFile.savesHeld(file)
        if (mode == Mode.LEGACY && !SaveFloor.admit(file, WHAT)) return false
        return withContext(NonCancellable) {
            lock.withLock {
                withContext(Dispatchers.IO) {
                    ensureOpenLocked()
                    when (mode) {
                        Mode.SQL -> upsertLocked(shots)
                        Mode.LEGACY -> if (openAtCall) legacySaveLocked(shots) else SaveFloor.admit(file, WHAT).let { false }
                        Mode.BLOCKED -> journalLocked(shots)
                        Mode.UNOPENED -> false
                    }
                }
            }
        }
    }

    /** [save] for a restore: every row given, past the cap too, and still no deletes. */
    suspend fun saveAll(shots: List<StoredShot>): Boolean = save(shots)

    /**
     * Remove [ids] from the store, with their `.series.gz`; never a `.json`
     * series, the old copy's. On the legacy backend the rows went with the
     * list's save, so only their series go. [forget] first takes them out of
     * the hidden rows at once.
     */
    suspend fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        forget(ids)
        withContext(NonCancellable) {
            lock.withLock {
                withContext(Dispatchers.IO) {
                    ensureOpenLocked()
                    when (mode) {
                        Mode.SQL -> {
                            db().writableDatabase.inTransaction { tables.delete(this, ids) }
                            for (id in ids) {
                                series.deleteGz(id)
                                stored.remove(id)
                                summaries.remove(id)
                                light.remove(id)
                                seen.remove(id)
                                onDisk.remove(id)
                            }
                            version++
                        }
                        Mode.LEGACY -> for (id in ids) {
                            series.deleteAll(id)
                            written.removeAll(listOf(series.gzFile(id).name, series.jsonFile(id).name, series.legacyFile(id).name))
                        }
                        else -> Unit
                    }
                    deleting.removeAll(ids.toSet())
                    version++
                }
            }
        }
    }

    /** Take [ids] out of the hidden rows now, before their delete lands. */
    fun forget(ids: Collection<String>) {
        deleting += ids
        version++
    }

    /** The restore marker's deletes: these shots' series, by name, the rows no longer listing them. */
    suspend fun deleteSeriesOf(ids: Collection<String>) = delete(ids)

    /** No-ops from P3a: saves never delete, so a restore has nothing to pause. */
    fun pauseDeletes() = Unit
    fun resumeDeletes() = Unit

    private fun upsertLocked(shots: List<StoredShot>): Boolean {
        val changed = shots.filter { row -> row.id !in deleting && !unchanged(row) }
        if (changed.isEmpty()) return true
        // Series first, so a row never names a file that isn't there.
        val curveless = HashSet<String>()
        for (row in changed) {
            if (row.samples.isEmpty() || row.id in onDisk) continue
            if (series.write(row.id, row.samples)) onDisk += row.id else curveless += row.id
        }
        unsavedCurves = (unsavedCurves - changed.map { it.id }.toSet()) + curveless
        // A curve only in memory isn't on disk: unknown, until a later save writes it.
        val prepared = changed.map { row ->
            row to summaryFor(row).let { if (row.id in curveless) it.copy(sampleCount = null) else it }
        }
        val ok = runCatching { writeRows(prepared) }.isSuccess || prepared.map { one ->
            // The batch failed (a full disk, say): each row alone, as it may fit, then the journal.
            runCatching { writeRows(listOf(one)) }.isSuccess || journalRow(one.first).let { false }
        }.all { it }
        helper?.handler?.quarantined?.let {
            // SQLite found the file damaged mid-session and its handler kept it aside.
            closeDb()
            lost("The shot history's database was damaged and has been kept aside.")
        }
        if (ok) maybeSnapshot()
        return ok && curveless.isEmpty()
    }

    private fun writeRows(rows: List<Pair<StoredShot, ShotSummary>>) {
        if (fault(MigrationFault.DISK_FULL) != null) throw android.database.sqlite.SQLiteFullException("disk full (test)")
        db().writableDatabase.inTransaction {
            for ((row, summary) in rows) {
                if (keepsHeavy(row)) tables.putLight(this, row, summary) else tables.putFull(this, row, summary)
            }
        }
        for ((row, summary) in rows) {
            stored[row.id] = row.stripped()
            summaries[row.id] = summary
            seen[row.id] = row
            if (!keepsHeavy(row)) light.remove(row.id)
            if (row.samples.isNotEmpty()) staleSummary.remove(row.id)
            journaled = journaled - row.id
        }
        version++
    }

    /** A light row (loaded without them) that still has none: its stored profile and brew series stay. */
    private fun keepsHeavy(row: StoredShot) = row.id in light && row.profile == null && row.brewSeries == null

    /** Whether [row] is as the store has it, its series included. */
    private fun unchanged(row: StoredShot): Boolean {
        if (row.samples.isNotEmpty() && (row.id !in onDisk || row.id in staleSummary)) return false
        if (seen[row.id] === row) return true
        val prev = stored[row.id] ?: return false
        val same = prev == row.stripped()
        if (same) seen[row.id] = row
        return same
    }

    /**
     * The summary to store with [row]. Worked out from the row when it holds
     * its series; a row whose series isn't in memory (loaded light, or before
     * its series loaded) keeps the one stored, so a save never turns a curve
     * into "no telemetry".
     */
    private fun summaryFor(row: StoredShot): ShotSummary {
        val prev = summaries[row.id]
        if (row.samples.isNotEmpty()) return summaryOf(row, json)
        if (prev != null && (keepsHeavy(row) || prev.sampleCount != 0u)) return prev
        return summaryOf(row, json)
    }

    private fun StoredShot.stripped(): StoredShot = if (samples.isEmpty()) this else copy(samples = emptyList())

    /** The store is out of reach: new rows go to the pending journal, their series to `.series.gz`. */
    private fun journalLocked(shots: List<StoredShot>): Boolean {
        val fresh = shots.filter { row -> seen[row.id] !== row }
        for (row in fresh) {
            if (row.samples.isNotEmpty() && row.id !in onDisk && series.write(row.id, row.samples)) onDisk += row.id
            journalRow(row)
            seen[row.id] = row
        }
        return false
    }

    /** Append [row] (without its series) to `history-pending.jsonl`. True when it's on disk. */
    private fun journalRow(row: StoredShot): Boolean = runCatching {
        val line = json.encodeToString(StoredShot.serializer(), row.copy(samples = emptyList())) + "\n"
        java.io.FileOutputStream(pendingFile, true).use { out ->
            out.write(line.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        journaled = journaled + row.id
        true
    }.getOrElse {
        persistLine("pending journal: ${it.message}")
        false
    }

    // ── Rows snapshot (SPEC §6) ──────────────────────────────────────────────

    /** After the first save each day: every row with its profile, gzip JSON lines, keeping the last one as `.bak`. */
    private fun maybeSnapshot() {
        val today = LocalDate.now()
        if (snapshotDay == today) return
        snapshotDay = today
        runCatching {
            val db = db().readableDatabase
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { gz ->
                db.rawQuery("SELECT row_json, profile_json, brew_series_json FROM shots ORDER BY completed_at DESC, id DESC", null).use { c ->
                    while (c.moveToNext()) {
                        val line = JsonObject(
                            buildMap {
                                put("row", json.parseToJsonElement(c.getString(0)))
                                if (!c.isNull(1)) put("profile", json.parseToJsonElement(c.getString(1)))
                                if (!c.isNull(2)) put("brewSeries", json.parseToJsonElement(c.getString(2)))
                            },
                        ).toString()
                        gz.write((line + "\n").toByteArray(Charsets.UTF_8))
                    }
                }
            }
            SafeFile.writeBytes(snapshotFile, out.toByteArray(), keepBackup = true)
        }.onFailure { persistLine("rows snapshot: ${it.message}") }
    }

    /** The rows a snapshot holds, with their profiles and brew series, and when it was written. */
    private fun readSnapshot(): Pair<Long, List<Pair<StoredShot, ShotSummary?>>>? = runCatching {
        val f = snapshotFile.takeIf { it.exists() } ?: SafeFile.backupOf(snapshotFile).takeIf { it.exists() } ?: return null
        val text = GZIPInputStream(f.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        val rows = text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val obj = json.parseToJsonElement(line).jsonObject
            val row = tables.decodeRow(obj.getValue("row").toString())
            val profile = obj["profile"]?.jsonObject
            val brew = obj["brewSeries"]?.let { json.decodeFromJsonElement(coffee.crema.core.BrewSeries.serializer(), it) }
            row.shot.copy(profile = profile, brewSeries = brew) to row.summary
        }.toList()
        f.lastModified() to rows
    }.getOrNull()

    // ── A lost store: the user's choices (SPEC §6) ────────────────────────────

    private fun choices(): LostChoices {
        val snap = readSnapshot()
        val old = runCatching {
            if (!file.exists()) null else file.lastModified() to json.decodeFromString(ListSerializer(StoredShot.serializer()), file.readText()).size
        }.getOrNull()
        return LostChoices(snap?.first, snap?.second?.size, old?.first, old?.second)
    }

    /** Lost: start a new, empty store. The old copy stays, and isn't merged in as late rows. */
    suspend fun startEmpty(): HistoryLoad = rebuild { _ -> }

    /** Lost: rebuild the store from the rows snapshot. */
    suspend fun restoreSnapshot(): HistoryLoad = rebuild { db ->
        val snap = readSnapshot() ?: throw MigrationFailed("The rows snapshot couldn't be read")
        for ((row, summary) in snap.second) {
            tables.putFull(db, row, summary ?: summaryOf(row.copy(samples = (series.read(row.id) as? SeriesRead.Found)?.samples ?: emptyList()), json))
        }
    }

    /** Lost: copy the old copy again, as the first migration did. */
    suspend fun recoverOldCopy(): HistoryLoad = withContext(Dispatchers.IO) {
        lock.withLock {
            closeDb()
            eraseStore(markerFile)
            mode = Mode.UNOPENED
            health = HistoryHealth.LOADING
            lostChoices = null
            try {
                migrateOrThrow()
                writeMarker(HistoryMarker(STATE_DONE, atMs = now()))
                openDone()
            } catch (e: Exception) {
                lost(e.message ?: "The old copy couldn't be copied")
            }
            if (mode == Mode.SQL) sqlLoad() else HistoryLoad(emptyList(), 0, health)
        }
    }

    /** A new store from [fill], marked done with the old copy as its source, so step 8 doesn't bring the old copy back. */
    private suspend fun rebuild(fill: (SQLiteDatabase) -> Unit): HistoryLoad = withContext(Dispatchers.IO) {
        lock.withLock {
            closeDb()
            // The damaged file is kept aside already; this one is the empty replacement.
            context.deleteDatabase(NEXT_DB_NAME)
            try {
                val bytes = runCatching { if (file.exists()) file.readBytes() else null }.getOrNull()
                val ids = bytes?.let { b ->
                    runCatching { json.decodeFromString(ListSerializer(StoredShot.serializer()), b.toString(Charsets.UTF_8)).map { it.id } }.getOrNull()
                }.orEmpty()
                val db = db().writableDatabase
                db.inTransaction {
                    tables.clear(this)
                    fill(this)
                    tables.putMeta(this, TABLE_META_KEY, json.encodeToString(MigrationRecord.serializer(), MigrationRecord(STATE_DONE, bytes?.let(::sha256) ?: "", ids)))
                }
                closeDb()
                writeMarker(HistoryMarker(STATE_DONE, atMs = now()))
                mode = Mode.UNOPENED
                lostChoices = null
                openDone()
            } catch (e: Exception) {
                lost(e.message ?: "The history couldn't be rebuilt")
            }
            if (mode == Mode.SQL) sqlLoad() else HistoryLoad(emptyList(), 0, health)
        }
    }

    // ── Legacy backend: shots.json, as before P3a ─────────────────────────────

    private fun legacyLoad(): List<StoredShot> {
        val outcome = loadStore(file, WHAT, open = false) {
            json.decodeFromString(ListSerializer(StoredShot.serializer()), it)
        }
        return when (outcome) {
            is SafeLoad.Loaded -> outcome.value.newestFirst()
            SafeLoad.Absent, is SafeLoad.Corrupt, is SafeLoad.Unavailable -> emptyList()
        }
    }

    private suspend fun legacySaveLocked(shots: List<StoredShot>): Boolean {
        for (shot in shots) {
            val name = series.gzFile(shot.id).name
            if (shot.samples.isEmpty() || name in written) continue
            if (series.write(shot.id, shot.samples)) written += name
        }
        // Only a shot whose series file is written goes without its samples.
        return saveStore(file, WHAT) {
            json.encodeToString(
                ListSerializer(StoredShot.serializer()),
                shots.map { if (it.samples.isNotEmpty() && series.gzFile(it.id).name in written) it.copy(samples = emptyList()) else it },
            )
        }
    }

    // ── Erase ────────────────────────────────────────────────────────────────

    /** Delete everything the history keeps (Erase all data): `shots.json` and its copies, the series,
     *  `shots.db` with its `-wal` and `-shm` and any kept-aside copy, the marker, the snapshot and the journal. */
    suspend fun erase() {
        lock.withLock {
            withContext(Dispatchers.IO) {
                closeDb()
                eraseStore(file)
                seriesDir.listFiles()?.forEach { eraseStore(it) }
                runCatching { context.deleteDatabase(NEXT_DB_NAME) }
                dbFile.parentFile?.listFiles { f -> f.name.startsWith(NEXT_DB_NAME + QuarantineHandler.CORRUPT) }?.forEach { it.delete() }
                NEXT_FILES.forEach { eraseStore(File(context.filesDir, it)) }
                Stores.forget(dbFile)
                written.clear()
                stored.clear(); summaries.clear(); light.clear(); seen.clear(); onDisk.clear(); deleting.clear(); staleSummary.clear(); pendingHidden.clear()
                version++
                unreadableSeries = emptySet()
                unsavedCurves = emptySet()
                journaled = emptySet()
                lostChoices = null
                snapshotDay = null
                // The next save starts a new, empty store.
                mode = Mode.UNOPENED
                health = HistoryHealth.LOADING
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun db(): HistoryDb = helper ?: synchronized(this) {
        helper ?: HistoryDb(context, NEXT_DB_NAME).also { helper = it }
    }

    private fun closeDb() {
        val h = helper ?: return
        helper = null
        runCatching { h.close() }
    }

    private fun readMarker(): HistoryMarker? =
        when (val m = SafeFile.peek(markerFile) { json.decodeFromString(HistoryMarker.serializer(), it) }) {
            is SafeLoad.Loaded -> m.value
            else -> null
        }

    private fun writeMarker(marker: HistoryMarker) {
        SafeFile.write(markerFile, json.encodeToString(HistoryMarker.serializer(), marker))
    }

    private fun decodeRecord(text: String?): MigrationRecord? =
        text?.let { runCatching { json.decodeFromString(MigrationRecord.serializer(), it) }.getOrNull() }

    private fun freeSpace(): Long = freeSpaceForTest?.invoke() ?: context.filesDir.usableSpace

    private fun now() = System.currentTimeMillis()

    private fun fault(f: MigrationFault): MigrationFault? = f.takeIf { it in faults }

    private fun persistLine(line: String) {
        runCatching { coffee.crema.diag.DiagLog.add("history: $line") }
    }

    private fun splitView(all: List<StoredShot>): Pair<List<StoredShot>, List<StoredShot>> {
        var unpinned = 0
        val view = ArrayList<StoredShot>()
        val hidden = ArrayList<StoredShot>()
        for (row in all) {
            if (row.pinned || unpinned++ < MAX_SHOTS) view += row else hidden += row
        }
        return view to hidden
    }

    companion object {
        /**
         * A shot's series file name: [id] with every byte outside
         * `[A-Za-z0-9.-]` (`_` included) written as `_XX` (UTF-8, upper hex),
         * so two ids never share a file. A UUID id keeps its name.
         */
        internal fun seriesFileName(id: String): String = buildString {
            for (b in id.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                if (b >= 0 && (c.isLetterOrDigit() && c.code < 128 || c == '.' || c == '-')) append(c)
                else append('_').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
            append(".json")
        }
        private const val HEX = "0123456789ABCDEF"

        /** Newest first, ties by id descending: the one order a reload keeps (SPEC §5). */
        val ORDER: Comparator<StoredShot> = compareByDescending<StoredShot> { it.completedAtMs }.thenByDescending { it.id }

        /** Tests set this to hold [loadSeries] until they complete it. Null in the app. */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var holdSeriesLoad: kotlinx.coroutines.Deferred<Unit>? = null

        /** Tests set this to hold [loadAll] (the open and the move) until they complete it. Null in the app. */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var holdOpen: kotlinx.coroutines.Deferred<Unit>? = null

        /** Test seam: the steps that fail or stop, process-wide. */
        @androidx.annotation.VisibleForTesting
        internal val faults: MutableSet<MigrationFault> = Collections.synchronizedSet(mutableSetOf())

        /** Test seam: every migration fails, so the legacy backend runs (its own tests). */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var legacyOnlyForTest = false

        /** Test seam: the free space a migration sees. */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var freeSpaceForTest: (() -> Long)? = null

        /** The rows in view keep the newest N unpinned shots (matches the web cap). The store keeps all. */
        const val MAX_SHOTS = 300
        const val FILE_NAME = "shots.json"

        /** The SQLite store the rows move to (SPEC §3, P3a). */
        const val NEXT_DB_NAME = "shots.db"

        const val MARKER_NAME = "history-store.json"
        const val SNAPSHOT_NAME = "history-rows-snapshot.jsonl.gz"
        const val PENDING_NAME = "history-pending.jsonl"

        /** The store marker, rows snapshot and pending journal (SPEC §1 and §6), for [erase] too. */
        val NEXT_FILES = listOf(MARKER_NAME, SNAPSHOT_NAME, PENDING_NAME)
        private const val WHAT = "shot history"
        private const val SERIES_DIR = "shot-series"
        private const val TABLE_META_KEY = HistoryTables.META_MIGRATION
        private const val STATE_DONE = "done"
        private const val STATE_FAILED = "failed"
        private const val STATE_LOST = "lost"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
