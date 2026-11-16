package coffee.crema.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import coffee.crema.core.HistoryDbException
import coffee.crema.core.HistoryHeavy
import coffee.crema.core.HistoryLateImport
import coffee.crema.core.HistoryRowIn
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * From P6a nothing caps the rows (#310): the view the app shows holds every
 * row the store holds. Only the legacy backend keeps its cap of
 * [LEGACY_MAX_SHOTS], as it has nowhere else to put a row ([viewCap]).
 * [hiddenRows] stays, empty once the view holds them all, for a row that
 * reaches the store some other way.
 *
 * From P4a every row is a [ShotRow]: no series, profile or brew series, with
 * the summary the store wrote. A load reads rows only. A shot's heavy parts
 * come through [loadShot] when something needs them (the last few kept in
 * memory), and go in through [stage]: the next [save] writes them with the
 * row. A part a save doesn't carry stays as stored.
 *
 * If the migration can't finish, the app runs on the legacy backend: today's
 * `shots.json`, with its cap, retried at the next two launches and then at most
 * once a day.
 *
 * From #310 A5 (SQLite SPEC §11 stage 2), when [stage2Enabled], the store's
 * first open runs the flip ([HistoryFlip]): a verified copy of the rows,
 * `history-sqlite/history.db`, becomes the truth, and `shots.db` the old
 * copy, never written again. The memory above stays as it is; the rows load
 * from, and write to, `history.db` ([HistoryAuthority]), and an older
 * build's writes to the P3 stores come in as late rows ([HistoryLate]).
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

/** A line of `history-pending.jsonl`: a row to put, or an id to delete, applied in order. */
@Serializable
internal data class JournalEntry(
    val put: StoredShot? = null,
    val delete: String? = null,
    /** A profile for [put]'s row only if the store has none ([flushHidden]'s fill): its brew series stays. */
    val fillProfile: JsonObject? = null,
) {
    val id: String? get() = put?.id ?: delete
}

/** What a load gave: the rows in view, newest first, and how many are hidden past the cap (none from P6a). */
data class HistoryLoad(val view: List<ShotRow>, val hiddenCount: Int, val health: HistoryHealth)

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

/** A row or its curve couldn't be read just now: an I/O error, which says nothing about it. */
class HistoryReadException(message: String) : java.io.IOException(message)

/** The process died here (a test's stand-in for a kill). Never caught by the store. */
@androidx.annotation.VisibleForTesting
class SimulatedCrash(at: String) : Error("simulated crash at $at")

class HistoryStore(
    private val context: Context,
    private val json: Json,
    /** The build's `versionCode`: the stage-1 index runs a full diff at the first boot of each (#310 A1). */
    indexVersionCode: Long = coffee.crema.BuildConfig.VERSION_CODE.toLong(),
) {
    private val file get() = File(context.filesDir, FILE_NAME)
    private val markerFile get() = File(context.filesDir, MARKER_NAME)
    private val snapshotFile get() = File(context.filesDir, SNAPSHOT_NAME)
    /** The pending journal: P3a's `history-pending.jsonl`, or after the flip stage 2's `history-sqlite/pending.jsonl` (#310 A5). */
    private val pendingFile get() = if (onStage2) stage2.pending else File(context.filesDir, PENDING_NAME)
    private val dbFile: File
        get() = runCatching { context.getDatabasePath(NEXT_DB_NAME) }.getOrNull()
            ?: File(context.filesDir.parentFile, "databases/$NEXT_DB_NAME")
    private val seriesDir get() = File(context.filesDir, SERIES_DIR)
    private val series = SeriesFiles(File(context.filesDir, SERIES_DIR), json)

    /** `history-index.db` (#310 A1): the hook every write to `shots` goes through, and its full diff. */
    private val index: HistoryIndex = HistoryIndex(File(context.filesDir, SQLITE_DIR), { tables }, indexVersionCode) { refreshReads() }
    private val tables = HistoryTables(json, index)

    // ── Stage 2 (#310 A5, SQLite SPEC §11): `history.db` is the truth after the flip ──

    private val stage2 = Stage2Files(context.filesDir, json)

    /** `history.db` while the rows are in it this session ([Mode.AUTH]). */
    @Volatile private var auth: HistoryAuthority? = null

    /** The marker says `done`: the rows are `history.db`'s, and `shots.db` is the old copy, never written. */
    @Volatile private var onStage2 = false

    /** The open found `history.db` written by a newer build (`min_reader_v` or `min_writer_v` above ours): "update the app". */
    @Volatile var tooNew = false
        private set

    /** Why this session's flip didn't pass, if it ran and failed: Settings shows it. */
    @Volatile var flipError: String? = null
        private set

    /** The flip runs at most once per store: at its first open (SPEC §11, M2). */
    private var flipTried = false

    /** Steps 8 and 9 after the flip: late rows an older build wrote to the P3 stores. */
    private val late by lazy { HistoryLate(context.filesDir, dbFile, tables, series, json) }

    /** Loads, saves and deletes run on IO and may overlap; one at a time. */
    private val lock = Mutex()

    /** [SQL]: the rows are `shots.db`'s (P3a, stage 1). [AUTH]: `history.db`'s (stage 2, #310 A5). */
    private enum class Mode { UNOPENED, SQL, AUTH, LEGACY, BLOCKED }

    /** The rows are in a SQLite store this session, `shots.db` or `history.db`: the store's memory holds every row. */
    private val inSqlite: Boolean get() = mode == Mode.SQL || mode == Mode.AUTH

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

    // ── The store's memory of what it wrote ──────────────────────────────────

    /**
     * Each row as the store holds it, light, with the store's own summary, by
     * id: the save's diff, the hidden rows, and (legacy) the rows of
     * `shots.json`. Nothing here holds a series, a profile or a brew series.
     */
    private val stored = ConcurrentHashMap<String, ShotRow>()

    /** Rows whose `profile_json` is set: a pull fills a profile only into a row that has none. */
    private val withProfile: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Shots made or brought here whole ([stage]) whose heavy parts the next
     * save writes: a capture, a logged brew, a pull, an import, a restore, a
     * backfill. A part a staged shot lacks stays as the store holds it.
     */
    private val staged = ConcurrentHashMap<String, StoredShot>()

    /** The staged shot each journaled line came from (the store out of reach), so a save doesn't journal it again. */
    private val journaledStage = ConcurrentHashMap<String, StoredShot>()

    /** The heavy parts of the last [RECENT_SHOTS] shots read whole ([loadShot]), least recent first. */
    private val recent = object : LinkedHashMap<String, Heavy>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Heavy>?): Boolean = size > RECENT_SHOTS
    }

    private class Heavy(val samples: List<TelemetrySample>, val profile: JsonObject?, val brewSeries: coffee.crema.core.BrewSeries?)

    /** The last object seen for each row in a save: the same object is unchanged. */
    private val seen = ConcurrentHashMap<String, ShotRow>()

    /** Ids whose series is on disk, in any of its files. */
    private val onDisk: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Rows whose stored summary is wrong now (a curve its read didn't find, S4; a count that read back otherwise): the next save writes it. */
    private val dirtySummary: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Rows a delete has taken out of the store, or is about to. */
    private val deleting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Bumped on every change to [stored], [latest] or [deleting], after the
     * change: [hiddenRows]' and [syncRows]' caches, and [rowsVersion].
     */
    private val versionFlow = MutableStateFlow(0L)
    private val version: Long get() = versionFlow.value
    private fun bumpVersion() = versionFlow.update { it + 1 }
    @Volatile private var hiddenCache: Triple<Long, Set<String>, List<ShotRow>>? = null

    /**
     * Rows handed to the store that it hasn't written yet, by id (#310 A3a):
     * each [save]'s rows that differ from what it last saw, taken at the call
     * ([accept]), and each [updateHidden] change. An entry goes once a row
     * equal to it in every field, its summary too (`remove(key, value)` uses
     * `equals`, not identity), lands in [stored] (written, or found
     * unchanged), or its row is deleted. A row that only reached the pending journal stays: the store
     * holds it there. So a reader of the store ([syncRow], [syncRows]) sees an
     * edit as soon as its save is called, not when its write lands, and never
     * misses a row on its way from here to [stored]: [stored] is written
     * first and the entry goes after, and readers read here first.
     */
    private val latest = ConcurrentHashMap<String, ShotRow>()

    /** [syncRows]' last answer and the [version] it was read at. */
    @Volatile private var syncCache: Pair<Long, List<ShotRow>>? = null

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

    /** Shots whose series file was unreadable when read (kept aside and reported). */
    @Volatile var unreadableSeries: Set<String> = emptySet()
        private set

    // ── The legacy backend (shots.json), as before P3a ───────────────────────

    /** Series files that already hold the series (legacy). */
    private val written = mutableSetOf<String>()

    /** The legacy backend's profile and brew series per row: `shots.json` carries them inline. */
    private val legacyHeavy = ConcurrentHashMap<String, Pair<JsonObject?, coffee.crema.core.BrewSeries?>>()

    // ── Open, migrate, load ──────────────────────────────────────────────────

    /** Whether the rows are in SQLite this session. */
    val usesSql: Boolean get() = inSqlite

    /** Whether the rows are in `history.db` this session (#310 A5). */
    val onHistoryDb: Boolean get() = mode == Mode.AUTH

    /** The cap the store keeps, for a restore's plan: none from P3a, or legacy's. */
    val capacity: Int? get() = if (mode == Mode.LEGACY) LEGACY_MAX_SHOTS else null

    /**
     * How many unpinned rows the view may hold (#310 P6a): none on `shots.db`,
     * or anywhere but the legacy backend, whose list is the file.
     */
    val viewCap: Int? get() = capacity

    /** The rows' load, as the restore and the backups read it. */
    val storeHealth: StoreHealth
        get() = when (health) {
            HistoryHealth.LOADING -> StoreHealth.LOADING
            HistoryHealth.OK -> StoreHealth.OK
            HistoryHealth.LEGACY -> Stores.health(file)
            HistoryHealth.UNAVAILABLE -> StoreHealth.UNAVAILABLE
            HistoryHealth.LOST -> StoreHealth.DAMAGED
        }

    /**
     * Every row (view and hidden), each loaded whole: for tests and small tools.
     * A row that won't read comes light, so nothing in the app may use this:
     * a light row there would read as "no curve" (#419 N7). Use [loadFull].
     */
    suspend fun load(): List<StoredShot> {
        val loaded = loadAll()
        val rows = loaded.view + hiddenRows(loaded.view.mapTo(HashSet()) { it.id })
        return rows.map { row -> runCatching { loadShot(row.id) }.getOrNull()?.let(row::joined) ?: row.light() }.sortedWith(ORDER)
    }

    /** The rows (view only, the legacy backend's whole list), without their series. Opens the store first. */
    suspend fun loadRows(): List<ShotRow> = loadAll().view

    /**
     * Open the store, migrating on the first launch, and give the rows in view.
     * Rows past the cap stay in the store, hidden. On the legacy backend the
     * rows are `shots.json`'s, and saves stay refused until [openSaves].
     */
    suspend fun loadAll(): HistoryLoad = withContext(Dispatchers.IO) {
        holdOpen?.await()
        var flip = false
        val first = lock.withLock {
            guarded {
                openLocked(force = false)
                flip = flipDue()
                if (flip) HistoryLoad(emptyList(), 0, health) else loadOpened()
            }.also { refreshReads() }
        }
        if (!flip) return@withContext first
        // The flip (#310 A5): its own steps take the lock, a batch at a time, so a
        // capture still lands in shots.db meanwhile (M9). Then the rows, from
        // whichever store is the truth now.
        runFlip()
        lock.withLock { guarded { loadOpened() }.also { refreshReads() } }
    }

    private fun loadOpened(): HistoryLoad = when (mode) {
        Mode.SQL -> sqlLoad()
        Mode.AUTH -> authLoad()
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
            closeAuth()
            mode = Mode.UNOPENED
            guarded {
                openLocked(force = true)
                loadOpened()
            }.also { refreshReads() }
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
        // Stage 2 first (#310 A5): once the marker says done, shots.db is the old copy, and P3a's
        // open would write it (its late rows, its journal). It is never opened through P3a's helper.
        if (stage2Enabled) {
            when (val s = stage2.state()) {
                is Stage2State.Done -> {
                    onStage2 = true
                    if (s.rebuilt) rebuildStage2Marker(s.generation)
                    openAuth(s.generation)
                    return
                }
                // An I/O error says nothing: neither store is opened, so neither is written.
                is Stage2State.Unreadable -> {
                    unavailable(s.error)
                    return
                }
                is Stage2State.NotDone -> stage2Marker = s.marker
            }
        }
        when (val read = readMarkerOutcome()) {
            // An I/O error says nothing about the marker: never read it as "not migrated".
            is MarkerRead.Unreadable -> unavailable("Couldn't read the shot history's marker: ${read.error}")
            is MarkerRead.Found -> when (read.marker.state) {
                STATE_LOST -> lost(null)
                STATE_DONE -> openDone()
                else -> notDone(read.marker, force, markerDamaged = false)
            }
            MarkerRead.Damaged -> notDone(null, force, markerDamaged = true)
            MarkerRead.Absent -> notDone(null, force, markerDamaged = false)
        }
    }

    /**
     * No `done` marker. Migrate only when the marker is absent or says
     * `failed`, and no store is there or one opened and its own flag reads
     * "not done". A store that won't open or read is unavailable (or lost
     * after a quarantine), never cleared (SPEC §6 step 1). A damaged marker
     * may have said `done` or `lost`: it never leads to a migration.
     */
    private fun notDone(marker: HistoryMarker?, force: Boolean, markerDamaged: Boolean) {
        if (dbFile.exists()) {
            when (val probe = probeStore()) {
                StoreProbe.Done -> {
                    // A commit whose marker a kill cut off, or a marker that was lost: the store's own flag says done.
                    writeMarker(HistoryMarker(STATE_DONE, atMs = now()))
                    openDone()
                    return
                }
                StoreProbe.Quarantined -> {
                    lost("The shot history's database was damaged and has been kept aside.")
                    return
                }
                is StoreProbe.Failed -> {
                    unavailable(probe.why)
                    return
                }
                is StoreProbe.NotDone -> when {
                    markerDamaged -> {
                        closeDb()
                        lost("The shot history's marker is damaged and its database doesn't hold a finished copy.")
                        return
                    }
                    // Rows with no `done` flag that reads: a migration commits both
                    // together, so its `meta` is damaged. Never copied over (#408 F1).
                    probe.holdsRows -> {
                        closeDb()
                        lost("The shot history's database holds rows but doesn't say it holds a finished copy.")
                        return
                    }
                    else -> Unit
                }
            }
        } else if (markerDamaged) {
            // The marker may have said done: a missing store then is lost, not a first launch.
            lost("The shot history's marker is damaged and its database is missing.")
            return
        }
        if (!force && marker?.state == STATE_FAILED && now() < marker.nextTryAtMs) {
            lastError = marker.lastError
            toLegacy()
            return
        }
        migrate(marker)
    }

    private sealed interface StoreProbe {
        data object Done : StoreProbe
        /** Its flag isn't `done`, or doesn't read; [holdsRows] when it has rows anyway. */
        data class NotDone(val holdsRows: Boolean) : StoreProbe
        data object Quarantined : StoreProbe
        data class Failed(val why: String) : StoreProbe
    }

    /** What an existing `shots.db` says of itself. Opens it, and leaves it as it is. */
    private fun probeStore(): StoreProbe {
        val h = db()
        val db = try {
            h.writableDatabase
        } catch (e: HistoryTooNewException) {
            closeDb()
            return StoreProbe.Failed(e.message ?: "update the app")
        } catch (e: Exception) {
            val quarantined = h.handler.quarantined != null
            closeDb()
            return if (quarantined) StoreProbe.Quarantined else StoreProbe.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (h.handler.quarantined != null) {
            closeDb()
            return StoreProbe.Quarantined
        }
        return try {
            val record = decodeRecord(tables.meta(db, TABLE_META_KEY))
            if (record?.state == STATE_DONE) {
                StoreProbe.Done
            } else {
                StoreProbe.NotDone(holdsRows = db.rawQuery("SELECT 1 FROM shots LIMIT 1", null).use { it.moveToFirst() })
            }
        } catch (e: Exception) {
            closeDb()
            StoreProbe.Failed(e.message ?: e.javaClass.simpleName)
        }
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
            // It stays (the lost marker says what it is); deleting it could take a -wal the move left (N5).
            closeDb()
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
        val m = (readMarkerOutcome() as? MarkerRead.Found)?.marker
        lastError = why ?: m?.lastError
        if (m?.state != STATE_LOST) runCatching { writeMarker(HistoryMarker(STATE_LOST, lastError = lastError, atMs = now())) }
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
        // Clear: a restart never builds on a partial copy. Only ever ours, and
        // only when it holds no rows; anything else is kept aside (M1).
        clearNewStore()
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
            // The source as read: a recovered .bak or .tmp when the main file was moved aside (N4).
            val size = listOf(file, SafeFile.backupOf(file), File(file.path + ".tmp")).maxOf { it.length() }
            // 1.5× the source (SPEC §6 step 3), plus the WAL, which holds the one transaction's rows until its checkpoint,
            // plus step 6's `.series.gz` copies: at most the `.json` each is read from, and for inline samples
            // at most `shots.json` again (#408 F2).
            val seriesBound = source.sumOf { r -> if (r.samples.isEmpty()) series.sourceLength(r.id) else 0L } +
                if (source.any { it.samples.isNotEmpty() }) size else 0L
            val need = size * 5 / 2 + seriesBound
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
        // The gz files this run makes, so a failure takes them back (#408 F2).
        val created = ArrayList<String>()
        try {
            // Every series read to its own `.series.gz` first, so no row names a file
            // that isn't there, and an old build's prune of a `.json` (the old copy,
            // left as it is) can't take a migrated curve (#408 M2).
            var seriesWritten = 0
            val firstCurve = prepared.firstOrNull { !it.samples.isNullOrEmpty() }
            for (p in prepared) {
                val samples = p.samples
                if (samples.isNullOrEmpty()) continue
                if (seriesWritten > 0 && fault(MigrationFault.CRASH_IN_SERIES) != null) throw SimulatedCrash("series")
                val curve = if (fault(MigrationFault.ALTER_SERIES) != null && p === firstCurve) samples.drop(1) else samples
                if (!series.gzFile(p.row.id).exists()) created += p.row.id
                if (!series.write(p.row.id, curve)) throw MigrationFailed("Couldn't write a shot's telemetry")
                seriesWritten += 1
            }
            val hash = sha256(runCatching { file.readBytes() }.getOrDefault(ByteArray(0)))
            val db = db().writableDatabase
            tables.write(db) {
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
                verify(db, prepared)?.let { throw MigrationFailed("Verify failed: $it") }
                tables.putMeta(
                    db,
                    TABLE_META_KEY,
                    json.encodeToString(
                        MigrationRecord.serializer(),
                        MigrationRecord(STATE_DONE, hash, firsts.keys.toList(), duplicates = duplicates),
                    ),
                )
            }
        } catch (e: Throwable) {
            // A failed move holds no disk (SPEC §6 Failure 1): the copies it made go. Each one's
            // source is still in the old copy. A crash leaves them, and the next run writes them again.
            if (e !is SimulatedCrash) for (id in created) series.deleteGz(id)
            throw e
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
        // The copy that failed: its transaction didn't commit, so this deletes an empty file.
        runCatching { clearNewStore() }.onFailure { persistLine("clear after a failed move: ${it.message}") }
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
                // A row that couldn't come in now (its series write failed) is looked at again next launch (S2).
                var incomplete = false
                tables.write(db) {
                    for (row in blob) {
                        if (row.id in known || row.id in considered) continue
                        if (row.id in heldIds || (row.visualizerId != null && row.visualizerId in heldRemote)) {
                            considered += row.id
                            continue
                        }
                        val samples = when {
                            row.samples.isNotEmpty() -> row.samples
                            else -> when (val read = series.read(row.id)) {
                                is SeriesRead.Found -> read.samples
                                is SeriesRead.Unavailable -> {
                                    incomplete = true
                                    continue
                                }
                                else -> emptyList()
                            }
                        }
                        // Its own gz, as at the migration (M2): the old build may prune the .json later.
                        if (samples.isNotEmpty() && !series.write(row.id, samples)) {
                            incomplete = true
                            continue
                        }
                        tables.putFull(this, row.copy(samples = emptyList()), summaryOf(row.copy(samples = samples), json))
                        considered += row.id
                        if (samples.isEmpty() && row.brewMethod == null) late += row.id
                    }
                }
                next = next.copy(
                    sourceHash = if (incomplete) record.sourceHash else hash,
                    sourceIds = record.sourceIds + considered,
                    lateIds = (record.lateIds + late).distinct(),
                )
            }
        }
        // Step 9: a late row's series may have landed since.
        if (next.lateIds.isNotEmpty()) {
            val still = mutableListOf<String>()
            tables.write(db) {
                for (id in next.lateIds) {
                    val row = tables.full(this, id) ?: continue
                    val read = series.read(id)
                    if (read is SeriesRead.Found && series.write(id, read.samples)) {
                        tables.putFull(this, row.shot, summaryOf(row.shot.copy(samples = read.samples), json))
                    } else {
                        still += id
                    }
                }
            }
            next = next.copy(lateIds = still)
        }
        if (next != record) db.inTransaction { tables.putMeta(this, TABLE_META_KEY, json.encodeToString(MigrationRecord.serializer(), next)) }
    }

    /** Rows that went to the pending journal while the store was out of reach, merged now it's back, in order. */
    private fun mergePending(db: SQLiteDatabase) {
        if (!pendingFile.exists()) return
        val entries = journalEntries()
        tables.write(db) {
            for (e in entries) {
                e.delete?.let { id ->
                    tables.delete(this, listOf(id))
                    series.deleteGz(id)
                }
                val row = e.put ?: continue
                val held = tables.full(this, row.id)
                tables.upsert(this, row.copy(samples = emptyList()), journaledSummary(row, held))
                // A fill that waited out a full disk: only into a row that still has no profile (#408 N8).
                e.fillProfile?.let { p -> if (tables.full(this, row.id)?.shot?.profile == null) tables.putProfile(this, row.id, p) }
            }
        }
        // Ours, not the old copy: it has landed in the store. A file that won't
        // go is emptied, so the next launch can't merge it over newer edits (N6).
        if (!pendingFile.delete()) runCatching { SafeFile.write(pendingFile, "", keepBackup = false) }
        journaled = emptySet()
    }

    /**
     * The summary a journal line's row goes in with. Its series is read: a curve
     * found counts as itself. Otherwise a light line (an edit: no profile or
     * brew series) keeps the summary the store holds, since a read that failed
     * says nothing about the curve. A whole line whose curve won't read, or that
     * the store says had one, is unknown, never "no curve" (#419 S1).
     */
    private fun journaledSummary(row: StoredShot, held: DbRow?): ShotSummary {
        val read = series.read(row.id)
        val brew = row.brewSeries ?: held?.shot?.brewSeries
        if (read is SeriesRead.Found) return summaryOf(row.copy(samples = read.samples, brewSeries = brew), json)
        val light = row.profile == null && row.brewSeries == null
        held?.summary?.let { if (light) return it }
        val base = summaryOf(row.copy(samples = emptyList(), brewSeries = brew), json)
        val namedCurve = held?.summary?.let { it.sampleCount != 0u } ?: false
        return if (read is SeriesRead.Absent && !namedCurve) base else base.copy(sampleCount = null)
    }

    /** The rows in view now and every row's memory, from `shots.db`. No series, profile or brew series is read. */
    private fun sqlLoad(): HistoryLoad {
        val db = db().readableDatabase
        val bad = mutableListOf<String>()
        val all = tables.lightRows(db, bad)
        return landRows(all, bad, tables.idsWithProfile(db), snapshotFile)
    }

    /** [all] (newest first) into memory: every row's, the profiles, and the snapshot's day. */
    private fun landRows(all: List<DbRow>, bad: List<String>, profiled: Set<String>, snapshot: File): HistoryLoad {
        unreadableRows = bad
        if (bad.isNotEmpty()) persistLine("${bad.size} rows couldn't be read and stay in the store as they are")
        // A row with no summary that reads: unknown, so it's neither manual nor "no telemetry".
        val rows = all.map { r -> r.shot.toRow(r.summary ?: unknownSeriesSummary(r.shot)) }
        seen.clear(); deleting.clear(); pendingHidden.clear(); dirtySummary.clear()
        synchronized(recent) { recent.clear() }
        // [staged] stays: a shot made before the rows landed still has its heavy parts to write.
        // The new rows replace the old with no gap: readers run outside the lock, and a
        // row missing for a moment would read as gone, or as having no curve (#419 M1).
        replaceRows(rows.associateBy { it.id })
        settleLatest()
        withProfile += profiled
        withProfile.retainAll(profiled)
        // A curve on disk is known from the summary; the file isn't read until it's wanted.
        for (r in rows) if (r.summarisedCurve) onDisk += r.id
        bumpVersion()
        snapshotDay = snapshot.takeIf { it.exists() }?.lastModified()?.let { localDateOf(it, ZoneId.systemDefault()) }
        val (view, hidden) = splitView(rows)
        return HistoryLoad(view, hidden.size, health)
    }

    /** [next] in [stored], in place: each row put, then the ones it lacks dropped. Never an empty moment. */
    private fun replaceRows(next: Map<String, ShotRow>) {
        stored.putAll(next)
        stored.keys.retainAll(next.keys)
        bumpVersion()
    }


    // ── Stage 2: the flip and history.db (#310 A5, SQLite SPEC §11) ──────────

    /** The stage-2 marker as this open read it, when it isn't `done`: a failed flip's backoff. */
    private var stage2Marker: Stage2Marker? = null

    /**
     * Whether this open runs the flip: stage 2 is on in this build, it hasn't
     * run in this store, the rows are in `shots.db` and it opened `ok`, none
     * waits in P3a's journal, and a failed flip's backoff has passed (the
     * next two launches, then at most daily).
     */
    private fun flipDue(): Boolean {
        if (!stage2Enabled || flipTried || mode != Mode.SQL || health != HistoryHealth.OK) return false
        flipTried = true
        if (journaled.isNotEmpty() || File(context.filesDir, PENDING_NAME).exists()) {
            flipError = "Rows wait in the pending journal: the move into SQLite waits for them."
            return false
        }
        val m = stage2Marker
        return !(m?.state == STAGE2_FAILED && now() < m.nextTryAtMs)
    }

    /** [HistoryFlip]'s way into this store: `shots.db` under the lock, the index's diff, and the switch. */
    private val flipHost = object : FlipHost {
        override suspend fun <T> locked(block: (SQLiteDatabase) -> T): T? = withContext(Dispatchers.IO) {
            lock.withLock { if (mode == Mode.SQL) block(db().readableDatabase) else null }
        }

        override suspend fun fullDiff(): IndexDiffReport? = syncIndex(force = true)

        override fun switchTo(outcome: FlipOutcome.Done) = switchToAuth(outcome)

        override fun freeSpace(): Long = this@HistoryStore.freeSpace()
    }

    /** The flip, once, at this store's first open. Never throws but a [SimulatedCrash]. */
    private suspend fun runFlip() {
        val flip = HistoryFlip(flipHost, stage2, tables, index, context.filesDir, dbFile, json)
        when (val out = flip.run()) {
            is FlipOutcome.Done -> {
                flipError = null
                persistLine("the history moved to ${stage2.db(out.generation).name} (${out.captured.size} shots captured meanwhile)")
            }
            is FlipOutcome.Failed -> lock.withLock {
                // A failure after the file said done (a done-record or marker write that threw): the
                // flip passed all the same, and shots.db must not take another write (rule 3).
                val done = flip.doneGeneration ?: (stage2.state() as? Stage2State.Done)?.generation
                if (done != null) {
                    persistLine("the flip failed after its copy was done (${out.why}): on to ${stage2.db(done).name}")
                    closeDb()
                    onStage2 = true
                    stage2Marker = null
                    rebuildStage2Marker(done)
                    openAuth(done)
                } else {
                    flipFailed(out.why)
                }
            }
        }
        flip.leftover?.let { persistLine("flip: $it") }
    }

    /** A flip that didn't pass (SPEC §11, Failure): `shots.db` stays the truth, and the marker keeps the backoff. */
    private fun flipFailed(why: String) {
        persistLine("the move to ${Stage2Files.DB_NAME} failed: $why")
        flipError = why
        val failures = (stage2Marker?.failures ?: 0) + 1
        val next = if (failures < 3) 0L else now() + DAY_MS
        val m = Stage2Marker(STAGE2_FAILED, failures = failures, nextTryAtMs = next, lastError = why, atMs = now())
        runCatching { stage2.writeMarker(m) }.onFailure { persistLine("stage 2 marker: ${it.message}") }
        stage2Marker = m
    }

    /**
     * The flip passed (under the lock, after the marker): the store moves to
     * `history.db`. Shots captured during the flip are read from `shots.db`
     * as committed, before its helper closes, and go in at once (M9), then
     * step 8 reads every id once more, whatever the prefilter says.
     */
    private fun switchToAuth(o: FlipOutcome.Done) {
        // Read before the helper closes. A read that fails leaves them to step 8, which reads
        // every id of shots.db at once below, and at each boot until it can.
        val captured = if (o.captured.isEmpty()) emptyMap() else runCatching { tables.rawFull(db().readableDatabase, o.captured) }.getOrElse { emptyMap() }
        closeDb()
        // From the marker on, nothing writes shots.db again (rule 3): whatever fails here leaves the
        // store on stage 2, unavailable at worst, never back on shots.db.
        onStage2 = true
        stage2Marker = null
        try {
            if (captured.isNotEmpty()) {
                val rows = captured.map { (_, r) -> HistoryRowIn(r.first, HistoryHeavy(r.second, r.third), pulled = true) }
                o.db.now { it.importLate(HistoryLateImport(rows, emptyList(), null, null)) }
            }
            attachAuth(o.generation, o.db, forceLate = true)
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Throwable) {
            closeAuth()
            runCatching { o.db.now { it.closeAll() } }
            unavailable("The shot history moved to SQLite and couldn't be opened just now: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** The marker said `done` for [generation]: open it, or say why not. Never flips, never opens `shots.db` through P3a's helper. */
    private fun openAuth(generation: Int) {
        closeDb()
        val f = stage2.db(generation)
        if (!f.exists()) {
            lostAuth("The shot history's database (${f.name}) is missing.")
            return
        }
        val db = try {
            HistoryDbBridge.openNow(f, HistoryDbBridge.options(coffee.crema.core.HistoryRole.AUTHORITY))
        } catch (e: HistoryDbException.TooNew) {
            tooNew = true
            unavailable("The shot history was written by a newer version of Crema: update the app.")
            return
        } catch (e: HistoryDbException) {
            when (e) {
                is HistoryDbException.Corrupt, is HistoryDbException.Foreign, is HistoryDbException.WrongRole -> {
                    // Damaged or not ours: renamed aside, never deleted, and lost until the user chooses.
                    val moved = stage2.quarantine(f)
                    lostAuth("The shot history's database was damaged and has been kept aside${if (moved) "" else " (it couldn't be moved)"}.")
                }
                is HistoryDbException.CantOpen -> if (f.exists()) unavailable(e.message ?: "the shot history's database wouldn't open") else lostAuth("The shot history's database is missing.")
                else -> unavailable(e.message ?: e.javaClass.simpleName)
            }
            return
        }
        val problem = runCatching {
            val info = db.now { it.info() }
            when {
                db.now { it.migrationState() } != STAGE2_DONE -> "doesn't hold a finished copy"
                info.generation.toInt() != generation -> "is generation ${info.generation}, not $generation"
                else -> null
            }
        }.getOrElse { "couldn't be read: ${it.message}" }
        if (problem != null) {
            runCatching { db.now { it.closeAll() } }
            lostAuth("The shot history's database $problem.")
            return
        }
        attachAuth(generation, db, forceLate = false)
    }

    /** [db] is open and checked: late rows, the stage-2 journal, then the store reads and writes it. */
    private fun attachAuth(generation: Int, db: HistoryDbBridge, forceLate: Boolean) {
        val source = SqliteHistorySource.open(db, json, details)
        val a = HistoryAuthority(generation, db, source, tables)
        tooNew = runCatching { !db.now { it.health() }.writable }.getOrDefault(false)
        auth = a
        mode = Mode.AUTH
        health = HistoryHealth.OK
        lastError = if (tooNew) "The shot history was written by a newer version of Crema: update the app to change it." else null
        lostChoices = null
        runCatching { lastLate = late.run(a, force = forceLate) }.onFailure { persistLine("late rows: ${it.message}") }
        runCatching { mergePendingAuth(a) }.onFailure { persistLine("pending journal: ${it.message}") }
    }

    /** Stage 2's journal, merged now `history.db` is back, in order, as P3a merges its own. */
    private fun mergePendingAuth(a: HistoryAuthority) {
        if (!stage2.pending.exists()) return
        for (e in journalEntries()) {
            e.delete?.let { id -> a.deleteNow(listOf(id)) }
            val row = e.put ?: continue
            val held = a.db.now { it.get(listOf(row.id)) }.firstOrNull()?.let { r ->
                runCatching { tables.decodeRow(r.rowJson) }.getOrNull()?.let { d ->
                    val (p, b) = a.heavy(row.id)
                    d.copy(shot = d.shot.copy(profile = p, brewSeries = b))
                }
            }
            val fill = e.fillProfile?.takeIf { held?.shot?.profile == null }
            a.write(listOf(row.copy(samples = emptyList()) to journaledSummary(row, held)), fill?.let { mapOf(row.id to it) }.orEmpty())
        }
        if (!stage2.pending.delete()) runCatching { SafeFile.write(stage2.pending, "", keepBackup = false) }
        journaled = emptySet()
    }

    /** The rows from `history.db`, as [sqlLoad] reads `shots.db`'s. */
    private fun authLoad(): HistoryLoad {
        val a = auth ?: return HistoryLoad(emptyList(), 0, health)
        val bad = mutableListOf<String>()
        val all = a.loadRows(bad)
        return landRows(all, bad, a.idsWithProfile(), stage2.snapshot)
    }

    private fun closeAuth() {
        val a = auth ?: return
        auth = null
        a.close()
    }

    /** Lost on stage 2 (SPEC §11): `history.db` gone, foreign or damaged. Nothing is written or migrated, and P3a's files are left alone. */
    private fun lostAuth(why: String) {
        closeAuth()
        mode = Mode.BLOCKED
        health = HistoryHealth.LOST
        lastError = why
        lostChoices = authChoices()
    }

    /** The marker or done-record was missing: both written again from what the files say (S9). */
    private fun rebuildStage2Marker(generation: Int) {
        runCatching {
            if ((stage2.readDone() as? coffee.crema.persist.SafeLoad.Loaded)?.value?.state != STAGE2_DONE) stage2.writeDone(generation, now())
            stage2.writeMarker(Stage2Marker(STAGE2_DONE, generation = generation, atMs = now()))
        }.onFailure { persistLine("stage 2 marker rebuild: ${it.message}") }
    }

    /** The app's view of a shot's details for the SQLite source: its series, by the store's rules. */
    private val details = object : ShotDetails {
        override fun full(row: ShotRow): StoredShot = loadFull(row)

        override fun hasCurve(row: ShotRow): Boolean = this@HistoryStore.hasCurve(row)

        override fun rowFor(shot: StoredShot): ShotRow = this@HistoryStore.rowFor(shot)

        override fun write(shots: List<StoredShot>): Int {
            var failed = 0
            for (s in shots) if (s.samples.isNotEmpty() && !series.write(s.id, s.samples)) failed++
            noteDetailsWritten()
            return failed
        }

        override fun delete(ids: Collection<String>) {
            for (id in ids) series.deleteGz(id)
        }
    }

    /** Series landed with no row write: the backup mark moves (REVIEW-466 note 1). */
    private fun noteDetailsWritten() {
        val a = auth?.takeIf { mode == Mode.AUTH } ?: return
        runCatching { a.db.now { it.noteDetailsWritten(now()) } }.onFailure { persistLine("details mark: ${it.message}") }
    }

    /**
     * Where a backup walks the shots (#310 A5, R1a): on `history.db`, its
     * export walk, which holds a delete made during it (S6), each row read
     * whole through [full]. Null on `shots.db` and the legacy backend: the
     * snapshot's own rows.
     */
    internal fun backupSource(full: (ShotRow) -> StoredShot): HistorySource? {
        val a = auth?.takeIf { mode == Mode.AUTH } ?: return null
        val src = a.source
        return object : HistorySource {
            override fun openWalk(): ShotWalk {
                val walk = src.openWalk()
                return object : ShotWalk by walk {
                    override fun full(row: ShotRow): StoredShot = full(row)
                }
            }

            override fun writeMark(): String? = src.writeMark()

            override suspend fun beginRestore(): ShotsRestore = src.beginRestore()
        }
    }

    /**
     * A restore's wipe on `history.db` (M3): of [named], only rows made at or
     * before [beginSeq] that the backup doesn't hold, read in pages by `rid`.
     * A shot made during the restore is never in it. On `shots.db` the named
     * rows, as P3a always did.
     */
    suspend fun restoreRemove(named: List<String>, backupIds: Set<String>, beginSeq: ULong?) {
        if (named.isEmpty()) return
        val a = auth?.takeIf { mode == Mode.AUTH }
        if (a == null || beginSeq == null) return delete(named)
        val want = named.filterNot { it in backupIds }.toHashSet()
        val wipe = ArrayList<String>()
        var after = Long.MIN_VALUE
        while (true) {
            val page = withContext(Dispatchers.IO) { a.db.now { it.idsCreatedBefore(beginSeq, after, 500u) } }
            if (page.isEmpty()) break
            page.mapNotNullTo(wipe) { r -> r.id.takeIf { it in want } }
            after = page.last().rid
            if (page.size < 500) break
        }
        delete(wipe)
    }

    /** A restore begins (M3): `history.db`'s `write_seq` now, or null on `shots.db`. */
    suspend fun restoreBegin(): ULong? = withContext(Dispatchers.IO) {
        auth?.takeIf { mode == Mode.AUTH }?.let { a -> runCatching { a.db.now { it.restoreBegin() } }.getOrNull() }
    }

    /** What a lost `history.db` can be rebuilt from: the rows snapshot, and the old copy (`shots.db`). */
    private fun authChoices(): LostChoices {
        val snap = listOf(stage2.snapshot, stage2.previousSnapshot).firstOrNull { it.exists() }?.let { f ->
            runCatching {
                val b = HistoryDbBridge.openNow(f, HistoryDbBridge.options(coffee.crema.core.HistoryRole.AUTHORITY))
                try {
                    f.lastModified() to b.now { it.count(HistoryDbBridge.ALL) }.toInt()
                } finally {
                    runCatching { b.now { it.closeAll() } }
                }
            }.getOrNull()
        }
        val old = runCatching { ReadOnlyShots.open(dbFile)?.use { dbFile.lastModified() to it.ids().size } }.getOrNull()
        return LostChoices(snap?.first, snap?.second, old?.first, old?.second)
    }

    /**
     * Lost on stage 2: a new `history-<n>.db` from [fill] (S9), never over
     * the damaged one. Its `migration_source` is every id the P3 stores hold
     * now and its late-row baseline is now, so step 8 brings only what an
     * older build writes after the choice, and "start empty" stays empty.
     * Then the done-record and the marker name it.
     */
    private suspend fun recoverAuth(fill: (HistoryDbBridge) -> Unit): HistoryLoad = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                closeAuth()
                val g = stage2.nextGeneration()
                val f = stage2.db(g)
                val ids = LinkedHashSet<String>()
                ReadOnlyShots.open(dbFile)?.use { ro ->
                    ids += ro.ids()
                    ro.meta(HistoryTables.META_MIGRATION)?.let { t -> runCatching { json.decodeFromString(MigrationRecord.serializer(), t) }.getOrNull() }?.let { ids += it.sourceIds }
                }
                val jsonBytes = runCatching { File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readBytes() }.getOrNull()
                jsonBytes?.let { b -> runCatching { json.decodeFromString(ListSerializer(StoredShot.serializer()), b.toString(Charsets.UTF_8)) }.getOrNull() }?.let { rows -> rows.mapTo(ids) { it.id } }
                val baseline = LateBaseline.now(context.filesDir, dbFile, jsonBytes?.let(LateBaseline::sha256))
                stage2.dir.mkdirs()
                val b = HistoryDbBridge.createRecoveryNow(
                    f,
                    HistoryDbBridge.options(coffee.crema.core.HistoryRole.AUTHORITY, generation = g.toUInt()),
                    coffee.crema.core.HistoryRecovery(ids.toList(), json.encodeToString(LateBaseline.serializer(), baseline)),
                )
                try {
                    fill(b)
                } finally {
                    runCatching { b.now { it.closeAll() } }
                }
                stage2.writeDone(g, now())
                stage2.writeMarker(Stage2Marker(STAGE2_DONE, generation = g, atMs = now()))
                mode = Mode.UNOPENED
                lostChoices = null
                openAuth(g)
            } catch (e: SimulatedCrash) {
                throw e
            } catch (e: Exception) {
                lostAuth(e.message ?: "The history couldn't be rebuilt")
            }
            if (mode == Mode.AUTH) authLoad() else HistoryLoad(emptyList(), 0, health)
        }
    }

    /** Rows and their heavy parts into a recovery file, a batch per transaction. */
    private fun fillFrom(b: HistoryDbBridge, rows: Sequence<HistoryRowIn>) {
        for (chunk in rows.chunked(250)) b.now { it.upsert(chunk) }
    }

    /** The rows a truth file (a snapshot) holds, with their heavy parts. */
    private fun rowsOf(f: File): List<HistoryRowIn> {
        val b = HistoryDbBridge.openNow(f, HistoryDbBridge.options(coffee.crema.core.HistoryRole.AUTHORITY))
        try {
            val out = ArrayList<HistoryRowIn>()
            var after = Long.MIN_VALUE
            while (true) {
                val page = b.now { it.idsCreatedBefore(ULong.MAX_VALUE, after, 500u) }
                if (page.isEmpty()) break
                val ids = page.map { it.id }
                val heavy = b.now { it.heavy(ids) }.associateBy { it.id }
                for (r in b.now { it.get(ids) }) out += HistoryRowIn(r.rowJson, heavy[r.id]?.let { HistoryHeavy(it.profileJson, it.brewSeriesJson) })
                after = page.last().rid
                if (page.size < 500) break
            }
            return out
        } finally {
            runCatching { b.now { it.closeAll() } }
        }
    }

    /** The rows snapshot, once a day after the first write, through the online backup API, keeping one previous copy. */
    private fun authSnapshot() {
        val a = auth ?: return
        val today = LocalDate.now()
        if (snapshotDay == today) return
        snapshotDay = today
        runCatching {
            val seq = a.db.now { it.writeSeq() }
            if (seq == snapshotSeq) return
            if (stage2.snapshot.exists()) {
                for (suffix in QuarantineHandler.SIBLINGS) File(stage2.previousSnapshot.path + suffix).delete()
                for (suffix in QuarantineHandler.SIBLINGS) {
                    val from = File(stage2.snapshot.path + suffix)
                    if (from.exists() && !from.renameTo(File(stage2.previousSnapshot.path + suffix))) error("couldn't keep the previous snapshot")
                }
            }
            a.db.now { it.copyTo(stage2.snapshot.path, coffee.crema.core.HistoryJournalMode.WAL) }
            snapshotSeq = seq
        }.onFailure { persistLine("rows snapshot: ${it.message}") }
    }

    /** This session's late-row step (steps 8 and 9): what it read and brought in. */
    @Volatile internal var lastLate: HistoryLate.Report? = null
        private set

    /** `history.db`'s `write_seq` at the last snapshot: one with nothing new is skipped. */
    @Volatile private var snapshotSeq: ULong? = null

    // ── Rows past the cap ────────────────────────────────────────────────────

    /** The rows the store holds that aren't in [viewIds]: past the cap, newest first. */
    // No app callers from #310 A3b (REVIEW-470 N3): every reader and edit goes through [rows], [row] and [update].
    @androidx.annotation.VisibleForTesting
    fun hiddenRows(viewIds: Set<String>): List<ShotRow> {
        if (!inSqlite) return emptyList()
        val cached = hiddenCache
        if (cached != null && cached.first == version && cached.second == viewIds) return cached.third
        val rows = stored.values.filter { it.id !in viewIds && it.id !in deleting }.sortedWith(ORDER)
        hiddenCache = Triple(version, viewIds, rows)
        return rows
    }

    // ── Disk space (#310 P6a) ────────────────────────────────────────────────

    /** The bytes free where the history lives (`filesDir`), for the storage notices. Unknown reads as plenty. */
    fun freeBytes(): Long = runCatching { freeSpace() }.getOrDefault(Long.MAX_VALUE)

    /**
     * About how many bytes deleting [ids] gives back: each row's columns and
     * its `.series.gz`, the files [delete] removes. On the legacy backend, its
     * series files. The old copy's `.json` series stay (rule 3), so they don't count.
     */
    suspend fun bytesOf(ids: Collection<String>): Long = withContext(Dispatchers.IO) {
        when (mode) {
            Mode.SQL -> runCatching { tables.bytesOf(db().readableDatabase, ids) }.getOrDefault(0L) + ids.sumOf { series.gzFile(it).length() }
            // history.db: about the row's own bytes and its curve's (the heavy parts aside).
            Mode.AUTH -> ids.sumOf { id -> series.gzFile(id).length() + (stored[id]?.let { tables.rowJson(it.light(), it.summary).length.toLong() } ?: 0L) }
            Mode.LEGACY -> ids.sumOf { series.gzFile(it).length() + series.jsonFile(it).length() + series.legacyFile(it).length() }
            else -> 0L
        }
    }

    /** Every row id the store holds (SQL), in view or not. */
    // No app callers from #310 A3b (REVIEW-470 N3): every reader and edit goes through [rows], [row] and [update].
    @androidx.annotation.VisibleForTesting
    fun storedIds(): Set<String> = if (inSqlite) stored.keys - deleting else emptySet()

    /** Every row the store holds (SQL). */
    // No app callers from #310 A3b (REVIEW-470 N3): every reader and edit goes through [rows], [row] and [update].
    @androidx.annotation.VisibleForTesting
    fun storedRows(): List<ShotRow> = if (inSqlite) stored.values.filter { it.id !in deleting } else emptyList()

    /** The stored row [id], hidden or not. */
    // No app callers from #310 A3b (REVIEW-470 N3): every reader and edit goes through [rows], [row] and [update].
    @androidx.annotation.VisibleForTesting
    fun storedRow(id: String): ShotRow? = if (inSqlite && id !in deleting) stored[id] else null

    /**
     * Whether shot [id] has a machine curve: one staged to be written, or one
     * its summary names whose file is there. A summary that says so with no
     * file, or an unknown one, is no curve, so a restore's fill or a pull's
     * backfill may put one back (S4).
     */
    fun hasCurve(id: String): Boolean {
        if (staged[id]?.samples?.isNotEmpty() == true) return true
        if (!inSqlite && mode != Mode.LEGACY) return false
        val row = stored[id] ?: return false
        // A curve the last read couldn't reach is still a curve: nothing may fill over it (#419 N5).
        return (row.summarisedCurve || id in unreadNow) && series.exists(id)
    }

    /** Rows whose series gave an I/O error at the legacy load: unknown, and not to be filled over. */
    private val unreadNow: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [hasCurve] of [row]. */
    fun hasCurve(row: ShotRow): Boolean = hasCurve(row.id)

    /** Whether shot [id] has its profile (the recipe the DE1 ran), staged or stored. */
    fun hasProfile(id: String): Boolean = staged[id]?.profile != null || when (mode) {
        Mode.SQL, Mode.AUTH -> id in withProfile
        Mode.LEGACY -> legacyHeavy[id]?.first != null
        else -> false
    }

    /** [id]'s stored summary says a curve its series read didn't find: unknown now, so a restore's fill
     *  or a pull's backfill may put one back, and the next save writes it so (S4). */
    private fun curveNotFound(id: String, unreadable: Boolean = false) {
        var changed = false
        // In place: a write landing meanwhile keeps its fields (#419 N3).
        stored.computeIfPresent(id) { _, prev ->
            // A file that won't decode is unknown even under a "no curve" summary: it's there.
            if (!prev.summarisedCurve && !(unreadable && prev.summary.sampleCount == 0u)) prev
            else prev.copy(summary = prev.summary.copy(sampleCount = null)).also { changed = true }
        }
        if (!changed) return
        dirtySummary += id
        bumpVersion()
    }

    /** Changes to rows past the cap that are in memory and not yet written ([flushHidden]). */
    private val pendingHidden = ConcurrentHashMap<String, ShotRow>()

    /**
     * Change a stored row past the cap, where no edit of the view can reach it:
     * in memory at once, so a reader right after sees it (a bound upload's
     * "is it gone"), and on disk by [flushHidden]. Heavy parts go through
     * [stage] first. True when the row is here.
     */
    // No app callers from #310 A3b (REVIEW-470 N3): every reader and edit goes through [rows], [row] and [update].
    @androidx.annotation.VisibleForTesting
    fun updateHidden(id: String, transform: (ShotRow) -> ShotRow): Boolean {
        if (!inSqlite || id in deleting) return false
        // A save handed in and not yet written is the newest version (#310 A3a).
        val prev = pendingHidden[id] ?: latest[id] ?: stored[id] ?: return false
        val next = transform(prev)
        if (next == prev && !staged.containsKey(id)) return true
        pendingHidden[id] = next
        // Readers of the store see it at once, and an older save landing first doesn't hide it.
        latest[id] = next
        stored[id] = next.copy(summary = stored[id]?.summary ?: next.summary)
        bumpVersion()
        return true
    }

    /** Write [id]'s change from [updateHidden]. */
    // No app callers from #310 A3b (REVIEW-470 N3): every reader and edit goes through [rows], [row] and [update].
    @androidx.annotation.VisibleForTesting
    suspend fun flushHidden(id: String) = withContext(NonCancellable) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                val row = pendingHidden.remove(id) ?: return@withContext
                if (!inSqlite || id in deleting) return@withContext
                writeLocked(listOf(row))
            }
        }
    }

    // ── The rows in memory: what every reader reads (#310 A3a, A3b) ──────────

    /**
     * The rows as this store holds them in memory, newest first: [stored]
     * (`shots.db`'s, or `shots.json`'s on the legacy backend) with the
     * writes handed to it ([latest]) over them, less the rows being deleted.
     * Every reader of the history reads these, never a list of the UI's
     * (#310 A3b): sync's planners and backlogs, backups, the screens'
     * counts and lookups, and the list while the index can't answer.
     *
     * Read in one go and without a lock: a row moves from [latest] to
     * [stored], [stored] written first, and a read takes [latest] first, so
     * no row is ever missing for being mid-write (SPEC S3). A deleted row is
     * gone from the moment its delete is called ([forget]). Before the store
     * opens, these are the rows made since launch.
     *
     * Cached by [version]; a rebuild starts from the last answer's order, so
     * a change of a few rows costs a pass, not a sort (REVIEW-467 N4).
     */
    fun rows(): List<ShotRow> {
        val v = version
        val cached = syncCache
        if (cached != null && cached.first == v) return cached.second
        // [latest] first: a row leaves it only after [stored] has it.
        val ahead = HashMap(latest)
        val all = HashMap<String, ShotRow>(stored.size + ahead.size)
        for ((id, r) in stored) all[id] = r
        all.putAll(ahead)
        all.keys.removeAll(deleting)
        val out = ArrayList<ShotRow>(all.size)
        // The last answer's order first, then what's new: mostly sorted, so the sort is a pass.
        cached?.second?.forEach { r -> all.remove(r.id)?.let(out::add) }
        out.addAll(all.values)
        out.sortWith(ORDER)
        syncCache = v to out
        return out
    }

    /** [rows], under the name sync's callers knew it by (#310 A3a). */
    fun syncRows(): List<ShotRow> = rows()

    /**
     * The row [id] as the store holds it now, the writes handed to it
     * included ([latest]); null when it holds no such row, or is deleting
     * it. What sync's "is it still here" and "is it bound" read (P3 spec,
     * #354 review), and every lookup by id.
     */
    fun row(id: String): ShotRow? {
        if (id in deleting) return null
        // [latest] first: a row leaves it only after [stored] has it.
        return latest[id] ?: stored[id]
    }

    /** [row], under the name sync's callers knew it by (#310 A3a). */
    fun syncRow(id: String): ShotRow? = row(id)

    /** Bumped on every change to the rows [rows] reads: a change notice for what derives from them. */
    val rowsVersion: StateFlow<Long> get() = versionFlow

    /** Edits of the rows in memory ([update], [put], [edit], [drop]) run one at a time, each on the rows as they stand. Reads take no lock. */
    private val edits = Any()

    /**
     * Take note of [rows], a save's or an edit's, at the call (#310 A3a):
     * each the store hasn't seen goes into [latest] until it lands, so a
     * reader of the store sees it now. Taken whether or not the store is open
     * (REVIEW-467 N1). Rows being deleted aren't taken. True: kept for the
     * callers that pass it on to [save].
     */
    fun accept(rows: List<ShotRow>): Boolean {
        var any = false
        for (r in rows) {
            if (seen[r.id] === r || r.id in deleting) continue
            if (latest.put(r.id, r) !== r) any = true
        }
        if (any) bumpVersion()
        return true
    }

    /**
     * Change the row [id] by [transform], on the row as it stands now, in
     * memory at once (#310 A3b): every reader sees it from here, and the next
     * [save] writes it. The changed row, or null when the store holds no such
     * row (or is deleting it).
     */
    fun update(id: String, transform: (ShotRow) -> ShotRow): ShotRow? = synchronized(edits) {
        if (id in deleting) return null
        val prev = latest[id] ?: stored[id] ?: return null
        val next = transform(prev)
        if (next != prev) {
            latest[id] = next
            bumpVersion()
        }
        next
    }

    /** New or replacing rows (a capture, a pull, an import, a restore), in memory at once; the next [save] writes them. */
    fun put(rows: List<ShotRow>) {
        if (rows.isEmpty()) return
        synchronized(edits) { accept(rows) }
    }

    /**
     * An edit of the whole list, for what works on it as a list (the legacy
     * backend's cap, a merge of many rows): [edit] gets the rows as they
     * stand ([rows]) and returns the list they become. Rows it adds or
     * changes are taken ([accept]); rows it leaves out are let go ([drop]).
     * On `shots.db` a written row it leaves out stays, but an edit of it not
     * yet written goes with its [latest] entry: leave out only rows being
     * deleted, or rows never written (REVIEW-470 N2). Returns the list it made.
     */
    fun edit(edit: (List<ShotRow>) -> List<ShotRow>): List<ShotRow> = synchronized(edits) {
        val before = rows()
        val after = edit(before)
        val was = HashMap<String, ShotRow>(before.size * 2).apply { for (r in before) put(r.id, r) }
        val changed = after.filter { was[it.id] !== it }
        accept(changed)
        val kept = after.mapTo(HashSet(after.size * 2)) { it.id }
        val gone = before.filter { it.id !in kept }.map { it.id }
        if (gone.isNotEmpty()) dropLocked(gone)
        after
    }

    /**
     * Let rows go from memory without deleting them (#310 A3b). A row not yet
     * written ([latest] only) is simply gone. On the legacy backend the list
     * is the file, so a row it lets go (its cap) is gone from memory and from
     * the next save. On `shots.db` a written row stays: only [delete] removes
     * one there, by name (rule 1).
     */
    fun drop(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(edits) { dropLocked(ids) }
    }

    private fun dropLocked(ids: Collection<String>) {
        var any = false
        for (id in ids) {
            if (latest.remove(id) != null) any = true
            if (mode == Mode.LEGACY && stored.remove(id) != null) any = true
        }
        if (any) bumpVersion()
    }

    /** Rows taken ([accept]) that the store hasn't written and didn't load: the shots made here since it last opened. */
    fun unwritten(): List<ShotRow> = latest.values.filter { !stored.containsKey(it.id) && it.id !in deleting }

    /** [latest]'s entries a load found as the store now holds them: nothing more to land. */
    private fun settleLatest() {
        if (latest.isEmpty()) return
        for ((id, r) in latest) {
            val s = stored[id] ?: continue
            if (s == r.copy(summary = s.summary)) latest.remove(id, r)
        }
        bumpVersion()
    }

    // ── A shot whole ─────────────────────────────────────────────────────────

    /**
     * Hold [shot]'s heavy parts for the next [save], and give its row: a shot
     * made or brought here whole (a capture, a logged brew, a pull, an import,
     * a restore, a backfill). The row goes into the view; the save writes the
     * series, profile and brew series with it. A part [shot] lacks stays as the
     * store holds it: no samples keep a stored curve, a null profile or brew
     * series keeps the stored one. Until the save lands, [loadShot] answers
     * from here.
     */
    fun stage(shot: StoredShot): ShotRow {
        // A curve read from disk a moment ago ([loadShot]) is on disk already: it needn't be written again.
        val read = synchronized(recent) { recent[shot.id] }
        val given = if (read != null && read.samples === shot.samples && shot.samples.isNotEmpty()) shot.copy(samples = emptyList()) else shot
        val merged = staged.merge(shot.id, given) { old, new ->
            new.copy(
                samples = new.samples.ifEmpty { old.samples },
                profile = new.profile ?: old.profile,
                brewSeries = new.brewSeries ?: old.brewSeries,
            )
        }!!
        return shot.toRow(summaryFor(merged))
    }

    /** [shot]'s row with the summary a save would store, staging nothing (a restore's plan works these out twice). */
    fun rowFor(shot: StoredShot): ShotRow = shot.toRow(summaryFor(shot))

    /** Drop what [stage] held for [ids]: shots that won't be saved after all (a full view refused them). */
    fun unstage(ids: Collection<String>) {
        for (id in ids) staged.remove(id)
    }

    /**
     * The shot [id] whole: its series, profile and brew series (SPEC §4), under
     * the row's fields as the store last wrote them (callers join it to the row
     * on screen). Null when the store holds no such row. A read that failed
     * just now throws [HistoryReadException], and so does a store out of
     * reach: neither says anything about the shot, so no caller may read it as
     * having no curve. A series the summary names that is damaged or gone gives
     * the shot without it, its summary unknown from then on (S4). The last
     * [RECENT_SHOTS] stay in memory.
     */
    suspend fun loadShot(id: String): StoredShot? = withContext(Dispatchers.IO) { readShot(id) }

    /** [loadShot] on the caller's thread (the backup walk's). [missingCurves] collects rows whose curve was damaged or gone. */
    fun readShot(id: String, missingCurves: MutableSet<String>? = null): StoredShot? {
        val staging = staged[id]
        // Not loaded is not empty: before the store opens, or while it's out of reach, nothing here says a shot isn't there.
        if (mode == Mode.UNOPENED || mode == Mode.BLOCKED) {
            return staging?.takeIf { id !in deleting } ?: throw HistoryReadException("The shot history can't be read just now")
        }
        val row = stored[id]
        if (row == null || id in deleting) {
            // Made here and not yet written: only memory has it.
            return staging?.takeIf { id !in deleting }
        }
        val heavy = when (mode) {
            Mode.SQL -> sqlHeavy(row, missingCurves) ?: return staging
            Mode.AUTH -> authHeavy(row, missingCurves)
            Mode.LEGACY -> legacyHeavy(row, missingCurves)
            Mode.BLOCKED, Mode.UNOPENED -> throw HistoryReadException("The shot history can't be read just now")
        }
        val disk = row.withHeavy(heavy.samples, heavy.profile, heavy.brewSeries)
        // A staged part is newer than the store's.
        return if (staging == null) disk else disk.copy(
            samples = staging.samples.ifEmpty { disk.samples },
            profile = staging.profile ?: disk.profile,
            brewSeries = staging.brewSeries ?: disk.brewSeries,
        )
    }

    /** [row]'s heavy parts from `shots.db` and its series, or null when the row has gone from the store. */
    private fun sqlHeavy(row: ShotRow, missingCurves: MutableSet<String>?): Heavy? {
        synchronized(recent) { recent[row.id] }?.let { return it }
        // A write between these reads and the cache put would leave a copy from before it (#419 S2).
        val gen = writeGen[row.id]
        val full = try {
            tables.full(db().readableDatabase, row.id)
        } catch (e: Exception) {
            throw HistoryReadException("Couldn't read shot ${row.id}: ${e.message}")
        } ?: return null
        val heavy = Heavy(readSamples(row, missingCurves), full.shot.profile, full.shot.brewSeries)
        beforeCacheForTest?.invoke(row.id)
        synchronized(recent) { if (writeGen[row.id] == gen) recent[row.id] = heavy }
        return heavy
    }

    /** [row]'s heavy parts from `history.db`'s `shot_heavy` and its series (#310 A5). */
    private fun authHeavy(row: ShotRow, missingCurves: MutableSet<String>?): Heavy {
        synchronized(recent) { recent[row.id] }?.let { return it }
        val gen = writeGen[row.id]
        val a = auth ?: throw HistoryReadException("The shot history can't be read just now")
        val (profile, brew) = try {
            a.heavy(row.id)
        } catch (e: Exception) {
            throw HistoryReadException("Couldn't read shot ${row.id}: ${e.message}")
        }
        val heavy = Heavy(readSamples(row, missingCurves), profile, brew)
        beforeCacheForTest?.invoke(row.id)
        synchronized(recent) { if (writeGen[row.id] == gen) recent[row.id] = heavy }
        return heavy
    }

    /** Each row's write count this session: a read cached only if no write landed while it ran. */
    private val writeGen = ConcurrentHashMap<String, Long>()

    /** A write to [id]'s row or series is landing: drop its cached copy, and any read in flight won't cache. */
    private fun wrote(id: String) {
        writeGen.merge(id, 1L, Long::plus)
        synchronized(recent) { recent.remove(id) }
    }

    /** [row]'s heavy parts on the legacy backend: inline in `shots.json`, the series in its file. */
    private fun legacyHeavy(row: ShotRow, missingCurves: MutableSet<String>?): Heavy {
        val (profile, brew) = legacyHeavy[row.id] ?: (null to null)
        return Heavy(readSamples(row, missingCurves), profile, brew)
    }

    /**
     * [row]'s series, read when its summary names one or doesn't say. An I/O
     * error throws. A damaged or missing file reads as none, the summary turns
     * unknown, and [missingCurves] names the row. A count that reads back other
     * than the summary's corrects the summary at the next save.
     */
    private fun readSamples(row: ShotRow, missingCurves: MutableSet<String>?): List<TelemetrySample> {
        // "No curve" with a file there is a summary to correct, not to trust (#419 S1): one stat.
        if (row.summary.sampleCount == 0u && !series.exists(row.id)) return emptyList()
        return when (val read = series.read(row.id)) {
            is SeriesRead.Found -> {
                onDisk += row.id
                written += read.file.name
                unreadNow.remove(row.id)
                if (row.summary.sampleCount != read.samples.size.toUInt()) {
                    val heavyBrew = legacyHeavy[row.id]?.second
                    var changed = false
                    stored.computeIfPresent(row.id) { _, prev ->
                        changed = true
                        prev.copy(summary = summaryOf(prev.withHeavy(read.samples, null, heavyBrew), json).let { s ->
                            if (heavyBrew == null) s.copy(brewSampleCount = prev.summary.brewSampleCount) else s
                        })
                    }
                    if (changed) {
                        dirtySummary += row.id
                        bumpVersion()
                    }
                }
                read.samples
            }
            is SeriesRead.Unavailable -> {
                unreadableSeries = unreadableSeries + row.id
                throw HistoryReadException("Couldn't read the curve of shot ${row.id}: ${read.error}")
            }
            SeriesRead.Corrupt -> {
                unreadableSeries = unreadableSeries + row.id
                missingCurves?.add(row.id)
                curveNotFound(row.id, unreadable = true)
                emptyList()
            }
            SeriesRead.Absent -> {
                if (row.summarisedCurve) missingCurves?.add(row.id)
                curveNotFound(row.id)
                emptyList()
            }
        }
    }

    /**
     * A hidden or view [row] whole, for a backup or an export: [readShot], with
     * the row's own fields. A row the store doesn't hold just now, and that
     * wasn't deleted, throws [HistoryReadException]: "can't read now", never a
     * row without its curve (#419 M1). A row deleted meanwhile comes light.
     */
    fun loadFull(row: ShotRow, missingCurves: MutableSet<String>? = null): StoredShot {
        readShot(row.id, missingCurves)?.let { return row.joined(it) }
        if (row.id in deleted || row.id in deleting) {
            // On history.db a row deleted during a backup walk is held, its heavy parts and curve
            // kept until the walk ends (S6): the walk reads it whole (#310 A5).
            heldWhole(row, missingCurves)?.let { return it }
            return row.light()
        }
        throw HistoryReadException("Couldn't read shot ${row.id} just now")
    }

    /** [row], deleted and still held by an open walk on `history.db`, whole; null when it isn't held. */
    private fun heldWhole(row: ShotRow, missingCurves: MutableSet<String>?): StoredShot? {
        val a = auth?.takeIf { mode == Mode.AUTH } ?: return null
        val (profile, brew) = runCatching { a.heavy(row.id) }.getOrElse { throw HistoryReadException("Couldn't read shot ${row.id}: ${it.message}") }
        val samples = when (val read = series.read(row.id)) {
            is SeriesRead.Found -> read.samples
            is SeriesRead.Unavailable -> throw HistoryReadException("Couldn't read the curve of shot ${row.id}: ${read.error}")
            SeriesRead.Corrupt -> emptyList<TelemetrySample>().also { missingCurves?.add(row.id) }
            SeriesRead.Absent -> {
                // Released already (the walk ended), or never had one.
                if (row.summarisedCurve) return null
                emptyList()
            }
        }
        return row.withHeavy(samples, profile, brew)
    }

    /** Rows deleted this session: a walk that listed one before its delete takes it light. */
    private val deleted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // ── Series ───────────────────────────────────────────────────────────────

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
                        seriesWritten[shot.id] = shot.samples
                        wrote(shot.id)
                    } else {
                        failed++
                    }
                }
                // history.db: a curve with no row write still moves the backup mark (REVIEW-466 note 1).
                if (shots.any { it.samples.isNotEmpty() }) noteDetailsWritten()
                failed
            }
        }
    }

    /** The series [writeSeriesOf] just wrote, by id: a save of the same staged list needn't write it again. */
    private val seriesWritten = ConcurrentHashMap<String, List<TelemetrySample>>()

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
     * Save [rows], the history as the app shows it. On SQL only the rows that
     * changed since the last save are written, with the heavy parts [stage]
     * holds for them, and nothing is deleted: a row missing from [rows] stays
     * in the store. On the legacy backend the list is the file, as before P3a.
     * True when it landed.
     */
    suspend fun save(rows: List<ShotRow>, accepted: Boolean = false): Boolean {
        // The legacy backend's floor is checked at the call: a save that waits
        // out a load would otherwise land a snapshot from before it.
        val openAtCall = SafeFile.savesOpen(file) && !SafeFile.savesHeld(file)
        if (mode == Mode.LEGACY && !SaveFloor.admit(file, WHAT)) return false
        // Readers of the store see these rows from now, not once the write lands (#310 A3a).
        // A caller that launched this save took note of them at the launch ([accepted]):
        // again now could put back a row a change since ([updateHidden]) has replaced.
        if (!accepted) accept(rows)
        return withContext(NonCancellable) {
            lock.withLock {
                withContext(Dispatchers.IO) {
                    ensureOpenLocked()
                    when (mode) {
                        Mode.SQL, Mode.AUTH -> upsertLocked(rows)
                        Mode.LEGACY -> if (openAtCall) legacySaveLocked(rows) else SaveFloor.admit(file, WHAT).let { false }
                        Mode.BLOCKED -> journalLocked(rows)
                        Mode.UNOPENED -> false
                    }.also { refreshReads() }
                }
            }
        }
    }

    /**
     * Write what memory holds that the store hasn't (#310 A3b): every row
     * handed to it ([latest]), each row whose stored summary is wrong now,
     * and each row whose heavy parts wait in [stage]. Read under the lock,
     * as the write runs, so a flush always writes the newest version and a
     * later edit's flush can never be undone by an earlier one. On the legacy
     * backend the list is the file: every row. Nothing is deleted. True when
     * it landed.
     */
    suspend fun flush(): Boolean = flush(null)

    /**
     * [flush] of [ids] only (null: all it would write), each as memory holds
     * it when the write runs: a restore's merge batch (REVIEW-470 B1). A row
     * deleted meanwhile isn't written. On the legacy backend, the list.
     */
    suspend fun flush(ids: Collection<String>?): Boolean {
        val openAtCall = SafeFile.savesOpen(file) && !SafeFile.savesHeld(file)
        if (mode == Mode.LEGACY && !SaveFloor.admit(file, WHAT)) return false
        return withContext(NonCancellable) {
            lock.withLock {
                withContext(Dispatchers.IO) {
                    ensureOpenLocked()
                    when (mode) {
                        Mode.SQL, Mode.AUTH -> upsertLocked(ids?.mapNotNull(::row) ?: pendingLocked())
                        Mode.LEGACY -> if (openAtCall) legacySaveLocked(rows()) else SaveFloor.admit(file, WHAT).let { false }
                        Mode.BLOCKED -> journalLocked(ids?.mapNotNull(::row) ?: pendingLocked())
                        Mode.UNOPENED -> false
                    }.also { refreshReads() }
                }
            }
        }
    }

    /** The rows a [flush] writes on `shots.db` or to the journal: memory's that the store may not have as they are. */
    private fun pendingLocked(): List<ShotRow> {
        val ids = HashSet<String>(latest.keys)
        ids += dirtySummary
        ids += staged.keys
        return ids.mapNotNull(::row)
    }

    /** [save] of whole shots: each is [stage]d, then saved as its row. On the legacy backend the list is the file. */
    @JvmName("saveShots")
    suspend fun save(shots: List<StoredShot>): Boolean = save(shots.map { stage(it) })

    /** [save] for a restore: every row given, past the cap too, and still no deletes. */
    suspend fun saveAll(rows: List<ShotRow>): Boolean = save(rows)

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
                            tables.write(db().writableDatabase) { tables.delete(this, ids) }
                            for (id in ids) {
                                series.deleteGz(id)
                                forgetMemory(id)
                            }
                            bumpVersion()
                            // A journal line for one of these would bring it back at the next launch (M3).
                            retireJournal(ids.toSet())
                        }
                        // history.db (#310 A5): rows first, then their series, through the source,
                        // which holds a row an open backup walk still needs (S6).
                        Mode.AUTH -> {
                            checkNotNull(auth) { "the shot history's database is closed" }.deleteNow(ids.toList())
                            for (id in ids) forgetMemory(id)
                            bumpVersion()
                            retireJournal(ids.toSet())
                        }
                        Mode.LEGACY -> for (id in ids) {
                            series.deleteAll(id)
                            written.removeAll(listOf(series.gzFile(id).name, series.jsonFile(id).name, series.legacyFile(id).name))
                            forgetMemory(id)
                            legacyHeavy.remove(id)
                        }
                        // The store is out of reach: the delete waits in the journal with the rows made meanwhile (S1).
                        Mode.BLOCKED -> {
                            for (id in ids) {
                                latest.remove(id)
                                // Gone for what reads the store now, not only at the next launch (REVIEW-467 N2).
                                stored.remove(id)
                                seen.remove(id)
                                pendingHidden.remove(id)
                                staged.remove(id)
                                journaledStage.remove(id)
                            }
                            journalDeletes(ids)
                        }
                        Mode.UNOPENED -> Unit
                    }
                    deleting.removeAll(ids.toSet())
                    bumpVersion()
                    refreshReads()
                }
            }
        }
    }

    private fun forgetMemory(id: String) {
        latest.remove(id)
        wrote(id)
        deleted += id
        unreadNow.remove(id)
        stored.remove(id)
        withProfile.remove(id)
        staged.remove(id)
        journaledStage.remove(id)
        seen.remove(id)
        onDisk.remove(id)
        pendingHidden.remove(id)
        dirtySummary.remove(id)
        seriesWritten.remove(id)
        synchronized(recent) { recent.remove(id) }
    }

    /** Take [ids] out of the hidden rows now, before their delete lands. */
    fun forget(ids: Collection<String>) {
        deleting += ids
        bumpVersion()
    }

    /** The restore marker's deletes: these shots' series, by name, the rows no longer listing them. */
    suspend fun deleteSeriesOf(ids: Collection<String>) = delete(ids)

    /** No-ops from P3a: saves never delete, so a restore has nothing to pause. */
    fun pauseDeletes() = Unit
    fun resumeDeletes() = Unit

    private fun upsertLocked(rows: List<ShotRow>): Boolean {
        val changed = rows.filter { row -> row.id !in deleting && (staged.containsKey(row.id) || !unchanged(row)) }
        // A row found as the store holds it, or being deleted, has nothing more to land (#310 A3a).
        if (latest.isNotEmpty() && changed.size < rows.size) {
            val writing = changed.mapTo(HashSet()) { it.id }
            var any = false
            for (r in rows) if (r.id !in writing && latest.remove(r.id, r)) any = true
            if (any) bumpVersion()
        }
        if (changed.isEmpty()) return true
        return writeLocked(changed)
    }

    /** What a save writes for one row: the row, the shot as written (its staged heavy parts, if any), and its summary. */
    private class Write(val row: ShotRow, val shot: StoredShot, val staging: StoredShot?, val summary: ShotSummary)

    /** Write [changed] to `shots.db`, series first; a batch that fails goes a row at a time, then to the journal. */
    private fun writeLocked(changed: List<ShotRow>): Boolean {
        val shots = changed.map { row -> staged[row.id].let { st -> Triple(row, st?.let(row::joined) ?: row.light(), st) } }
        // Series first, so a row never names a file that isn't there.
        val curveless = HashSet<String>()
        for ((_, shot, _) in shots) {
            if (shot.samples.isEmpty()) continue
            // A restore wrote this very series a moment ago ([writeSeriesOf]).
            if (seriesWritten.remove(shot.id) === shot.samples) continue
            if (series.write(shot.id, shot.samples)) onDisk += shot.id else curveless += shot.id
        }
        unsavedCurves = (unsavedCurves - changed.map { it.id }.toSet()) + curveless
        val writes = shots.map { (row, shot, st) ->
            // A curve only in memory isn't on disk: unknown, until a later save writes it.
            val summary = if (st != null) summaryFor(shot) else stored[row.id]?.summary ?: row.summary
            Write(row, shot, st, if (row.id in curveless) summary.copy(sampleCount = null) else adoptSeries(shot, summary))
        }
        val ok = landed { writeRows(writes, curveless = curveless) } || writes.map { one ->
            // The batch failed (a full disk, say): each row alone, as it may fit, then the journal.
            landed { writeRows(listOf(one), curveless = curveless) } || journalRow(one.shot).let { false }
        }.all { it }
        helper?.handler?.quarantined?.let {
            // SQLite found the file damaged mid-session and its handler kept it aside.
            closeDb()
            lost("The shot history's database was damaged and has been kept aside.")
        }
        if (ok) maybeSnapshot()
        return ok && curveless.isEmpty()
    }

    /** [block] ran without throwing. A [SimulatedCrash] (tests) ends the process, as a real one would. */
    private inline fun landed(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: SimulatedCrash) {
        throw e
    } catch (e: Throwable) {
        false
    }

    private fun writeRows(writes: List<Write>, profiles: Map<String, JsonObject> = emptyMap(), curveless: Set<String> = emptySet()) {
        if (fault(MigrationFault.DISK_FULL) != null) throw android.database.sqlite.SQLiteFullException("disk full (test)")
        if (mode == Mode.AUTH) {
            // history.db: the rows and their heavy parts in one transaction, under the source's lock (M10, #310 A5).
            checkNotNull(auth) { "the shot history's database is closed" }.write(writes.map { it.shot to it.summary }, profiles)
        } else {
            tables.write(db().writableDatabase) {
                // A part a row doesn't carry stays as stored: a light row never clears a profile or a brew series.
                for (w in writes) tables.upsert(this, w.shot, w.summary)
                for ((id, profile) in profiles) tables.putProfile(this, id, profile)
            }
        }
        for (w in writes) {
            val id = w.row.id
            wrote(id)
            // Written again (a restore's revive, say): a missing read of it is "can't read now" again (#419 F1).
            deleted.remove(id)
            stored[id] = w.row.copy(summary = w.summary)
            seen[id] = w.row
            // Landed: after [stored] has it, so a reader never misses it (#310 A3a).
            latest.remove(id, w.row)
            if (w.shot.profile != null) withProfile += id
            if (w.staging != null) {
                // Its heavy parts are in the store now; a curve that didn't write stays staged for the next save.
                synchronized(recent) { recent.remove(id) }
                if (id !in curveless) staged.remove(id, w.staging)
                journaledStage.remove(id)
            }
            dirtySummary.remove(id)
            journaled = journaled - id
        }
        withProfile += profiles.keys
        bumpVersion()
        // These rows landed: an older journal line for one would undo them at the next launch (M3).
        retireJournal(writes.mapTo(HashSet()) { it.row.id })
    }

    /**
     * A row new to the store, written without a curve, whose series file is
     * already there: a build before P3a wrote it, or the row came back after its
     * rows file was lost (#325). The file is read once, here, so the summary
     * names the curve rather than "no telemetry". A file that can't be read
     * makes the summary unknown.
     */
    private fun adoptSeries(shot: StoredShot, summary: ShotSummary): ShotSummary {
        if (stored.containsKey(shot.id) || shot.samples.isNotEmpty() || summary.sampleCount != 0u || !series.exists(shot.id)) return summary
        return when (val read = series.read(shot.id)) {
            is SeriesRead.Found -> {
                onDisk += shot.id
                summaryOf(shot.copy(samples = read.samples), json)
            }
            SeriesRead.Absent -> summary
            SeriesRead.Corrupt -> {
                unreadableSeries = unreadableSeries + shot.id
                summary.copy(sampleCount = null)
            }
            is SeriesRead.Unavailable -> summary.copy(sampleCount = null)
        }
    }

    /** Whether [row] is as the store has it (its summary aside: the store's is the one written). */
    private fun unchanged(row: ShotRow): Boolean {
        if (row.id in dirtySummary) return false
        if (seen[row.id] === row) return true
        val prev = stored[row.id] ?: return false
        val same = prev == row.copy(summary = prev.summary)
        if (same) seen[row.id] = row
        return same
    }

    /**
     * The summary to store with [shot], a staged shot as it will be written.
     * Worked out from its series when it carries one; one that doesn't keeps
     * the summary stored, so a save never turns a curve into "no telemetry".
     */
    private fun summaryFor(shot: StoredShot): ShotSummary {
        val prev = stored[shot.id]?.summary
        if (shot.samples.isNotEmpty()) {
            val s = summaryOf(shot, json)
            // A row loaded light carries no brew series: the stored count stays.
            return if (shot.brewSeries == null && prev != null) s.copy(brewSampleCount = prev.brewSampleCount) else s
        }
        if (prev == null) return summaryOf(shot, json)
        // A brew series arriving on a row that had neither (a guided brew's first save).
        if (shot.brewSeries != null && prev.sampleCount == 0u && prev.brewSampleCount == 0u) return summaryOf(shot, json)
        return prev
    }

    /** The store is out of reach: new rows go to the pending journal, their series to `.series.gz`. */
    private fun journalLocked(rows: List<ShotRow>): Boolean {
        val fresh = rows.filter { row ->
            val st = staged[row.id]
            seen[row.id] !== row || (st != null && journaledStage[row.id] !== st)
        }
        for (row in fresh) {
            val st = staged[row.id]
            val shot = st?.let(row::joined) ?: row.light()
            if (shot.samples.isNotEmpty() && series.write(row.id, shot.samples)) onDisk += row.id
            journalRow(shot)
            seen[row.id] = row
            if (st != null) journaledStage[row.id] = st
        }
        return false
    }

    /** Append [row] (without its series) to `history-pending.jsonl` as a `put`. True when it's on disk. */
    private fun journalRow(row: StoredShot, fillProfile: JsonObject? = null): Boolean =
        appendJournal(JournalEntry(put = row.copy(samples = emptyList()), fillProfile = fillProfile)).also { if (it) journaled = journaled + row.id }

    /** Append a `delete` line for each of [ids]: a delete made while the store was out of reach (S1). */
    private fun journalDeletes(ids: Collection<String>) {
        for (id in ids) appendJournal(JournalEntry(delete = id))
        journaled = journaled - ids.toSet()
    }

    private fun appendJournal(entry: JournalEntry): Boolean = runCatching {
        val line = json.encodeToString(JournalEntry.serializer(), entry) + "\n"
        java.io.FileOutputStream(pendingFile, true).use { out ->
            out.write(line.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        true
    }.getOrElse {
        persistLine("pending journal: ${it.message}")
        false
    }

    /** The journal's lines, in order; one that doesn't decode is skipped. */
    private fun journalEntries(): List<JournalEntry> =
        if (!pendingFile.exists()) emptyList()
        else pendingFile.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { json.decodeFromString(JournalEntry.serializer(), line) }.getOrNull()
        }

    /** Drop the journal's lines for [ids], which the store now has (or has deleted) as they are. */
    private fun retireJournal(ids: Set<String>) {
        if (ids.isEmpty() || !pendingFile.exists()) return
        runCatching {
            val lines = pendingFile.readLines().filter { it.isNotBlank() }
            val keep = lines.filter { line ->
                val e = runCatching { json.decodeFromString(JournalEntry.serializer(), line) }.getOrNull()
                e == null || e.id !in ids
            }
            if (keep.size == lines.size) return
            if (keep.isEmpty()) {
                if (!pendingFile.delete()) SafeFile.write(pendingFile, "", keepBackup = false)
            } else {
                SafeFile.write(pendingFile, keep.joinToString("\n", postfix = "\n"), keepBackup = false)
            }
        }.onFailure { persistLine("pending journal: couldn't retire lines: ${it.message}") }
    }

    // ── Rows snapshot (SPEC §6) ──────────────────────────────────────────────

    /** After the first save each day: every row with its profile, gzip JSON lines, keeping the last one as `.bak`. */
    private fun maybeSnapshot() {
        if (mode == Mode.AUTH) return authSnapshot()
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
    suspend fun startEmpty(): HistoryLoad = if (onStage2) recoverAuth { } else rebuild { _ -> }

    /** Lost: rebuild the store from the rows snapshot. */
    suspend fun restoreSnapshot(): HistoryLoad = if (onStage2) {
        recoverAuth { b ->
            val f = listOf(stage2.snapshot, stage2.previousSnapshot).firstOrNull { it.exists() } ?: throw MigrationFailed("There's no rows snapshot")
            fillFrom(b, rowsOf(f).asSequence())
        }
    } else rebuild { w ->
        val snap = readSnapshot() ?: throw MigrationFailed("The rows snapshot couldn't be read")
        for ((row, summary) in snap.second) {
            tables.putFull(w, row, summary ?: summaryOf(row.copy(samples = (series.read(row.id) as? SeriesRead.Found)?.samples ?: emptyList()), json))
        }
    }

    /** Lost: copy the old copy again, as the first migration did. */
    suspend fun recoverOldCopy(): HistoryLoad = if (onStage2) {
        // The old copy after the flip is shots.db, read read-only (S12), never written.
        recoverAuth { b ->
            val ro = ReadOnlyShots.open(dbFile) ?: throw MigrationFailed("The old copy (shots.db) is missing")
            ro.use {
                val ids = it.ids().sorted()
                for (chunk in ids.chunked(250)) {
                    fillFrom(b, it.rows(chunk).values.asSequence().map { (rowJson, h) -> HistoryRowIn(rowJson, HistoryHeavy(h.profileJson, h.brewSeriesJson)) })
                }
            }
        }
    } else recoverOldCopyP3()

    private suspend fun recoverOldCopyP3(): HistoryLoad = withContext(Dispatchers.IO) {
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
    private suspend fun rebuild(fill: (ShotsWrite) -> Unit): HistoryLoad = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                // Whatever is there may still hold rows (a lost store that wasn't quarantined): kept aside, never deleted (S7).
                clearNewStore()
                val bytes = runCatching { if (file.exists()) file.readBytes() else null }.getOrNull()
                val ids = bytes?.let { b ->
                    runCatching { json.decodeFromString(ListSerializer(StoredShot.serializer()), b.toString(Charsets.UTF_8)).map { it.id } }.getOrNull()
                }.orEmpty()
                val db = db().writableDatabase
                tables.write(db) {
                    tables.clear(this)
                    fill(this)
                    tables.putMeta(db, TABLE_META_KEY, json.encodeToString(MigrationRecord.serializer(), MigrationRecord(STATE_DONE, bytes?.let(::sha256) ?: "", ids)))
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

    // ── The stage-1 index (#310 A1, SQLite SPEC §11 stage 1) ─────────────────

    /**
     * [block] under the store's lock, with `shots.db` when the rows are in it
     * this session. The lock is taken and released on IO, never on the
     * caller's dispatcher: a background sync started on Main must not need
     * Main to let a save through.
     */
    private suspend fun <T> withShots(block: (SQLiteDatabase?) -> T): T =
        withContext(Dispatchers.IO) {
            lock.withLock { block(if (mode == Mode.SQL) runCatching { db().readableDatabase }.getOrNull() else null) }
        }

    /**
     * The boot check, and a full diff when it misses ([force]: one anyway).
     * Each batch takes the lock, so a capture never waits on the whole diff.
     * Null when nothing ran: the store isn't on `shots.db`, the check passed,
     * or the index won't open. Never throws but a [SimulatedCrash].
     */
    suspend fun syncIndex(force: Boolean = false): IndexDiffReport? {
        val diff = withShots { shots -> shots?.let { index.planDiff(it, force) }.also { refreshReads() } } ?: return null
        while (withShots { shots -> shots != null && index.diffStep(diff, shots) }) afterDiffBatchForTest?.invoke()
        return withShots { shots -> index.finishDiff(diff, shots).also { refreshReads() } }
    }

    // ── Reading the index (#310 A2) ──────────────────────────────────────────

    private val reads = MutableStateFlow<HistoryIndexReads?>(null)

    /** The live libraries reached the index this session: until then its coffee, tag and method answers may be last session's. */
    @Volatile private var liveSet = false

    /**
     * What History reads while the index can answer for the rows (SPEC §3,
     * §12): null means `index_fallback`, the in-memory rows. It can answer
     * only when the rows are in `shots.db` and readable, none waits in the
     * pending journal, the live libraries are in, and the index is fresh
     * ([HistoryIndex.fresh]). A new value each time it becomes readable again.
     */
    val indexReads: StateFlow<HistoryIndexReads?> = reads.asStateFlow()

    /** Why History reads the in-memory rows now, or null when it reads the index: the debug screen's line. */
    @Volatile var fallbackReason: String? = "not checked yet"
        private set

    private fun refreshReads() {
        // On history.db the list reads the truth itself, with its own notices (#310 A5).
        val a = auth?.takeIf { mode == Mode.AUTH }
        val reader = if (a != null) a.db.takeIf { !it.isClosed } else index.reader()
        val why = when {
            !inSqlite -> "the rows aren't in SQLite (${mode.name.lowercase()})"
            health != HistoryHealth.OK -> "the store is ${health.name.lowercase()}"
            journaled.isNotEmpty() -> "${journaled.size} rows wait in the pending journal"
            unreadableRows.isNotEmpty() -> "${unreadableRows.size} rows this build can't read"
            !liveSet -> "the live libraries aren't in the index yet"
            reader == null && a != null -> "the shot history's database is closed"
            reader == null -> if (index.lastError != null) "the index failed: ${index.lastError}" else "the index is stale or closed"
            else -> null
        }
        fallbackReason = why
        val current = reads.value
        reads.value = when {
            why != null || reader == null -> null
            current != null && current.bridge === reader -> current
            else -> HistoryIndexReads(reader, a?.notices ?: index.notices, ::decodeIndexRow)
        }
    }

    /**
     * Puts the live bean and method libraries into the index (SPEC §5, §6):
     * derived data the coffee scope, the effective tags and a custom method's
     * label read. Under the lock, as every index call. Rows, seqs and
     * `pending` are left alone.
     */
    suspend fun setIndexLive(live: coffee.crema.core.HistoryLive) = withShots { shots ->
        val a = auth?.takeIf { mode == Mode.AUTH }
        if (a != null) {
            if (runCatching { a.setLive(live) }.onFailure { persistLine("live libraries: ${it.message}") }.isSuccess) liveSet = true
        } else if (shots != null && index.setLive(live)) {
            liveSet = true
        }
        refreshReads()
    }

    /** An index row's `row_json` as the screens show it, as [sqlLoad] reads `shots.db`'s; null when it won't read. */
    fun decodeIndexRow(rowJson: String): ShotRow? = runCatching {
        val r = tables.decodeRow(rowJson)
        r.shot.toRow(r.summary ?: unknownSeriesSummary(r.shot))
    }.getOrNull()

    /** Test seam: as SQLite's error handler does mid-session, the file kept aside as [aside]; the next write sees it and the store is lost. */
    @androidx.annotation.VisibleForTesting
    internal fun quarantineForTest(aside: File) {
        helper?.handler?.markQuarantinedForTest(aside)
    }

    /** Test seam: every file this store has open closed, as a process exit leaves them (#310 A5). */
    @androidx.annotation.VisibleForTesting
    internal suspend fun closeForTest() = withContext(Dispatchers.IO) {
        lock.withLock {
            closeDb()
            closeAuth()
            index.closeAll()
        }
    }

    /** Test seam: close the index as a process death would, so another store (or the test) can open the file. */
    @androidx.annotation.VisibleForTesting
    internal suspend fun closeIndexForTest() = withContext(Dispatchers.IO) { lock.withLock { index.closeAll() } }

    /** The index's health, for the debug screen. */
    suspend fun indexHealth(): IndexHealth = withShots { index.health(it) }

    /** The index against `shots.db`, row by row: the debug screen's check and the tests' oracle. Null when either isn't open. */
    suspend fun compareIndex(): IndexComparison? = withShots { shots -> shots?.let { index.compare(it) } }

    // ── Legacy backend: shots.json, as before P3a ─────────────────────────────

    /**
     * `shots.json`'s rows. Its profile and brew series stay in memory (the
     * file carries them inline, and a save rewrites it whole); each row's
     * summary is worked out here from its series, as this backend has no
     * stored one. That reads the series (at most [LEGACY_MAX_SHOTS]) at load, as
     * before P3a. A row whose series is inline is [stage]d, so the next save
     * moves it to its file.
     */
    private fun legacyLoad(): List<ShotRow> {
        val outcome = loadStore(file, WHAT, open = false) {
            json.decodeFromString(ListSerializer(StoredShot.serializer()), it)
        }
        val shots = when (outcome) {
            is SafeLoad.Loaded -> outcome.value.newestFirst()
            SafeLoad.Absent, is SafeLoad.Corrupt, is SafeLoad.Unavailable -> emptyList()
        }
        // Every series read first, then memory replaced with no gap: readers run outside
        // the lock, and a row missing for a moment would read as gone (#419 M1).
        val heavy = HashMap<String, Pair<JsonObject?, coffee.crema.core.BrewSeries?>>()
        val unreachable = HashSet<String>()
        val rows = shots.map { shot ->
            heavy[shot.id] = shot.profile to shot.brewSeries
            val samples = shot.samples.ifEmpty {
                when (val read = series.read(shot.id)) {
                    is SeriesRead.Found -> {
                        written += read.file.name
                        onDisk += shot.id
                        read.samples
                    }
                    SeriesRead.Absent -> emptyList()
                    SeriesRead.Corrupt -> {
                        unreadableSeries = unreadableSeries + shot.id
                        null
                    }
                    is SeriesRead.Unavailable -> {
                        // Don't know: unknown, and not to be filled over (#419 N5).
                        unreadableSeries = unreadableSeries + shot.id
                        unreachable += shot.id
                        null
                    }
                }
            }
            if (shot.samples.isNotEmpty()) staged[shot.id] = shot
            val summary = if (samples == null) unknownSeriesSummary(shot) else summaryOf(shot.copy(samples = samples), json)
            shot.toRow(summary)
        }
        seen.clear()
        synchronized(recent) { recent.clear() }
        // Only added to: a row the file lost but the view still holds keeps its profile and
        // brew series for the next save (#419 F3). A delete drops an id's entry.
        legacyHeavy.putAll(heavy)
        unreadNow += unreachable
        unreadNow.retainAll(unreachable)
        replaceRows(rows.associateBy { it.id })
        settleLatest()
        return rows
    }

    private suspend fun legacySaveLocked(rows: List<ShotRow>): Boolean {
        val shots = rows.map { row ->
            val st = staged[row.id]
            val (profile, brew) = legacyHeavy[row.id] ?: (null to null)
            row.withHeavy(st?.samples.orEmpty(), st?.profile ?: profile, st?.brewSeries ?: brew)
        }
        val curves = HashSet<String>()
        for (shot in shots) {
            if (shot.samples.isEmpty()) continue
            if (series.write(shot.id, shot.samples)) {
                written += series.gzFile(shot.id).name
                onDisk += shot.id
                curves += shot.id
            }
        }
        // Only a shot whose series file is written goes without its samples.
        val ok = saveStore(file, WHAT) {
            json.encodeToString(
                ListSerializer(StoredShot.serializer()),
                shots.map { if (it.id in curves) it.copy(samples = emptyList()) else it },
            )
        }
        if (ok) {
            for ((row, shot) in rows.zip(shots)) {
                legacyHeavy[row.id] = shot.profile to shot.brewSeries
                val st = staged[row.id]
                stored[row.id] = row.copy(summary = if (st != null) summaryFor(shot) else stored[row.id]?.summary ?: row.summary)
                // Landed, after [stored] has it (#310 A3b): the list is the file now.
                latest.remove(row.id, row)
                if (st != null && (shot.samples.isEmpty() || row.id in curves)) staged.remove(row.id, st)
            }
            bumpVersion()
        }
        return ok
    }

    // ── Erase ────────────────────────────────────────────────────────────────

    /** Delete everything the history keeps (Erase all data): `shots.json` and its copies, the series,
     *  `shots.db` with its `-wal` and `-shm` and any kept-aside copy, the marker, the snapshot and the journal,
     *  and the SQLite history's files ([eraseSqliteHistory]), the index closed first (S11). Returns the names of what
     *  stayed, empty when everything went, so Erase all data can say so (#428). */
    suspend fun erase(): List<String> =
        lock.withLock {
            // The index first, under the core's lock (S11), and no store here opens it again until
            // this returns (REVIEW-446 note 4): nothing writes or makes a file this is about to remove.
            withContext(Dispatchers.IO) { index.erasing { eraseLocked() } }
        }

    private fun eraseLocked(): List<String> {
        val stayed = mutableListOf<String>()
        fun gone(name: String, ok: Boolean) { if (!ok) stayed += name }
        closeDb()
        // history.db under the core's lock first (S11): nothing writes a file this is about to remove.
        closeAuth()
        onStage2 = false
        tooNew = false
        stage2Marker = null
        gone(FILE_NAME, eraseStore(file))
        seriesDir.listFiles()?.forEach { gone("$SERIES_DIR/${it.name}", eraseStore(it)) }
        // False also when there's none: what's left on disk tells.
        runCatching { context.deleteDatabase(NEXT_DB_NAME) }
        gone(NEXT_DB_NAME, !dbFile.exists())
        dbFile.parentFile?.listFiles { f -> f.name.startsWith(NEXT_DB_NAME + QuarantineHandler.CORRUPT) }
            ?.forEach { gone(it.name, it.delete()) }
        NEXT_FILES.forEach { gone(it, eraseStore(File(context.filesDir, it))) }
        gone(SQLITE_DIR, eraseSqliteHistory())
        duringEraseForTest?.invoke()
        Stores.forget(dbFile)
        written.clear()
        stored.clear(); withProfile.clear(); staged.clear(); journaledStage.clear(); legacyHeavy.clear(); seriesWritten.clear(); seen.clear();
        deleted.clear(); writeGen.clear(); unreadNow.clear()
        onDisk.clear(); deleting.clear(); pendingHidden.clear(); dirtySummary.clear(); latest.clear()
        synchronized(recent) { recent.clear() }
        bumpVersion()
        unreadableSeries = emptySet()
        unsavedCurves = emptySet()
        journaled = emptySet()
        lostChoices = null
        snapshotDay = null
        // The next save starts a new, empty store.
        mode = Mode.UNOPENED
        health = HistoryHealth.LOADING
        return stayed
    }

    /**
     * Remove the files the SQLite history keeps (its SPEC §11, N3), if present:
     * `history-sqlite/` (the index, `history.db`, any `history-<n>.db`, the snapshots, their
     * `-wal`, `-shm` and `-journal`, `pending.jsonl`, `done.json`) and its marker,
     * `history-sqlite.json`. This build writes only the index (#310 A1), closed before this
     * runs; it removes the rest so an erase here, after a rollback, still takes everything.
     * The done-record goes after the rest of the directory, and the marker only once the
     * directory is gone, so a file that stays is never left without its marker (S10). True
     * when none of them remains.
     */
    private fun eraseSqliteHistory(): Boolean {
        val dir = File(context.filesDir, SQLITE_DIR)
        val done = File(dir, SQLITE_DONE_NAME)
        val rest = dir.listFiles()?.filterNot { it.name == done.name || it.name.startsWith(done.name + ".") }.orEmpty()
        var gone = rest.map { it.deleteRecursively() }.all { it }
        if (gone) gone = SafeFile.erase(done)
        if (gone) gone = !dir.exists() || dir.deleteRecursively()
        if (!gone) {
            persistLine("couldn't erase every file in $SQLITE_DIR; kept $SQLITE_MARKER_NAME")
            return false
        }
        return eraseStore(File(context.filesDir, SQLITE_MARKER_NAME))
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

    private sealed interface MarkerRead {
        data class Found(val marker: HistoryMarker) : MarkerRead
        data object Absent : MarkerRead
        /** There, and none of its copies decodes. */
        data object Damaged : MarkerRead
        /** There, and couldn't be read: an I/O error. */
        data class Unreadable(val error: String) : MarkerRead
    }

    private fun readMarkerOutcome(): MarkerRead =
        when (val m = SafeFile.peek(markerFile) { json.decodeFromString(HistoryMarker.serializer(), it) }) {
            is SafeLoad.Loaded -> MarkerRead.Found(m.value)
            SafeLoad.Absent -> MarkerRead.Absent
            is SafeLoad.Corrupt -> MarkerRead.Damaged
            is SafeLoad.Unavailable -> MarkerRead.Unreadable(m.error)
        }

    /**
     * Take `shots.db` out of the way for a new copy: deleted when it holds no
     * rows (a migration that didn't commit), kept aside as `shots.db.corrupt-<ms>`
     * with its `-wal` and `-shm` otherwise, or when it can't be read to tell.
     */
    private fun clearNewStore() {
        closeDb()
        if (!dbFile.exists()) return
        val empty = runCatching {
            // A handler that does nothing: the framework's would delete a damaged file.
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY) { }.use { db ->
                db.rawQuery("SELECT COUNT(*) FROM shots", null).use { c -> c.moveToFirst() && c.getInt(0) == 0 }
            }
        }.getOrElse { e -> e is android.database.sqlite.SQLiteException && "no such table" in (e.message ?: "") }
        if (empty) {
            context.deleteDatabase(NEXT_DB_NAME)
        } else if (!QuarantineHandler().quarantine(dbFile)) {
            throw MigrationFailed("Couldn't set the old shots.db aside")
        }
        // Gone from under the index, outside a write: `pending` up until a full diff.
        index.replaced()
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

    /** [all] as the view and the rows past its cap. From P6a `shots.db` has no cap, so every row is in view. */
    private fun <T : ShotMeta> splitView(all: List<T>): Pair<List<T>, List<T>> {
        val cap = viewCap ?: return all to emptyList()
        var unpinned = 0
        val view = ArrayList<T>()
        val hidden = ArrayList<T>()
        for (row in all) {
            if (row.pinned || unpinned++ < cap) view += row else hidden += row
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
        val ORDER: Comparator<ShotMeta> = compareByDescending<ShotMeta> { it.completedAtMs }.thenByDescending { it.id }

        /** Test seam: runs between a shot's reads and their cache put, where a write can land (#419 S2). */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var beforeCacheForTest: ((String) -> Unit)? = null

        /** Shots [loadShot] keeps whole in memory (SPEC §4: Android keeps the last 8). */
        const val RECENT_SHOTS = 8

        /** Tests set this to hold [loadAll] (the open and the move) until they complete it. Null in the app. */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var holdOpen: kotlinx.coroutines.Deferred<Unit>? = null

        /** Test seam: runs inside [erase], after the SQLite history's files are gone (#310 A1b). */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var duringEraseForTest: (() -> Unit)? = null

        /** Test seam: runs between two batches of the index's full diff, outside the lock (#310 A1). */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var afterDiffBatchForTest: (suspend () -> Unit)? = null

        /** Test seam: the steps that fail or stop, process-wide. */
        @androidx.annotation.VisibleForTesting
        internal val faults: MutableSet<MigrationFault> = Collections.synchronizedSet(mutableSetOf())

        /**
         * Stage 2 (#310 A5): the flip runs at the first open, and a `done`
         * marker opens `history.db`. Off, a build reads and writes `shots.db`
         * as stage 1 does, whatever the marker says: an older build's view.
         */
        @Volatile internal var stage2Enabled = STAGE2_IN_BUILD

        /** Whether this build ships stage 2. */
        const val STAGE2_IN_BUILD = false

        /** Test seam: every migration fails, so the legacy backend runs (its own tests). */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var legacyOnlyForTest = false

        /** Test seam: the free space a migration sees. */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var freeSpaceForTest: (() -> Long)? = null

        /**
         * The legacy backend's cap: `shots.json` keeps the newest N unpinned
         * shots, as before P3a. Nothing else caps the history from P6a (#310).
         */
        const val LEGACY_MAX_SHOTS = 300

        /** The view's cap for a store in [health] ([viewCap]), for what reads the UI state. */
        fun capFor(health: HistoryHealth): Int? = if (health == HistoryHealth.LEGACY) LEGACY_MAX_SHOTS else null
        const val FILE_NAME = "shots.json"

        /** The SQLite store the rows move to (SPEC §3, P3a). */
        const val NEXT_DB_NAME = "shots.db"

        const val MARKER_NAME = "history-store.json"
        const val SNAPSHOT_NAME = "history-rows-snapshot.jsonl.gz"
        const val PENDING_NAME = "history-pending.jsonl"

        /** The store marker, rows snapshot and pending journal (SPEC §1 and §6), for [erase] too. */
        val NEXT_FILES = listOf(MARKER_NAME, SNAPSHOT_NAME, PENDING_NAME)

        /**
         * The SQLite history's directory, done-record and marker (SQLite history SPEC §11, N3). This
         * build writes only the index there ([HistoryIndex], #310 A1); a later one writes the rest, and
         * [erase] removes them all. Never under `databases/`, so nothing that lists
         * `context.databaseList()` can open them through the framework.
         */
        const val SQLITE_DIR = "history-sqlite"
        const val SQLITE_DONE_NAME = "done.json"
        const val SQLITE_MARKER_NAME = "history-sqlite.json"
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
