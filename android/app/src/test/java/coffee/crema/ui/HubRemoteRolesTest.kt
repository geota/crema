package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.proxy.DeviceInfo
import coffee.crema.ble.proxy.MockWsServerLink
import coffee.crema.ble.proxy.Peer
import coffee.crema.ble.proxy.RelayHub
import coffee.crema.settings.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hub and remote roles on a real [MainViewModel]: the Hub mode switch
 * applied live (off frees the machine), the state a normal device derives from
 * a discovered hub ([MainUiState.hubHoldingMachine]), and a remote's status
 * from its hub's Welcome and roster, against a real [RelayHub] behind a
 * MockWebServer WebSocket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HubRemoteRolesTest {

    private val store = ViewModelStore()
    private var server: MockWebServer? = null
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        hubScope.cancel()
        server?.close()
    }

    private fun build(role: String = "normal", host: String = "", port: Int = 0): MainViewModel {
        if (role != "normal") VmHarness.persistRole(role, host, port)
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        return vm
    }

    private fun de1(vm: MainViewModel): De1BleManager {
        val field = MainViewModel::class.java.declaredFields.singleOrNull { it.type == De1BleManager::class.java }
            ?: error("MainViewModel no longer holds exactly one De1BleManager")
        field.isAccessible = true
        return field.get(vm) as De1BleManager
    }

    private fun role(vm: MainViewModel) = VmHarness.proxyController(vm).state.value.role

    /** The role in `prefs.json`, or null before the first save lands (it is
     *  written off the main thread). */
    private fun persistedRole(): String? = runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString(AppPrefs.serializer(), File(VmHarness.app().filesDir, "prefs.json").readText()).proxyRole
    }.getOrNull()

    /** AC-6: on and off, live, persisted, with no restart. */
    @Test
    fun `hub mode switches live in both directions`() {
        val vm = build()
        vm.setHubMode(true)
        VmHarness.advance(MODE_SWITCH_SETTLE_MS)
        assertEquals("primary", role(vm))
        assertEquals("primary", vm.ui.value.proxyRole)
        VmHarness.idleUntil("the role to persist") { persistedRole() == "primary" }

        vm.setHubMode(false)
        VmHarness.advance(MODE_SWITCH_SETTLE_MS)
        assertEquals("normal", role(vm))
        VmHarness.idleUntil("the role to persist") { persistedRole() == "normal" }
    }

    /** AC-6: hub → normal frees the machine. The DE1 manager is left
     *  user-disconnected, so its reconnect loop doesn't run and nothing scans
     *  for the machine, however long this device waits. */
    @Test
    fun `hub mode off leaves the DE1 user-disconnected`() {
        val vm = build(role = "primary")
        val ble = de1(vm)
        vm.setHubMode(false)
        VmHarness.advance(MODE_SWITCH_SETTLE_MS)
        assertEquals("normal", role(vm))
        assertTrue(ble.isUserDisconnected, "the DE1 is left user-disconnected")
        VmHarness.advance(60_000)
        assertEquals(De1BleManager.State.DISCONNECTED, ble.state.value, "no reconnect or scan started")
        assertEquals(De1BleManager.State.DISCONNECTED, vm.ui.value.bleState)
    }

    /** AC-8: a normal device sees another device's hub holding the DE1 — not
     *  itself, not a hub without the machine — and Hub mode is refused. */
    @Test
    fun `a discovered hub holding the machine is the normal device's hub`() {
        val vm = build()
        val proxy = VmHarness.proxyController(vm)
        val peers = MutableStateFlow<List<Peer>>(emptyList())
        proxy.overridePeers(peers)
        val kitchen = Peer("nsd-kitchen", "Kitchen tablet", "primary", holdsDe1 = true, host = "192.168.1.20", port = 8765)

        peers.value = listOf(kitchen.copy(id = proxy.instanceId))
        VmHarness.idle()
        assertNull(vm.ui.value.hubHoldingMachine, "this device's own advertisement")

        peers.value = listOf(kitchen.copy(holdsDe1 = false))
        VmHarness.idle()
        assertNull(vm.ui.value.hubHoldingMachine, "a hub without the machine")

        peers.value = listOf(kitchen)
        VmHarness.idle()
        assertEquals(kitchen, vm.ui.value.hubHoldingMachine)
        assertEquals("Turn off hub mode on Kitchen tablet first", vm.ui.value.hubModeBlockedReason())

        vm.setHubMode(true)
        VmHarness.advance(MODE_SWITCH_SETTLE_MS)
        assertEquals("normal", role(vm), "a second hub without the machine would only confuse remotes")
        assertTrue(vm.ui.value.userMessages.any { "Turn off hub mode on Kitchen tablet first" in it }, "${vm.ui.value.userMessages}")
    }

    /** AC-8: a remote doesn't get the normal device's "hub holds the machine". */
    @Test
    fun `a remote has no hub holding the machine`() {
        val vm = build(role = "secondary", host = "127.0.0.1", port = 1)
        VmHarness.proxyController(vm).overridePeers(
            MutableStateFlow(listOf(Peer("nsd-kitchen", "Kitchen tablet", "primary", holdsDe1 = true, host = "192.168.1.20", port = 8765))),
        )
        VmHarness.idle()
        assertEquals("secondary", role(vm))
        assertNull(vm.ui.value.hubHoldingMachine)
    }

    /**
     * AC-7, AC-8 and AC-37 (state flags): a remote names its hub from the
     * Welcome (the test hub sends no config snapshot), shows "Connected
     * through" while the hub lists the DE1 and disables Hub mode; when the
     * hub's roster loses the DE1 it says so and offers Connect directly,
     * which makes it a normal device.
     */
    @Test
    fun `a remote follows its hub's welcome and roster`() {
        val roster = MutableStateFlow(listOf(DeviceInfo("AA:BB", "DE1", "de1", "CONNECTED")))
        val primary = MockWsServerLink()
        val hub = RelayHub(
            primaryId = "host-1",
            primaryName = "Test hub",
            roster = { roster.value },
            readSource = { _, _, _ -> error("no reads in this test") },
            isSnapshotChar = { _, _ -> false },
        )
        val ws = MockWebServer().also { server = it }
        ws.enqueue(MockResponse.Builder().webSocketUpgrade(primary).build())
        ws.start(InetAddress.getLoopbackAddress(), 0)
        hubScope.launch { hub.serve(primary) }

        val vm = build()
        vm.switchToSecondary("127.0.0.1", ws.port)
        VmHarness.advance(MODE_SWITCH_SETTLE_MS)
        VmHarness.idleUntil("the remote to be welcomed") { vm.ui.value.mirroringPrimaryName.isNotEmpty() }
        VmHarness.idleUntil("the hub's roster") { vm.ui.value.remoteHubHasMachine }

        assertEquals("Test hub", vm.ui.value.mirroringPrimaryName, "named by the Welcome, before any snapshot")
        assertEquals("Connected through Test hub", vm.ui.value.remoteStatus())
        assertFalse(vm.ui.value.canConnectDirectly())
        assertEquals("Turn off hub mode on Test hub first", vm.ui.value.hubModeBlockedReason())

        roster.value = emptyList()
        hub.pushRoster()
        VmHarness.idleUntil("the roster without the DE1") { !vm.ui.value.remoteHubHasMachine }
        assertEquals("Test hub isn't connected to the machine", vm.ui.value.remoteStatus())
        assertTrue(vm.ui.value.canConnectDirectly())
        assertNull(vm.ui.value.hubModeBlockedReason())

        vm.connectDirectly()
        VmHarness.advance(MODE_SWITCH_SETTLE_MS)
        assertEquals("normal", role(vm))
        assertNull(vm.ui.value.remoteStatus())
    }

    private companion object {
        /** ProxyController.applyModeLocked's settle delay (150 ms), with margin. */
        const val MODE_SWITCH_SETTLE_MS = 200L
    }
}
