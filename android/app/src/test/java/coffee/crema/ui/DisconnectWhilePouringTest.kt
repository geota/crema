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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A user Disconnect while the machine pours asks first (#320), on the core's
 * `disconnectNeedsConfirm`; otherwise it disconnects at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DisconnectWhilePouringTest {
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

    private fun state(vm: MainViewModel, state: Int, substate: Int) {
        val out = VmHarness.bridge(vm).onNotification(
            NotificationSource.DE1_STATE, byteArrayOf(state.toByte(), substate.toByte()), clock,
        )
        VmHarness.deliverCoreOutput(vm, out)
        clock += 100uL
        settle(vm)
    }

    private fun pouring(): MainViewModel {
        val vm = build()
        state(vm, IDLE, 0)
        state(vm, ESPRESSO, POURING)
        assertNotNull(vm.ui.value.machineStateName)
        return vm
    }

    @Test
    fun `a Disconnect while pouring asks, and Cancel keeps the link`() {
        val vm = pouring()
        vm.requestDisconnect()
        settle(vm)
        assertTrue(vm.ui.value.disconnectConfirm)
        assertNotNull(vm.ui.value.machineStateName, "not disconnected yet")

        vm.answerDisconnect(false)
        settle(vm)
        assertFalse(vm.ui.value.disconnectConfirm)
        assertNotNull(vm.ui.value.machineStateName, "still connected")
    }

    @Test
    fun `confirming disconnects`() {
        val vm = pouring()
        vm.requestDisconnect()
        settle(vm)
        vm.answerDisconnect(true)
        settle(vm)
        assertFalse(vm.ui.value.disconnectConfirm)
        assertNull(vm.ui.value.machineStateName)
    }

    @Test
    fun `a Disconnect at idle does not ask`() {
        val vm = build()
        state(vm, IDLE, 0)
        assertNotNull(vm.ui.value.machineStateName)
        vm.requestDisconnect()
        settle(vm)
        assertFalse(vm.ui.value.disconnectConfirm)
        assertNull(vm.ui.value.machineStateName)
    }

    private companion object {
        const val IDLE = 2
        const val ESPRESSO = 4
        const val POURING = 5
    }
}
