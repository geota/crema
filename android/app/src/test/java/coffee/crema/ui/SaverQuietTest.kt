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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behind the screensaver nothing live is published (cohort review 2026-10-25,
 * Android P2-4): the whole screen tree used to recompose several times a
 * second for hours under the saver. Anything else still publishes, and the
 * saver going away shows the latest readouts at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SaverQuietTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private var clock = 1_000uL

    private fun build(): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    private fun settle(vm: MainViewModel) {
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
    }

    private fun state(vm: MainViewModel, code: Int) {
        VmHarness.deliverCoreOutput(vm, VmHarness.bridge(vm).onNotification(NotificationSource.DE1_STATE, byteArrayOf(code.toByte(), 0), clock))
        clock += 1_000uL
        settle(vm)
    }

    private fun events(vm: MainViewModel, vararg events: String) {
        VmHarness.deliverCoreOutput(vm, """{"events":[${events.joinToString(",")}],"commands":[]}""")
        settle(vm)
    }

    /** A VM with the saver up over an external sleep. */
    private fun behindTheSaver(): MainViewModel {
        val vm = build()
        state(vm, IDLE)
        state(vm, SLEEP)
        assertTrue(vm.ui.value.saverVisible)
        return vm
    }

    @Test
    fun `live values are quiet behind the saver`() {
        val vm = behindTheSaver()
        val shown = vm.live.value
        events(
            vm,
            telemetrySample(pressure = 8.8f),
            """{"type":"ScaleReading","content":{"weight":18.0,"flow":1.5}}""",
            """{"type":"WaterLevel","content":{"level":40.0,"refill_threshold":5.0}}""",
            """{"type":"ShotStarted"}""",
        )
        assertEquals(shown, vm.live.value, "nothing live reaches the screens")
        assertEquals(8.8f, vm.liveStore.value.pressure, "the store keeps up")
        assertEquals(18f, vm.liveStore.value.scaleWeightG)
        assertEquals(40f, vm.liveStore.value.waterLevelMm)
    }

    @Test
    fun `anything else still publishes behind the saver`() {
        val vm = behindTheSaver()
        events(vm, """{"type":"MachineErrorChanged","content":{"state":"Sleep","substate":"Ready","message":"No water"}}""")
        assertEquals("No water", vm.ui.value.machineError)
    }

    @Test
    fun `the latest values appear the moment the saver goes`() {
        val vm = behindTheSaver()
        events(vm, telemetrySample(pressure = 1f), telemetrySample(pressure = 2f))
        vm.dismissSaver()
        settle(vm)
        assertFalse(vm.ui.value.saverVisible)
        assertEquals(2f, vm.live.value.pressure)

        events(vm, telemetrySample(pressure = 3f))
        assertEquals(3f, vm.live.value.pressure, "and they stay live after it")
    }

    private companion object {
        const val SLEEP = 0
        const val IDLE = 2
    }
}
