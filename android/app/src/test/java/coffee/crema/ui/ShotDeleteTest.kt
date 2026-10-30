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
}
