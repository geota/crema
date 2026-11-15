package coffee.crema.history

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.SafeFile
import coffee.crema.ui.coreJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SQLite history H0: Erase all data takes the files a newer build's SQLite
 * history keeps, though this build writes none of them, so an erase after a
 * rollback to this build still leaves nothing behind (SPEC §11, N3). A file
 * that stays keeps its marker (S10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistorySqliteEraseTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val filesDir get() = app.filesDir
    private val sqliteDir get() = File(filesDir, HistoryStore.SQLITE_DIR)
    private val marker get() = File(filesDir, HistoryStore.SQLITE_MARKER_NAME)

    /** Every file a stage-1 or stage-2 build can leave in `history-sqlite/`. */
    private val planted = listOf(
        "history-index.db", "history-index.db-wal", "history-index.db-shm", "history-index.db-journal",
        "history.db", "history.db-wal", "history.db-shm", "history.db-journal",
        "history-2.db", "history-2.db-wal", "history-2.db-shm",
        "history-snapshot.db", "history-snapshot.db-journal", "history-snapshot.db.prev",
        "pending.jsonl", "pending.jsonl.tmp", "done.json", "done.json.bak",
        "repair/history.db",
    )

    /** Names beside them that aren't the SQLite history's. */
    private val others = listOf("history-sqlite-notes.txt", "history-sqlite.json.txt", "elsewhere/history.db", "elsewhere/history-sqlite.json")

    private fun plant(base: File, name: String) = File(base, name).apply { parentFile?.mkdirs(); writeText(name) }

    private fun store() = HistoryStore(app, coreJson)

    @Before fun clean() {
        SafeFile.openAllForTest()
        runBlocking { store().erase() }
    }

    @After fun tidy() {
        sqliteDir.setWritable(true)
        sqliteDir.deleteRecursively()
        others.forEach { File(filesDir, it).delete() }
        File(filesDir, "elsewhere").delete()
        SafeFile.resetForTest()
    }

    @Test fun `erase removes the planted SQLite history and its marker, and nothing else`() = runBlocking {
        planted.forEach { plant(sqliteDir, it) }
        plant(filesDir, HistoryStore.SQLITE_MARKER_NAME)
        plant(filesDir, HistoryStore.SQLITE_MARKER_NAME + ".bak")
        plant(filesDir, HistoryStore.SQLITE_MARKER_NAME + ".tmp")
        others.forEach { plant(filesDir, it) }

        store().erase()

        assertFalse(sqliteDir.exists(), "history-sqlite/ is gone, every file in it with it")
        assertFalse(marker.exists())
        assertFalse(SafeFile.backupOf(marker).exists())
        assertFalse(File(filesDir, HistoryStore.SQLITE_MARKER_NAME + ".tmp").exists())
        for (name in others) assertEquals(name, File(filesDir, name).readText(), "$name is not the SQLite history's")
    }

    @Test fun `erase without any SQLite history files still erases`() = runBlocking {
        others.forEach { plant(filesDir, it) }
        store().erase()
        assertFalse(sqliteDir.exists())
        assertFalse(marker.exists())
        for (name in others) assertTrue(File(filesDir, name).exists())
    }

    @Test fun `a file that stays keeps the done-record and the marker (S10)`() = runBlocking {
        // A read-only directory: its files can't be unlinked. Not on a filesystem that ignores it.
        val held = File(sqliteDir, "held").apply { mkdirs() }
        val probe = plant(held, "probe")
        plant(held, "history.db")
        plant(sqliteDir, HistoryStore.SQLITE_DONE_NAME)
        plant(filesDir, HistoryStore.SQLITE_MARKER_NAME)
        held.setWritable(false)
        try {
            org.junit.Assume.assumeFalse("a read-only directory's files can't be deleted here (not root)", probe.delete())
            store().erase()
            assertTrue(File(held, "history.db").exists())
            assertTrue(File(sqliteDir, HistoryStore.SQLITE_DONE_NAME).exists(), "the done-record goes only after the rest")
            assertTrue(marker.exists(), "the marker goes only once the directory is gone")
        } finally {
            held.setWritable(true)
        }
        // Once it can go, the next erase takes everything.
        store().erase()
        assertFalse(sqliteDir.exists())
        assertFalse(marker.exists())
    }
}
