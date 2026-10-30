package coffee.crema.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F16a: the view model arms one wake tick for the core's `WakeAt` and drops
 *  it when the app goes to the background. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WakeAtVmTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private fun deliver(vm: MainViewModel, json: String) {
        val apply = MainViewModel::class.java.getDeclaredMethod("applyCoreOutputJson", String::class.java)
        apply.isAccessible = true
        apply.invoke(vm, json)
    }

    @Test
    fun `a WakeAt arms one tick, and backgrounding cancels it`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        val at = SystemClock.elapsedRealtime() + 60_000
        deliver(vm, """{"events":[{"type":"WakeAt","content":{"at_ms":$at.0}}],"commands":[]}""")
        assertTrue(vm.wakePending)
        vm.setAppForeground(false)
        assertFalse(vm.wakePending, "no wake-up tick in the background")
        vm.setAppForeground(true)
    }

    /** F16a validation: a refused, held or dropped request is told to the
     *  user (the events decode, and each becomes a notice). */
    @Test
    fun `refused, held and dropped requests are told to the user`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        deliver(
            vm,
            """{"events":[
              {"type":"MachineRequestRefused","content":{"state":"Descale","reason":{"kind":"MachineNotIdle","details":{"state":"Espresso"}}}},
              {"type":"MachineRequestHeld","content":{"state":"Steam"}},
              {"type":"MachineRequestDropped","content":{"state":"Steam","reason":{"kind":"StateChanged","details":{"from":"Idle","to":"Espresso"}}}},
              {"type":"MachineRequestDropped","content":{"state":"HotWater","reason":{"kind":"TookTooLong"}}}
            ],"commands":[]}""",
        )
        VmHarness.idle()
        val messages = vm.ui.value.userMessages
        assertTrue(messages.any { it == "Descale needs the DE1 idle or asleep (it is in Espresso)." }, "$messages")
        assertTrue(messages.any { it == REQUEST_HELD_TEXT }, "$messages")
        assertTrue(messages.any { it == "Steam cancelled: a shot started" }, "$messages")
        assertTrue(messages.any { it.startsWith("HotWater cancelled: the profile is still loading") }, "$messages")
    }
}
