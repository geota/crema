package coffee.crema.ui

import android.app.Application
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A persisted primary starts hosting at launch. Its relay start used to run
 * inside ProxyController's own field init, while the VM was still being
 * constructed: when the server bound at once, the start's advertisement read
 * the VM's `ble` before it existed and the start was logged as failed. The
 * persisted role now starts from the VM's init, after every property exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProxyStartupOrderTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** A fresh factory: `getInstance` caches an earlier test's Application. */
    private fun build(store: ViewModelStore): MainViewModel =
        ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]

    /** The VM's [ProxyController], found by type rather than by field name. */
    private fun controllerOf(vm: MainViewModel): ProxyController {
        val field = MainViewModel::class.java.declaredFields.single { it.type == ProxyController::class.java }
        field.isAccessible = true
        return field.get(vm) as ProxyController
    }

    @Test
    fun aPersistedPrimaryStartsItsRelayAndAdvertisesItAtLaunch() {
        File(app.filesDir, "prefs.json").writeText("""{"proxyRole":"primary"}""")
        val store = ViewModelStore()
        val vm = build(store)
        val listening = Regex("LAN relay listening on :(\\d+)")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (vm.ui.value.eventLog.none { listening.containsMatchIn(it) }) {
            check(System.nanoTime() < deadline) { "the relay never listened: ${vm.ui.value.eventLog}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        val log = vm.ui.value.eventLog
        val port = log.firstNotNullOf { listening.find(it) }.groupValues[1].toInt()

        assertTrue(log.none { "LAN relay failed to start" in it }, "the relay start failed: $log")
        // The NSD advertisement carries the primary role and the relay's port.
        val nsd = shadowOf(app.getSystemService(NsdManager::class.java))
        val key = NsdServiceInfo().apply {
            serviceName = "Crema-${controllerOf(vm).instanceId.take(8)}"
            serviceType = "_crema._tcp."
        }
        val advertised = assertNotNull(nsd.getRegisteredServiceInfo(nsd.getRegistrationListener(key)), "nothing advertised")
        assertEquals(port, advertised.port)
        assertEquals("primary", advertised.attributes["role"]?.decodeToString())

        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
