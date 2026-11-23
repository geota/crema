package coffee.crema.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import coffee.crema.ui.phone.PhoneScaleScreen
import coffee.crema.ui.screens.ScaleScreen
import coffee.crema.ui.theme.CremaTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Devices' Scale | Grinder tabs (#488 follow-up 23): on the emulator the
 * first tap on "Grinder" right after opening Devices didn't switch tabs (a
 * second tap did). Devices is opened through the tablet's nav host from
 * Brew, and the tab is tapped at once, while the host's transition still
 * runs, on the tablet and the phone screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DevicesTabTest {

    @get:Rule val compose = createComposeRule()

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    @Test
    fun `the first tap on Grinder right after opening Devices switches the tab, on the tablet`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        var open: ((String) -> Unit)? = null
        compose.setContent {
            CremaTheme {
                AppNavHost(
                    machineConnected = false,
                    scaleConnected = false,
                    onRailConnect = {},
                    brewContent = { navTo -> open = navTo },
                    profilesContent = { _, _ -> },
                    beansContent = {},
                    historyContent = {},
                    scaleContent = { navTo -> ScaleScreen(vm, onNav = navTo, onConnect = {}) },
                    settingsContent = {},
                    beanEditContent = {},
                    profileEditContent = {},
                    debugContent = {},
                )
            }
        }
        compose.waitForIdle()
        // Open Devices, then tap Grinder before the transition settles.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { open!!("scale") }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Grinder").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("grinder", vm.ui.value.devicesTab, "one tap switches the tab")
        compose.onNodeWithText("Your grinders").assertExists()
    }

    @Test
    fun `the first tap on Grinder switches the tab, on the phone`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        compose.setContent {
            CremaTheme { PhoneScaleScreen(vm, onNav = {}, onConnect = {}) }
        }
        compose.onNodeWithText("Grinder").performClick()
        compose.waitForIdle()
        assertEquals("grinder", vm.ui.value.devicesTab, "one tap switches the tab")
        compose.onNodeWithText("Your grinders").assertExists()
    }
}
