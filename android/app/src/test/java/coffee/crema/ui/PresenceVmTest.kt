package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.NotificationSource
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The screensaver's coupling to machine sleep, through the view model
 * (MainViewModel breakup step 5): an external sleep raises the saver, the
 * first report after connect and a sleep the app asked for don't, and the
 * machine waking on its own dismisses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PresenceVmTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private var clock = 1_000uL

    /** One DE1 state report (state, substate) through the VM's core lane. */
    private fun state(vm: MainViewModel, code: Int) {
        val raw = VmHarness.bridge(vm).onNotification(NotificationSource.DE1_STATE, byteArrayOf(code.toByte(), 0), clock)
        clock += 1_000uL
        VmHarness.deliverCoreOutput(vm, raw)
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
    }

    private fun build(): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    @Test
    fun `an external sleep raises the saver and an external wake dismisses it`() {
        val vm = build()
        state(vm, SLEEP)
        assertFalse(vm.ui.value.saverVisible, "the first report after connect never raises it")
        state(vm, IDLE)
        state(vm, SLEEP)
        assertTrue(vm.ui.value.saverVisible, "an external sleep raises it")
        state(vm, IDLE)
        assertFalse(vm.ui.value.saverVisible, "the machine waking on its own dismisses it")
    }

    @Test
    fun `a sleep the app asked for does not raise the saver`() {
        val vm = build()
        state(vm, IDLE)
        vm.sleep()
        VmHarness.idle()
        state(vm, SLEEP)
        assertFalse(vm.ui.value.saverVisible)
    }

    @Test
    fun `dismissing the saver clears it`() {
        val vm = build()
        state(vm, IDLE)
        state(vm, SLEEP)
        assertTrue(vm.ui.value.saverVisible)
        vm.dismissSaver()
        VmHarness.idle()
        assertFalse(vm.ui.value.saverVisible)
    }

    private companion object {
        const val SLEEP = 0
        const val IDLE = 2
    }
}
