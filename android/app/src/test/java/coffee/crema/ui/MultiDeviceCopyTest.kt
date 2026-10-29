package coffee.crema.ui

import coffee.crema.MainSources
import coffee.crema.ble.proxy.Peer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The words and enablement of the hub / remote surfaces ([remoteStatusLine],
 * [hubModeBlockedReason], [hubModeSubtitle], the turn-off-hub copy), pure over
 * [MainUiState], so both shells say the same thing.
 */
class MultiDeviceCopyTest {

    private val kitchen = Peer("nsd-1", "Kitchen tablet", "primary", holdsDe1 = true, host = "192.168.1.20", port = 8765)

    private fun remote(
        name: String = "Kitchen tablet",
        reconnecting: Boolean = false,
        hasMachine: Boolean = true,
        viewOnly: Boolean = false,
    ) = MainUiState(
        proxyRole = "secondary",
        proxyPrimaryHost = "192.168.1.20",
        mirroringPrimaryName = name,
        mirrorReconnecting = reconnecting,
        remoteHubHasMachine = hasMachine,
        mirrorViewOnly = viewOnly,
    )

    /** AC-7: the status line follows the link and the hub's roster. */
    @Test
    fun `the remote status line follows the link and the roster`() {
        assertEquals("Connected through Kitchen tablet", remote().remoteStatus())
        assertEquals("Reconnecting to Kitchen tablet…", remote(reconnecting = true).remoteStatus())
        assertEquals("Reconnecting to Kitchen tablet…", remote(reconnecting = true, hasMachine = false).remoteStatus(), "the link comes first")
        assertEquals("Kitchen tablet isn't connected to the machine", remote(hasMachine = false).remoteStatus())
        assertEquals("Connected through Kitchen tablet · view-only", remote(viewOnly = true).remoteStatus())
    }

    /** AC-7: before the Welcome names the hub, the line names the address it
     *  dials; the VM fills the name from the Welcome ([HubRemoteRolesTest]). */
    @Test
    fun `before the hub is named the line uses its address`() {
        assertEquals("Reconnecting to 192.168.1.20…", remote(name = "", reconnecting = true).remoteStatus())
        assertEquals("Reconnecting to the hub…", remote(name = "", reconnecting = true).copy(proxyPrimaryHost = "").remoteStatus())
    }

    /** AC-37 (pure half): only a remote has a status line — the tablet's top-bar
     *  chip and the phone's banner show exactly while it is non-null. */
    @Test
    fun `only a remote has a status line`() {
        assertNull(MainUiState(proxyRole = "normal").remoteStatus())
        assertNull(MainUiState(proxyRole = "primary").remoteStatus())
        assertTrue(remote().remoteStatus() != null)
    }

    @Test
    fun `connect directly appears once the hub can't give this device the machine`() {
        assertFalse(remote().canConnectDirectly())
        assertTrue(remote(reconnecting = true).canConnectDirectly())
        assertTrue(remote(hasMachine = false).canConnectDirectly())
        assertFalse(MainUiState(proxyRole = "normal").canConnectDirectly())
    }

    /** AC-6 and AC-8: Hub mode is disabled while another hub holds the DE1 —
     *  this remote's hub, or one NSD lists to a normal device — and not
     *  otherwise. */
    @Test
    fun `hub mode is disabled while another hub holds the machine`() {
        assertEquals("Turn off hub mode on Kitchen tablet first", remote().hubModeBlockedReason())
        assertNull(remote(hasMachine = false).hubModeBlockedReason(), "a hub without the machine")
        assertNull(remote(reconnecting = true).hubModeBlockedReason(), "a hub that's gone")
        assertEquals(
            "Turn off hub mode on Kitchen tablet first",
            MainUiState(proxyRole = "normal", hubHoldingMachine = kitchen).hubModeBlockedReason(),
        )
        assertNull(MainUiState(proxyRole = "normal").hubModeBlockedReason())
        assertNull(MainUiState(proxyRole = "primary", hubHoldingMachine = kitchen).hubModeBlockedReason(), "a hub can always turn it off")
    }

    /** AC-6: the switch's subtitle says that turning it off frees the machine. */
    @Test
    fun `the hub mode subtitle says turning it off frees the machine`() {
        val hub = MainUiState(proxyRole = "primary")
        assertEquals("This tablet will disconnect from the machine so another device can connect.", hub.hubModeSubtitle("tablet"))
        assertEquals("This phone will disconnect from the machine so another device can connect.", hub.hubModeSubtitle("phone"))
        assertEquals("Turn off hub mode on Kitchen tablet first", remote().hubModeSubtitle("tablet"))
        assertTrue("connect through" in MainUiState().hubModeSubtitle("tablet"))
    }

    /** AC-8: what a normal device's connect surfaces say while a hub holds the DE1. */
    @Test
    fun `the turn-off-hub copy offers to connect through the hub`() {
        assertEquals("Turn off hub mode on Kitchen tablet to connect this device", turnOffHubToConnect(kitchen.name))
        assertEquals("Connect through Kitchen tablet", connectThroughLabel(kitchen.name))
    }

    /** AC-37: the picker's restart sub is gone (the debug role selector it
     *  explained is gone), and the manual hub host and port rows show in every
     *  role on both shells, so the emulator / no-NSD path still feeds the
     *  picker's manual row. */
    @Test
    fun `no restart sub, and the manual hub fields show in every role`() {
        val files = MainSources.kotlinFiles().toMap()
        val picker = files.getValue("coffee/crema/ui/components/MultiDevicePicker.kt")
        assertFalse("restart the app" in picker, "the picker's restart-to-apply sub")
        for (path in listOf("coffee/crema/ui/screens/SettingsScreen.kt", "coffee/crema/ui/phone/PhoneSettingsScreen.kt")) {
            val lines = files.getValue(path).lines()
            val host = lines.indexOfFirst { "CremaSettingsRow(\"Hub host\"" in it }
            assertTrue(host >= 0, "$path has the manual hub host row")
            // Neither the row nor the nearest block opener above it is a role check.
            val opener = lines.subList(0, host).last { it.trimEnd().endsWith("{") }
            assertFalse("proxyRole" in lines[host] || "proxyRole" in opener, "$path guards the host row by role: $opener / ${lines[host]}")
            assertTrue(lines.any { "CremaSettingsRow(\"Hub port\"" in it }, "$path has the manual hub port row")
        }
    }
}
