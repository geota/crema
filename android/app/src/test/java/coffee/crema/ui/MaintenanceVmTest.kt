package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.NotificationSource
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The water a pour counts reaches the maintenance state (MainViewModel
 * breakup step 2): the end of a water session flushes the live total into
 * the persisted state and its readout, and a mark action rebaselines to the
 * live total, read on the core lane where it is counted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MaintenanceVmTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    /** The core's own test `ShotSample` (positive group flow). */
    private val sample = intArrayOf(
        0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5C, 0x80, 0x00, 0x58, 0x00, 0x5A, 0x00, 0x5D, 0x00,
        0x60, 0x40, 0x03, 0x96,
    ).map { it.toByte() }.toByteArray()

    private fun settle(vm: MainViewModel) {
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
    }

    @Test
    fun `a hot-water pour is flushed on its end and a filter mark rebaselines to it`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        settle(vm)
        val bridge = VmHarness.bridge(vm)
        val start = vm.ui.value.maintenance.totalLitres
        // Hot water (6), two samples, then Idle (2): one water session.
        VmHarness.deliverCoreOutput(vm, bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(6, 5), 1_000uL))
        VmHarness.deliverCoreOutput(vm, bridge.onNotification(NotificationSource.DE1_SHOT_SAMPLE, sample, 1_000uL))
        VmHarness.deliverCoreOutput(vm, bridge.onNotification(NotificationSource.DE1_SHOT_SAMPLE, sample, 1_250uL))
        VmHarness.deliverCoreOutput(vm, bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), 2_000uL))
        settle(vm)
        val counted = VmHarness.maintenance(vm).countedLitres
        assertTrue(counted > start, "the pour counted water")
        assertEquals(counted, vm.ui.value.maintenance.totalLitres, "the session's end flushed the total")
        assertTrue(vm.ui.value.maintenanceReadout != null, "and recomputed the readout")

        vm.markFilterCleaned()
        settle(vm)
        val m = vm.ui.value.maintenance
        assertEquals(counted, m.filterBaselineLitres)
        assertEquals(counted, m.totalLitres)
    }
}
