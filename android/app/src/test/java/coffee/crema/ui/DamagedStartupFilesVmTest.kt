package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * geota/crema#576: a file the app reads while starting, damaged the way a hard
 * kill or a power loss leaves it, never stops the app starting. Each case is
 * one startup file, zero-filled (a torn block) or cut off mid-JSON, on a device
 * that has already been used: the real ViewModel must build and finish its
 * startup load with nothing thrown, the damaged bytes must still be on disk
 * byte for byte (at the file's own path, or kept aside beside it), and the
 * shot history, which is in none of these files, must still hold its row.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DamagedStartupFilesVmTest(private val file: String, private val damage: String) {

    companion object {
        /** Every file the startup path reads that a crash or power loss can damage. */
        private val FILES = listOf(
            "prefs.json", "hot-water.json", "beanLibrary.json", "shots.json",
            "history-store.json", "history-sqlite.json", "history-sqlite/done.json",
            "history-rows-snapshot.jsonl.gz", "history-pending.jsonl",
            "pinnedProfiles.json", "hiddenProfiles.json", "customProfiles.json",
            "brew-recipes.json", "maintenance.json", "visualizer.json", "decent.json", "drive.json",
            "stolenSerials.json", "sawModel.json", "relay-v4.json", "restore-pending.json",
            "relayPort", "last-shot.txt",
            // SQLite discards a -wal whose header doesn't check out: copied aside first (#578 follow-up).
            "history-sqlite/history.db-wal",
            // Read and then deleted by design, so only "it starts" is checked:
            // relay v3 leftovers, erased once relay-v4.json records the
            // migration, and the stage-2 journal, emptied once replayed.
            "proxy-pairing.json", "deviceId", "history-sqlite/pending.jsonl",
        )

        private val DELETED_BY_DESIGN = setOf("proxy-pairing.json", "deviceId", "history-sqlite/pending.jsonl")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun cases(): List<Array<Any>> = FILES.flatMap { f -> listOf(arrayOf<Any>(f, "zero-filled"), arrayOf<Any>(f, "truncated")) }
    }

    private val stores = mutableListOf<ViewModelStore>()
    private val app get() = VmHarness.app()
    private val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        // A coroutine's uncaught failure goes to the thread's handler: on a
        // device that's the crash, here it would only be printed.
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        Thread.currentThread().setUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After fun tearDown() {
        stores.forEach { it.clear() }
        VmHarness.idle()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        Thread.currentThread().setUncaughtExceptionHandler(null)
        SafeFile.resetForTest()
        Stores.resetForTest()
    }

    private fun startedVm(): MainViewModel {
        val s = ViewModelStore().also(stores::add)
        return VmHarness.build(s).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }
    }

    /** A used device: a shot, a roaster, a setting and a pinned profile saved, then the app closed. */
    private fun useTheApp() {
        val vm = startedVm()
        vm.addManualBrew(
            LibraryController.ManualBrewInput(
                method = "espresso", completedAtMs = 1_700_000_000_000, beanId = null, doseG = 18f, waterG = null,
                yieldG = 36f, grind = null, brewTempC = null, durationMs = 28_000, rating = null, notes = null, nextPlan = null,
            ),
        )
        vm.addRoaster("Damage test roaster", null, null, null, "")
        vm.setAutoDriveBackup(true)
        vm.ui.value.profiles.firstOrNull()?.let { vm.togglePinProfile(it.id) }
        VmHarness.idleUntil("the shot to land") { VmHarness.rows(vm).size == 1 }
        stores.removeAt(stores.lastIndex).clear()
        VmHarness.idle()
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
    }

    /**
     * A damaged `-wal` comes from a kill, and a kill takes the process's
     * connection to [db] with it. In this JVM the last ViewModel's connection
     * stays open (a new store takes it over), and closing it over a `-wal`
     * the test replaced would checkpoint the replaced bytes into the main file
     * from its in-memory WAL index, which no device can do. So the disk is
     * left as a kill leaves it: the connection closed and the main file and
     * `-shm` put back as they were before the close, without its checkpoint.
     */
    private fun killedMidSession(db: File) {
        val asLeft = listOf(db, File(db.path + "-shm")).filter { it.exists() }.associateWith { it.readBytes() }
        coffee.crema.history.HistoryAuthority.closeEveryHeld()
        for ((f, bytes) in asLeft) f.writeBytes(bytes)
    }

    /** An odd length, so no file the app writes has these exact bytes by chance. */
    private fun damaged(): ByteArray = when (damage) {
        "zero-filled" -> ByteArray(4099)
        else -> """{"damaged":[{"id":"cut-off-he""".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun theAppStartsAndTheDamagedBytesStayOnDisk() {
        useTheApp()
        if (file.endsWith("-wal")) killedMidSession(File(app.filesDir, file.removeSuffix("-wal")))
        val target = File(app.filesDir, file).apply { parentFile?.mkdirs() }
        val bytes = damaged()
        target.writeBytes(bytes)

        val vm = startedVm()

        assertEquals(emptyList(), uncaught.map { "${it.javaClass.simpleName}: ${it.message}" }, "nothing thrown while starting")
        assertEquals(1, VmHarness.rows(vm).size, "the shot history is untouched")
        if (file in DELETED_BY_DESIGN) return
        val dataDir = app.filesDir.parentFile!!
        val holders = dataDir.walkTopDown()
            .filter { it.isFile && it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }
            .map { it.relativeTo(app.filesDir).path }
            .toList()
        assertTrue(holders.isNotEmpty(), "the damaged $file is still on disk, not overwritten or deleted")
        assertTrue(
            holders.all { it == file || it.startsWith("$file.corrupt-") || it == "$file.bak" },
            "the damaged bytes are at $file or kept beside it, not elsewhere: $holders",
        )
    }
}
