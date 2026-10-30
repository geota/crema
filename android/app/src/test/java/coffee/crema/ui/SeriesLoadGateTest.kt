package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
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
 * F10 validation P1-2: the startup shows the shot rows at once and loads their
 * telemetry in the background; a backup or an export started in that window
 * waits for it instead of saving the shots without their curves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SeriesLoadGateTest {
    private val store = ViewModelStore()

    @After fun tearDown() {
        HistoryStore.holdSeriesLoad = null
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
    fun `a backup or an export during the series load waits for the full series`() {
        runBlocking { HistoryStore(VmHarness.app(), coreJson).save(listOf(shot)) }
        val gate = CompletableDeferred<Unit>()
        HistoryStore.holdSeriesLoad = gate
        val vm = VmHarness.build(store)
        VmHarness.awaitStartupLoaded(vm)
        assertTrue(vm.ui.value.history.single().samples.isEmpty(), "the series load is still held")

        // The save buttons are refused, not handed a file without curves.
        assertNull(vm.shotsJson(null))
        assertTrue(vm.ui.value.userMessages.last().startsWith("Shot data is still loading"))

        val backup = CoroutineScope(Dispatchers.Default).async { vm.buildBackupZipBytes() }
        Thread.sleep(200)
        VmHarness.idle()
        assertFalse(backup.isCompleted, "the backup waits for the series")

        gate.complete(Unit)
        VmHarness.idleUntil("the backup") { backup.isCompleted }
        val line = backupJsonl(assertNotNull(runBlocking { backup.await() })).lines().single { "\"s1\"" in it }
        assertEquals(5, Regex("7\\.25").findAll(line).count(), "every sample is in the backup: $line")
        assertEquals(5, vm.ui.value.history.single().samples.size)
        assertEquals(5, Regex("7\\.25").findAll(assertNotNull(vm.shotsJson(null))).count())
    }
}
