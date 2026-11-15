package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.history.StoredShot
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deleting a shot on Android (core/shell fixes F10 PR 4): a shot never
 * uploaded is gone at once and leaves nothing behind; a synced shot leaves
 * the history at once too, its tombstone held only by the Visualizer sync,
 * so no screen, stat, upload or export ever sees it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShotDeleteTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    @Test
    fun `an unsynced delete is immediate and tombstones stay hidden`() {
        val shots = listOf(
            StoredShot(id = "synced", completedAtMs = 2, durationMs = 30_000, visualizerId = "v-1"),
            StoredShot(id = "local", completedAtMs = 1, durationMs = 30_000),
        )
        File(VmHarness.app().filesDir, "shots.json")
            .writeText(Json.encodeToString(ListSerializer(StoredShot.serializer()), shots))
        val vm = VmHarness.build(store)
        VmHarness.idleUntil("the history load", 10_000) { vm.ui.value.history.size == 2 }

        vm.deleteShot("local")
        VmHarness.idle()
        assertEquals(listOf("synced"), vm.ui.value.history.map { it.id })
        assertTrue(vm.visualizer.shotTombstones.isEmpty(), "a never-uploaded shot leaves no tombstone")

        vm.deleteShot("synced")
        VmHarness.idleUntil("the tombstone", 10_000) { vm.visualizer.shotTombstones.isNotEmpty() }
        assertTrue(vm.ui.value.history.isEmpty(), "the tombstone is not in the history")
        val t = vm.visualizer.shotTombstones.single()
        assertEquals("synced" to "v-1", t.id to t.visualizerId)
        assertEquals(false, t.deleteRemote, "a plain delete keeps the remote copy")
    }

    /** #310 P4a: a deleted shot can't be read whole any more, and its curve goes with it. */
    @Test
    fun `a deleted shot leaves no curve and can't be read whole`() {
        val sample = TelemetrySample(
            elapsedMs = 0, pressure = 9f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null,
            dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1,
        )
        val shots = listOf(
            StoredShot(id = "keep", completedAtMs = 2, durationMs = 30_000, samples = listOf(sample, sample.copy(elapsedMs = 250))),
            StoredShot(id = "gone", completedAtMs = 1, durationMs = 30_000, samples = listOf(sample, sample.copy(elapsedMs = 250))),
        )
        coffee.crema.persist.SafeFile.openAllForTest()
        kotlinx.coroutines.runBlocking { coffee.crema.history.HistoryStore(VmHarness.app(), coreJson).save(shots) }
        val vm = VmHarness.build(store)
        VmHarness.idleUntil("the history load", 10_000) { vm.ui.value.history.size == 2 }
        assertEquals(2, VmHarness.whole(vm, "gone").samples.size, "read whole before the delete")

        vm.deleteShot("gone")
        val gz = coffee.crema.history.SeriesFiles(File(VmHarness.app().filesDir, "shot-series"), coreJson).gzFile("gone")
        VmHarness.idleUntil("the delete", 10_000) { !gz.exists() }
        assertEquals(listOf("keep"), vm.ui.value.history.map { it.id })
        assertEquals(null, VmHarness.wholeOrNull(vm, "gone"), "no shot to read")
        assertEquals(2, VmHarness.whole(vm, "keep").samples.size, "the other keeps its curve")
    }
}
