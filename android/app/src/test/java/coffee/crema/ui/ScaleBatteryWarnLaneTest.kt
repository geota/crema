package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals

/**
 * The scale's low-battery warning is once per connection (issue #29), and its
 * flag is lane-owned (cohort review 2026-10-25, Android P2-3): a disconnect on
 * Main re-arms it through the core lane, behind any reading still queued
 * there. Written straight from Main, the reset ran ahead of a queued reading
 * from the old connection, which then warned a second time and used up the
 * next connection's warning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ScaleBatteryWarnLaneTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private fun warnings(vm: MainViewModel) = vm.ui.value.userMessages.count { it.startsWith("Scale battery low") }

    private fun settle(vm: MainViewModel) {
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
    }

    @Test
    fun `a reading queued before the disconnect neither warns again nor uses up the next warning`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        VmHarness.deliverCoreOutput(vm, LOW)
        settle(vm)
        assertEquals(1, warnings(vm), "the first low reading warns")

        // Hold the lane, queue a reading of the old connection behind the
        // hold, and disconnect the scale on Main meanwhile.
        val hold = CountDownLatch(1)
        VmHarness.coreLane(vm).post { hold.await() }
        VmHarness.deliverCoreOutput(vm, LOW)
        vm.disconnectScale()
        hold.countDown()
        settle(vm)
        assertEquals(1, warnings(vm), "the old connection's queued reading does not warn again")

        VmHarness.deliverCoreOutput(vm, LOW)
        settle(vm)
        assertEquals(2, warnings(vm), "the next connection warns once")
    }

    private companion object {
        val LOW = """{"events":[{"type":"ScaleReading","content":{"weight":0.0,"flow":0.0,"device_battery":10}}],"commands":[]}"""
    }
}
