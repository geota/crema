package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.settings.AppPrefs
import coffee.crema.ui.RemoteFixture.Companion.writePrefs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The weight-unit pref reaches the core on load and on change, as on web
 * (core/shell fixes AC-F4.1): the core picks a connected scale's LCD unit
 * from it, so an oz user's Decent Scale shows ounces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WeightUnitPushTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    /** The Decent Scale LCD-enable packet the core's connect handshake emits:
     *  byte 4 is the unit (0 grams, 1 ounces). */
    private fun lcdUnit(bridge: CremaBridge): String {
        val out = coreJson.decodeFromString(CoreOutput.serializer(), bridge.queryScaleSettings())
        val lcd = out.commands.filterIsInstance<Command.WriteScale>()
            .map { w -> w.content.data.map { it.toInt() } }
            .single { it.take(4) == listOf(0x03, 0x0A, 0x01, 0x01) }
        return when (lcd[4]) {
            0 -> "g"
            1 -> "oz"
            else -> error("unknown LCD unit byte ${lcd[4]}")
        }
    }

    @Test
    fun `weight unit reaches the core on load and on change`() {
        writePrefs(AppPrefs(weightUnit = "oz"))
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        val bridge = VmHarness.bridge(vm)
        assertNotNull(bridge.connectScale("Decent Scale", emptyList()), "a Decent Scale is identified")
        assertEquals("oz", lcdUnit(bridge), "the loaded pref reached the core")

        vm.setWeightUnit("g")
        VmHarness.idle()
        assertEquals("g", lcdUnit(bridge), "a change reaches the core")

        vm.setWeightUnit("oz")
        vm.resetPreferences()
        VmHarness.idle()
        assertEquals("g", lcdUnit(bridge), "restore defaults reaches the core")
    }
}
