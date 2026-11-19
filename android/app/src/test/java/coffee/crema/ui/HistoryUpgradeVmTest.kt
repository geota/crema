package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.history.HistoryFixtures
import coffee.crema.history.HistoryStore
import coffee.crema.history.joined
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #310 A5 through the real ViewModel (#482): a 0.10.0 filesDir, launched on
 * this build with stage 2 on, as `HistoryUpgradeTest` runs it on the store.
 * Legacy-named series and an odd id included, every shot lands whole on
 * `history.db`, an edit and an upload stamp made after the flip last a
 * relaunch, and `shots.json` with its `.bak` is left byte for byte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryUpgradeVmTest {
    private var store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val shotsJson get() = File(app.filesDir, HistoryStore.FILE_NAME)
    private val shotsBak get() = File(shotsJson.path + ".bak")

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        runBlocking { HistoryStore(app, coreJson).erase() }
        File(app.filesDir, "shot-series").deleteRecursively()
    }

    @After fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private fun vm(): MainViewModel = VmHarness.build(store).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }

    private fun relaunch(): MainViewModel {
        store.clear()
        VmHarness.idle()
        store = ViewModelStore()
        SafeFile.resetForTest()
        return vm()
    }

    @Test
    fun `0_10_0's files through the real launch land whole on history-db, and edits last a relaunch`() {
        assertTrue(HistoryStore.stage2Enabled, "this build's default: stage 2")
        val rows = HistoryFixtures.as0100(HistoryFixtures.rows(300, samplesPer = 6))
        val odd = rows[13]
        assertTrue("/" in odd.id && " " in odd.id, "an odd id: ${odd.id}")
        val legacy = setOf(rows[7].id, odd.id)
        assertTrue(legacy.all { HistoryStore.seriesFileName(it) != it.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json" }, "the legacy names differ")
        val deleted = HistoryFixtures.as0100(HistoryFixtures.rows(1, samplesPer = 6, start = 1_700_000_000_000L)).single().copy(id = "deleted-in-0_10_0")
        HistoryFixtures.plantOldBuild(app, rows, inline = setOf(rows[1].id, rows[2].id), legacyName = legacy, previous = rows + deleted)
        val json = shotsJson.readBytes()
        val bak = shotsBak.readBytes()

        val vm = vm()
        val history = VmHarness.library(vm).historyStoreForTest
        assertTrue(history.onHistoryDb, "flipped at launch: ${history.flipError ?: history.lastError}")
        assertEquals(rows.map { it.id }.toSet(), VmHarness.truthIds(vm), "every shot on history.db, and not the one only the .bak holds")
        // After the flip: an upload binds a shot, and the user rates another.
        val stamped = rows[5]
        val rated = rows[8]
        assertEquals(null, stamped.visualizerId)
        VmHarness.library(vm).markShotSynced(stamped.id, "vis-5")
        vm.updateShot(rated.id, 4, "rated after the update")
        VmHarness.idleUntil("the edits on history.db") {
            VmHarness.truthField(vm, stamped.id, "visualizerId") == "vis-5" && VmHarness.truthField(vm, rated.id, "rating") == "4"
        }

        val again = relaunch()
        assertTrue(VmHarness.library(again).historyStoreForTest.onHistoryDb)
        val got = VmHarness.rows(again)
        assertEquals(rows.map { it.id }.toSet(), got.map { it.id }.toSet(), "every shot after a relaunch")
        for (r in rows) {
            val want = when (r.id) {
                stamped.id -> r.copy(visualizerId = "vis-5")
                rated.id -> r.copy(rating = 4, notes = "rated after the update")
                else -> r
            }
            val row = got.first { it.id == r.id }
            val have = row.joined(VmHarness.wholeOrNull(again, r.id) ?: error("shot ${r.id} doesn't read whole"))
            assertEquals(want, have, "shot ${r.id}: every field, profile and series")
        }
        assertTrue(json.contentEquals(shotsJson.readBytes()), "shots.json left as 0.10.0 wrote it")
        assertTrue(bak.contentEquals(shotsBak.readBytes()), "its .bak too")
    }
}
