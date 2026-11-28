package coffee.crema.ui.phone

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import coffee.crema.core.FollowStatus
import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder
import coffee.crema.core.GrinderFollowView
import coffee.crema.core.GrinderReading
import coffee.crema.core.GrinderSettings
import coffee.crema.core.GrinderState
import coffee.crema.core.ObservedSetting
import coffee.crema.ui.ConnectedGrinderUi
import coffee.crema.ui.GrinderLinkStatus
import coffee.crema.ui.LiveTelemetry
import coffee.crema.ui.MainUiState
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.grinderRailDot
import coffee.crema.ui.rowViewOf
import coffee.crema.ui.theme.CremaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The opt-in grinder row in the Devices sheet (phone and tablet) and its dot
 * on the rail: absent with the switch off or no Bluetooth grinder, and while
 * connected it says the grind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1280dp-h900dp")
class GrinderDevicesSheetTest {

    @get:Rule val compose = createComposeRule()

    private val motto = Grinder(id = "grinder:motto", name = "Motto 80", scale = GrindScale(0f, 999f, 1f, "μm"), presetId = "bookoo-motto-80", updatedAt = 1L)
    private val niche = Grinder(id = "grinder:niche", name = "Niche Zero", scale = GrindScale(0f, 50f, 1f, ""), presetId = "niche-zero", updatedAt = 1L)
    private val live = MutableStateFlow(LiveTelemetry())
    private val calls = mutableListOf<String>()
    private val hooks = GrinderDeviceHooks(
        live = live,
        onConnect = { calls += "connect" },
        onReconnect = { calls += "reconnect" },
        onDisconnect = { calls += "disconnect" },
        onRetry = { calls += "retry" },
        onForget = { calls += "forget" },
    )

    private fun state(on: Boolean, vararg grinders: Grinder, status: GrinderLinkStatus = GrinderLinkStatus.OFF, grind: String? = null) = MainUiState(
        grinderInDevices = on,
        grinders = rowViewOf(grinders.toList()),
        connectedGrinder = ConnectedGrinderUi(status = status),
        connectedGrinderAddress = if (status == GrinderLinkStatus.LIVE) "AA:BB:CC:DD:EE:01" else null,
        grinderFollow = GrinderFollowView(status = if (grind != null) FollowStatus.Following else FollowStatus.Off, grind = grind),
    )

    private fun reading(dial: String) = GrinderReading(
        at_ms = 1L, state = GrinderState.Idle, raw_state = "idle", setting = ObservedSetting(dial), settings = GrinderSettings(),
    )

    @Composable
    private fun Panel(ui: MainUiState, wide: Boolean) {
        CremaTheme {
            if (wide) {
                DevicesPanelWide(
                    ui = ui, connected = false, scaleConnected = false, onConnect = {}, onForgetDe1 = {}, onForgetScale = {},
                    onMirrorFrom = { _, _ -> }, onStopMirroring = {}, onConnectDirectly = {}, onPairAgain = {}, onAllowLocalNetwork = {}, grinder = hooks,
                )
            } else {
                DevicesPanel(
                    ui = ui, connected = false, scaleConnected = false, onConnect = {}, onForgetDe1 = {}, onForgetScale = {},
                    onMirrorFrom = { _, _ -> }, onStopMirroring = {}, onConnectDirectly = {}, onPairAgain = {}, onAllowLocalNetwork = {}, grinder = hooks,
                )
            }
        }
    }

    private var wideNow by mutableStateOf(false)

    private fun show(ui: MainUiState, wide: Boolean) {
        wideNow = wide
        compose.setContent { Panel(ui, wideNow) }
        compose.waitForIdle()
    }

    @Test
    fun `switch off, no grinder row on the phone or the tablet`() {
        show(state(false, motto, status = GrinderLinkStatus.LIVE, grind = "2.4"), wide = false)
        for (wide in listOf(false, true)) {
            wideNow = wide
            compose.waitForIdle()
            compose.onNodeWithText("Motto 80").assertDoesNotExist()
            compose.onNodeWithText("Connected · Grind 2.4").assertDoesNotExist()
        }
    }

    @Test
    fun `switch on without a Bluetooth grinder, no grinder row`() {
        show(state(true, niche), wide = false)
        for (wide in listOf(false, true)) {
            wideNow = wide
            compose.waitForIdle()
            compose.onNodeWithText("Niche Zero").assertDoesNotExist()
            compose.onNodeWithText("GRINDER").assertDoesNotExist()
            compose.onNodeWithText("BETA").assertDoesNotExist()
        }
    }

    @Test
    fun `switch on with a Bluetooth grinder, the phone row says the grind and Beta and disconnects`() {
        show(state(true, niche, motto, status = GrinderLinkStatus.LIVE, grind = "2.4"), wide = false)
        compose.onNodeWithText("GRINDER").assertExists()
        compose.onNodeWithText("BETA").assertExists()
        compose.onNodeWithText("Motto 80").assertExists()
        compose.onNodeWithText("Connected · Grind 2.4").assertExists()
        compose.onNodeWithText("Disconnect").performClick()
        assertEquals(listOf("disconnect"), calls)
    }

    @Test
    fun `the tablet tile follows the live dial when nothing is followed`() {
        live.value = LiveTelemetry(grinderReading = reading("480"))
        show(state(true, motto, status = GrinderLinkStatus.LIVE), wide = true)
        compose.onNodeWithText("Motto 80").assertExists()
        compose.onNodeWithText("Connected · Grind 480").assertExists()
        live.value = LiveTelemetry(grinderReading = reading("482"))
        compose.waitForIdle()
        compose.onNodeWithText("Connected · Grind 482").assertExists()
    }

    @Test
    fun `not connected, the row connects`() {
        show(state(true, motto), wide = false)
        compose.onAllNodesWithText("Not connected").assertCountEquals(2) // the DE1's and the grinder's
        compose.onNodeWithText("Connect").performClick()
        assertEquals(listOf("connect"), calls)
    }

    @Test
    fun `the rail gets a grinder dot under Scale only when the row shows`() {
        var ui by mutableStateOf(state(false, motto, status = GrinderLinkStatus.LIVE))
        val taps = mutableListOf<String>()
        compose.setContent {
            CremaTheme {
                Box(Modifier.height(900.dp)) {
                    CremaNavigationRail(
                        active = "brew", onNav = {}, machineConnected = false, scaleConnected = false,
                        onConnect = { taps += it }, grinderDot = ui.grinderRailDot(),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("SCALE").assertExists()
        compose.onNodeWithText("GRINDER").assertDoesNotExist()
        ui = state(true, niche)
        compose.waitForIdle()
        compose.onNodeWithText("GRINDER").assertDoesNotExist()
        ui = state(true, motto, status = GrinderLinkStatus.LIVE)
        compose.waitForIdle()
        compose.onNodeWithText("GRINDER").performClick()
        assertEquals(listOf("grinder"), taps)
    }
}
