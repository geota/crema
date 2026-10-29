package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.PairedDevice
import coffee.crema.ui.RemoteFixture.Companion.savedPrefs
import coffee.crema.ui.RemoteFixture.Companion.writePrefs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Normal devices and hubs keep today's settings behaviour (spec §7.4, AC-16, AC-32). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StandaloneSettingsTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    /** AC-32 (review-v2 6): a reset restores the preferences, not the role,
     *  the hub target, the paired devices or the DE1 / scale bindings. */
    @Test
    fun `reset keeps role and pairing`() {
        val paired = listOf(PairedDevice("phone-1", "Phone", canControl = true))
        writePrefs(
            AppPrefs(
                themeMode = "light", weightUnit = "oz", stopOnWeight = false, idleSleepMin = 30,
                proxyRole = "primary", proxyPrimaryHost = "192.168.1.20", proxyPrimaryPort = 8765,
                pairedDevices = paired, de1Address = "AA:BB:CC:DD:EE:01", scaleAddress = "AA:BB:CC:DD:EE:02", scaleName = "Bookoo",
            ),
        )
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        vm.resetPreferences()
        VmHarness.idleUntil("the reset saved") { savedPrefs().themeMode == AppPrefs().themeMode }
        val saved = savedPrefs()
        assertEquals(AppPrefs().weightUnit, saved.weightUnit)
        assertEquals(AppPrefs().stopOnWeight, saved.stopOnWeight)
        assertEquals("primary", saved.proxyRole)
        assertEquals("192.168.1.20", saved.proxyPrimaryHost)
        assertEquals(8765, saved.proxyPrimaryPort)
        assertEquals(paired, saved.pairedDevices)
        assertEquals("AA:BB:CC:DD:EE:01", saved.de1Address)
        assertEquals("AA:BB:CC:DD:EE:02", saved.scaleAddress)
        assertEquals("Bookoo", saved.scaleName)
    }
}
