package coffee.crema.ble

import android.content.ContextWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The keep-alive service's bookkeeping (cohort review 2026-10-25, Android
 * P2-2). Ending a firmware flash used to RESTART the running service just to
 * change its notification text; a refused background restart then cleared
 * `running` while the old instance kept its wake lock, and no gate change
 * could stop it any more.
 */
class BackgroundConnectionControllerTest {

    private class FakeService : KeepAliveService {
        override var wanted = false
        override var inForeground = false
        var refuseStarts = false
        val starts = mutableListOf<Boolean>()
        val notificationUpdates = mutableListOf<Boolean>()
        var stops = 0

        override fun start(firmware: Boolean): Boolean {
            starts += firmware
            if (refuseStarts) return false
            inForeground = true // the service's onStartCommand
            return true
        }

        override fun stop() {
            stops++
            inForeground = false
        }

        override fun updateNotification(firmware: Boolean) {
            notificationUpdates += firmware
        }
    }

    // A stub context: with unitTests.isReturnDefaultValues every framework
    // call returns null/0, so "plugged in" starts false and receivers no-op.
    private class StubContext : ContextWrapper(null) {
        override fun getApplicationContext() = this
        override fun getPackageName() = "coffee.crema.test"
    }

    private fun controller(service: FakeService) =
        BackgroundConnectionController(StubContext(), service).apply { start() }

    @Test
    fun flashEndSwapsTheNotificationInPlaceInsteadOfRestarting() {
        val svc = FakeService()
        val c = controller(svc)
        c.setEnabled(true)
        c.setDe1Active(true)
        c.onPowerChanged(true)
        assertEquals(listOf(false), svc.starts)

        c.setFirmwareFlashActive(true)
        c.setForeground(false) // the flash runs 15–25 min; the user wanders off
        c.setFirmwareFlashActive(false)

        assertEquals(listOf(false), svc.starts, "no second startForegroundService")
        assertEquals(listOf(true, false), svc.notificationUpdates)
        // Still tracked: unplugging stops it (and its wake lock).
        c.onPowerChanged(false)
        assertEquals(1, svc.stops)
        assertTrue(!svc.wanted)
    }

    @Test
    fun aRefusedStartWhileAnInstanceIsForegroundKeepsTrackingIt() {
        val svc = FakeService()
        val c = controller(svc)
        c.setEnabled(true)
        c.setDe1Active(true)
        // An earlier instance is still foreground (a stop that waited for its
        // startForeground), and the platform refuses this start.
        svc.inForeground = true
        svc.refuseStarts = true
        c.onPowerChanged(true)
        assertEquals(1, svc.starts.size)
        assertTrue(svc.wanted)

        c.onPowerChanged(false)
        assertEquals(1, svc.stops, "the running instance is still stoppable")
    }

    @Test
    fun aRefusedStartWithNothingRunningIsForgotten() {
        val svc = FakeService()
        svc.refuseStarts = true
        val c = controller(svc)
        c.setEnabled(true)
        c.setDe1Active(true)
        c.onPowerChanged(true)
        assertTrue(!svc.wanted)
        // Next foreground entry retries.
        svc.refuseStarts = false
        c.setForeground(false)
        c.setForeground(true)
        assertEquals(2, svc.starts.size)
    }
}
