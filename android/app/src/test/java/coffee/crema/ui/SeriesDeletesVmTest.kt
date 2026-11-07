package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ui.brewlog.BrewLogOwner
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.persist.SafeFile
import coffee.crema.visualizer.wireShotJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Explicit series deletes through the real startup and restore (#325): the
 * history's `known` comes from the load's rows, so a delete reaches the disk,
 * a corrupt file's lost rows never do, and a restore leaves series alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SeriesDeletesVmTest {
    private var store = ViewModelStore()
    private val app get() = VmHarness.app()
    private val seriesDir get() = File(app.filesDir, "shot-series")
    private val rowsFile get() = File(app.filesDir, "shots.json")

    @After fun tearDown() {
        store.clear()
        VmHarness.idle()
        seriesDir.deleteRecursively()
    }

    private fun launch(): MainViewModel = VmHarness.build(store).also {
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(it)
    }

    private fun relaunch(): MainViewModel {
        store.clear()
        VmHarness.idle()
        store = ViewModelStore()
        return launch()
    }

    private fun shot(id: String) = StoredShot(
        id = id,
        completedAtMs = 1_700_000_000_000L,
        durationMs = 3_000,
        samples = List(3) { n -> TelemetrySample(elapsedMs = n * 222L, pressure = 9f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null, dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1) },
    )

    private fun stored(vararg rows: StoredShot) = runBlocking { HistoryStore(app, coreJson).save(rows.toList()) }

    private fun series(id: String) = File(seriesDir, HistoryStore.seriesFileName(id))

    private fun log(vm: MainViewModel) {
        vm.openLogBrew(BrewLogOwner.HISTORY)
        vm.updateLogBrew { it.copy(method = "pourover", dose = 15.0) }
        assertTrue(vm.saveLogBrew(1_700_000_000_000L), "the form saved")
        VmHarness.idle()
    }

    private fun rowsOnDisk() = rowsFile.takeIf { it.exists() }?.readText().orEmpty()

    @Test
    fun `deleting a loaded shot deletes its series`() {
        stored(shot("gone"), shot("stays"))
        val vm = launch()
        vm.deleteShot("gone")
        VmHarness.idleUntil("the series file went") { !series("gone").exists() }
        assertTrue(series("stays").exists())
    }

    @Test
    fun `corrupt rows keep every series through two launches of saves`() {
        stored(shot("a"), shot("b"))
        rowsFile.writeText("{ not json")
        SafeFile.backupOf(rowsFile).delete()

        val first = launch()
        assertTrue(VmHarness.ownState(first).history.isEmpty())
        log(first)
        VmHarness.idleUntil("the first brew saved") { VmHarness.ownState(first).history.first().id in rowsOnDisk() }

        val second = relaunch()
        val kept = VmHarness.ownState(second).history.single().id
        // Twice: a save's deletes follow its write, and the second save can't
        // start until the first has finished them.
        log(second)
        log(second)
        VmHarness.idleUntil("the later brews saved") {
            VmHarness.ownState(second).history.filter { it.id != kept }.all { it.id in rowsOnDisk() }
        }

        assertTrue(series("a").exists(), "a's curve survived")
        assertTrue(series("b").exists(), "b's curve survived")
    }

    @Test
    fun `a Replace restore deletes no series, and the next delete is by name`() {
        stored(shot("old"), shot("both"))
        val vm = launch()
        fun line(body: JsonObject, kind: String) =
            Json.encodeToString(JsonObject.serializer(), JsonObject(body + ("kind" to JsonPrimitive(kind))))
        val text = (listOf(line(JsonObject(emptyMap()), "crema-backup/v1")) +
            listOf(shot("both"), shot("restored")).map { line(wireShotJson(it, forBackup = true), "shot") }).joinToString("\n")

        val before = VmHarness.ownState(vm).userMessages.size
        vm.restoreBackupFromText(text, MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the restore reported") { VmHarness.ownState(vm).userMessages.size > before }
        VmHarness.idleUntil("the restore's save") { "\"restored\"" in rowsOnDisk() }
        assertTrue(series("old").exists(), "restore's deletes aren't the diff's")
        assertTrue(series("restored").exists())

        vm.deleteShot("restored")
        VmHarness.idleUntil("the restored row's series went") { !series("restored").exists() }
        assertTrue(series("old").exists())
        assertTrue(series("both").exists())
        assertEquals(listOf("both"), VmHarness.ownState(vm).history.map { it.id })
        assertFalse("\"restored\"" in rowsOnDisk() && "\"old\"" in rowsOnDisk())
    }
}
