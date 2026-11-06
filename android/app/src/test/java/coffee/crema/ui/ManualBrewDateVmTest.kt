package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.ui.brewlog.BrewLogOwner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dating a logged brew through the real controllers (#300): the form saves its
 * date, a saved date moves the row, and a future one is refused in both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ManualBrewDateVmTest {
    private val store = ViewModelStore()
    private val now = 1_700_000_000_000
    private val hour = 3_600_000L

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private fun vm(): MainViewModel = VmHarness.build(store).also {
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(it)
    }

    private fun history(vm: MainViewModel) = VmHarness.ownState(vm).history

    /** Log a brew through the form, dated [at] (null = now). */
    private fun log(vm: MainViewModel, at: Long?, dose: Double = 15.0, nowMs: Long = now): StoredShot {
        vm.openLogBrew(BrewLogOwner.HISTORY)
        vm.updateLogBrew { it.copy(method = "pourover", dose = dose, completedAtMs = at) }
        assertTrue(vm.saveLogBrew(nowMs), "the form saved")
        return history(vm).first { it.doseG == dose.toFloat() }
    }

    @Test
    fun `an untouched date is the moment of saving`() {
        val vm = vm()
        assertEquals(now, log(vm, at = null).completedAtMs)
    }

    @Test
    fun `a backdated brew sorts by its date and isn't the newest`() {
        val vm = vm()
        log(vm, at = now, dose = 15.0)
        log(vm, at = now - 24 * hour, dose = 30.0)
        assertEquals(listOf(15f, 30f), history(vm).map { it.doseG })
    }

    @Test
    fun `the next log seeds from the newest brew, not the backdated one`() {
        val vm = vm()
        log(vm, at = now, dose = 15.0)
        log(vm, at = now - 24 * hour, dose = 30.0)
        vm.openLogBrew(BrewLogOwner.HISTORY)
        vm.reseedLogBrew("pourover")
        assertEquals(15.0, assertNotNull(vm.logBrew.value).dose)
    }

    @Test
    fun `a future date keeps the form open and saves nothing`() {
        val vm = vm()
        vm.openLogBrew(BrewLogOwner.HISTORY)
        vm.updateLogBrew { it.copy(method = "pourover", dose = 15.0, completedAtMs = now + 60_000) }
        assertFalse(vm.saveLogBrew(now))
        assertTrue(assertNotNull(vm.logBrew.value).attempted)
        assertEquals(emptyList(), history(vm))
    }

    @Test
    fun `changing a saved date moves the row and survives a reload`() {
        val vm = vm()
        val moved = log(vm, at = now, dose = 15.0)
        val other = log(vm, at = now - hour, dose = 30.0)
        vm.updateManualBrew(moved.id, completedAtMs = now - 2 * hour)
        VmHarness.idle()
        assertEquals(listOf(other.id, moved.id), history(vm).map { it.id })
        assertEquals(now - 2 * hour, history(vm).last().completedAtMs)
        val stored = HistoryStore(VmHarness.app(), Json { ignoreUnknownKeys = true })
        VmHarness.idleUntil("the history saved") {
            runBlocking { stored.loadRows() }.map { it.id } == listOf(other.id, moved.id)
        }
    }

    @Test
    fun `a saved brew can't be dated in the future`() {
        val vm = vm()
        val row = log(vm, at = now)
        vm.updateManualBrew(row.id, completedAtMs = System.currentTimeMillis() + 60_000)
        assertEquals(now, history(vm).single().completedAtMs)
    }

    @Test
    fun `a date edit leaves the bag alone`() {
        val vm = vm()
        val row = log(vm, at = now)
        val before = VmHarness.ownState(vm).beans
        vm.updateManualBrew(row.id, completedAtMs = now - hour)
        assertEquals(before, VmHarness.ownState(vm).beans)
    }

    @Test
    fun `a machine shot's date can't be changed`() {
        val vm = vm()
        val machine = StoredShot(id = "machine", completedAtMs = now, durationMs = 30_000)
        VmHarness.library(vm).insertPulledShots(listOf(machine))
        VmHarness.idle()
        vm.updateManualBrew("machine", completedAtMs = now - hour)
        assertEquals(now, history(vm).single().completedAtMs)
        assertNull(history(vm).single().brewMethod)
    }
}
