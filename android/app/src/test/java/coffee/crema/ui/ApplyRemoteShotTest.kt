package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.ShotPatch
import coffee.crema.history.StoredShot
import coffee.crema.visualizer.PulledParts
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * A pull's merge lands in one write on the row as it stands, and changes only
 * what the core's `merge_remote_shot` may: tags it adds, a blank barista, a
 * missing curve, and the remote version. A rating or a bean can't change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ApplyRemoteShotTest {
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private val sample = TelemetrySample(
        elapsedMs = 0, pressure = 9f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null,
        dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f,
    )

    @Test
    fun `the merge lands in one write and touches nothing else`() {
        val vm = VmHarness.build(store)
        VmHarness.awaitStartupLoaded(vm)
        val library = VmHarness.library(vm)
        val row = StoredShot(
            id = "s1", completedAtMs = 1, durationMs = 0, rating = 4, notes = "juicy", tags = listOf("washed"), visualizerId = "v-1",
        )
        library.insertPulledShots(listOf(row))
        VmHarness.idleUntil("the row") { vm.ui.value.history.any { it.id == "s1" } }

        library.applyRemoteShot(
            "s1",
            ShotPatch(tags = listOf("washed", "gesha"), barista = "Ana", backfillSamples = true, fillProfile = false, remoteVersion = 1_700_000_123),
            PulledParts(samples = listOf(sample), durationMs = 30_000),
        )

        val after = vm.ui.value.history.single { it.id == "s1" }
        assertEquals(listOf("washed", "gesha"), after.tags)
        assertEquals("Ana", after.barista)
        assertEquals(1, after.samples.size)
        assertEquals(30_000, after.durationMs)
        assertEquals(1_700_000_123, after.visualizerUpdatedAt)
        assertEquals(4, after.rating)
        assertEquals("juicy", after.notes)

        // A barista set here is never replaced.
        library.applyRemoteShot("s1", ShotPatch(barista = "Bo"), PulledParts())
        assertEquals("Ana", vm.ui.value.history.single { it.id == "s1" }.barista)
    }
}
