package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.history.seriesReadsForTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F10 validation P1-2, from #310 P4a: the startup shows the shot rows and
 * reads no series; a backup or an export started before the rows land waits
 * for them, then reads each shot whole, so no shot goes without its curve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SeriesLoadGateTest {
    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val store = ViewModelStore()

    @After fun tearDown() {
        HistoryStore.holdOpen = null
        store.clear()
        VmHarness.idle()
    }

    private val shot = StoredShot(
        id = "s1",
        completedAtMs = 1_700_000_000_000L,
        durationMs = 30_000,
        profileName = "Gate",
        samples = List(5) { n -> TelemetrySample(elapsedMs = n * 222L, pressure = 7.25f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null, dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1) },
    )

    private fun backupJsonl(zip: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: error("no backup.jsonl")
                if (e.name == "backup.jsonl") return z.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    @Test
    fun `a backup or an export before the rows land waits for them, then goes with every curve`() {
        runBlocking { HistoryStore(VmHarness.app(), coreJson).save(listOf(shot)) }
        val gate = CompletableDeferred<Unit>()
        HistoryStore.holdOpen = gate
        val vm = VmHarness.build(store)
        VmHarness.idleUntil("the prefs") { VmHarness.prefsLoaded(vm) && vm.startupLoad.isCompleted }
        assertFalse(vm.ui.value.historyLoaded, "the rows' load is still held")

        // The save buttons are refused, not handed a file without the shots.
        assertNull(VmHarness.shotsJson(vm))
        assertTrue(vm.ui.value.userMessages.last().startsWith("Shot data is still loading"))

        val backup = CoroutineScope(Dispatchers.Default).async { vm.buildBackupZipBytes() }
        Thread.sleep(200)
        VmHarness.idle()
        assertFalse(backup.isCompleted, "the backup waits for the rows")

        gate.complete(Unit)
        VmHarness.idleUntil("the backup") { backup.isCompleted }
        val line = backupJsonl(assertNotNull(runBlocking { backup.await() })).lines().single { "\"s1\"" in it }
        assertEquals(5, Regex("7\\.25").findAll(line).count(), "every sample is in the backup: $line")
        assertEquals(5u, vm.ui.value.history.single().summary.sampleCount)
        assertEquals(5, Regex("7\\.25").findAll(assertNotNull(VmHarness.shotsJson(vm))).count())
    }

    /** P4a's done-when: startup reads no series, and the rows hold none (the type has no field for one). */
    @Test
    fun `the startup reads no series and draws each row from its summary`() {
        val shots = List(40) { i -> shot.copy(id = "s$i", completedAtMs = shot.completedAtMs + i) }
        runBlocking { HistoryStore(VmHarness.app(), coreJson).save(shots) }
        val before = seriesReadsForTest.get()
        val vm = VmHarness.build(store)
        VmHarness.awaitStartupLoaded(vm)
        VmHarness.idleUntil("the rows") { vm.ui.value.historyLoaded }
        VmHarness.idle()
        assertEquals(before, seriesReadsForTest.get(), "no series file was read")
        assertEquals(40, vm.ui.value.history.size)
        assertTrue(vm.ui.value.history.all { it.summary.sampleCount == 5u && it.summary.spark != null }, "each row's chart and count come from its summary")
        // A shot opened reads its own series, and only it.
        assertEquals(5, VmHarness.whole(vm, "s7").samples.size)
        assertEquals(before + 1, seriesReadsForTest.get())
    }
}
