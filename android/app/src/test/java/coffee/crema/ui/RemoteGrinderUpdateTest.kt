package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.settings.GrinderSnapshot
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * grinder-ble §6.2 (Later trims): a hub sends its grinder block on its own
 * while a collar turns, not the whole snapshot. A real remote VM against a
 * real hub ([RemoteFixture]): the frame moves the remote's grinder and
 * nothing else, and the next snapshot's block replaces it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RemoteGrinderUpdateTest {

    private val store = ViewModelStore()
    private val fixture = RemoteFixture(store)

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        fixture.kill()
    }

    @Test
    fun `a grinder frame moves only the remote's grinder`() {
        val vm = fixture.start()
        fixture.push()
        val before = VmHarness.ownState(vm).mirror!!
        assertNull(before.grinder)

        fixture.pushGrinder(GrinderSnapshot(connected = true, model = "Motto 80", follow = "Following", setting = "481", grinderName = "Motto 80"))
        VmHarness.idle()
        assertEquals("from Motto 80 on Kitchen tablet", vm.ui.value.grindFollowNote())
        val after = VmHarness.ownState(vm).mirror!!
        assertEquals(before.snapshot, after.snapshot, "the snapshot is untouched")
        assertEquals(before.lastRev, after.lastRev, "the next snapshot still applies")

        // Paused on the hub: the dial is the remote's again.
        fixture.pushGrinder(GrinderSnapshot(connected = true, model = "Motto 80", follow = "Paused", setting = "481", grinderName = "Motto 80"))
        VmHarness.idle()
        assertNull(vm.ui.value.grindFollowNote())

        // The next snapshot carries the hub's block too: none now.
        fixture.push()
        assertNull(VmHarness.ownState(vm).mirror!!.grinder)
    }
}
