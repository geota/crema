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

    /** P1b: while the link redials, the re-find's verdicts take the line:
     *  "Couldn't verify <hub>" once it gave up, "<hub> isn't a hub any more"
     *  when NSD lists the hub's address as a device that turned Hub mode off.
     *  A link that is up still says "Connected through". */
    @Test
    fun `the re-find's verdicts while the link redials`() {
        val gaveUp = remote(reconnecting = true).copy(refindGaveUp = true)
        assertEquals("Couldn't verify Kitchen tablet", gaveUp.remoteStatus())
        assertTrue(gaveUp.canConnectDirectly())
        val stopped = remote(reconnecting = true).copy(hubStoppedHosting = true)
        assertEquals("Kitchen tablet isn't a hub any more", stopped.remoteStatus())
        assertTrue(stopped.canConnectDirectly())
        assertEquals("Couldn't verify Kitchen tablet", stopped.copy(refindGaveUp = true).remoteStatus(), "the give-up comes first")
        assertEquals("Connected through Kitchen tablet", remote().copy(hubStoppedHosting = true, refindGaveUp = true).remoteStatus())
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

    /** AC-6: Hub mode is disabled on a remote whose hub (which proved its
     *  pairing) holds the DE1, and not otherwise. */
    @Test
    fun `hub mode is disabled on a remote whose hub holds the machine`() {
        assertEquals("Turn off hub mode on Kitchen tablet first", remote().hubModeBlockedReason())
        assertNull(remote(hasMachine = false).hubModeBlockedReason(), "a hub without the machine")
        assertNull(remote(reconnecting = true).hubModeBlockedReason(), "a hub that's gone")
        assertNull(MainUiState(proxyRole = "normal").hubModeBlockedReason())
        assertNull(MainUiState(proxyRole = "primary", hubHoldingMachine = kitchen).hubModeBlockedReason(), "a hub can always turn it off")
    }

    /** Security review: a discovered hub's advertisement is unauthenticated, so
     *  on a normal device it only warns (deviation from AC-6's "disabled"). It
     *  is named only when it is the paired, pinned target; anything else is
     *  "a device on this network". */
    @Test
    fun `a discovered hub only warns, and is named only when paired and pinned`() {
        val unpaired = MainUiState(proxyRole = "normal", hubHoldingMachine = kitchen)
        assertNull(unpaired.hubModeBlockedReason(), "an advertisement never blocks Hub mode")
        assertEquals(
            "A device on this network says it's the hub — turning on Hub mode here will fail to connect while it holds the machine",
            unpaired.discoveredHubWarning(),
        )
        assertEquals(unpaired.discoveredHubWarning(), unpaired.hubModeSubtitle("tablet"))
        // A pin for another address, or an address without a pin, isn't verified.
        assertFalse(unpaired.copy(pairedHubPinned = true, proxyPrimaryHost = "192.168.1.99").isVerifiedHub(kitchen))
        assertFalse(unpaired.copy(pairedHubPinned = false, proxyPrimaryHost = kitchen.host).isVerifiedHub(kitchen))

        val pinned = unpaired.copy(pairedHubPinned = true, proxyPrimaryHost = kitchen.host)
        assertTrue(pinned.isVerifiedHub(kitchen))
        assertEquals(
            "Kitchen tablet is already the hub for this machine — turning on Hub mode here will fail to connect while it holds the machine",
            pinned.discoveredHubWarning(),
        )
        assertNull(MainUiState(proxyRole = "primary", hubHoldingMachine = kitchen).discoveredHubWarning())
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

    /** AC-8: what a normal device's connect surfaces say while a hub holds the
     *  DE1: named for a paired, pinned hub, unverified for any other. */
    @Test
    fun `the turn-off-hub copy offers to connect through the hub`() {
        assertEquals("Turn off hub mode on Kitchen tablet to connect this device", turnOffHubToConnect(kitchen, verified = true))
        assertEquals("Connect through Kitchen tablet", connectThroughLabel(kitchen, verified = true))
        assertEquals(
            "A device on this network says it's the hub (“Kitchen tablet”, unverified) — turn off hub mode there to connect this device",
            turnOffHubToConnect(kitchen, verified = false),
        )
        assertEquals("Connect through it", connectThroughLabel(kitchen, verified = false))
        assertEquals(turnOffHubToConnect(kitchen, verified = false), MainUiState(hubHoldingMachine = kitchen).turnOffHubCopy())
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
            for (row in listOf("Hub host", "Hub port")) {
                val at = lines.indexOfFirst { "CremaSettingsRow(\"$row\"" in it }
                assertTrue(at >= 0, "$path has the manual $row row")
                // An `if (…proxyRole…)` before the row on its own line, or on any
                // block that encloses it. (The row's own `last = …` may read the role.)
                val guards = (listOf(lines[at].substringBefore("CremaSettingsRow(")) + enclosingBlockOpeners(lines, at))
                    .filter { "proxyRole" in it }
                assertEquals(emptyList(), guards, "$path guards the $row row by role")
            }
        }
    }

    /** Validation D3: the phone Brew cue says what the status line says, so it
     *  never claims "Connected through" while the hub has no machine. */
    @Test
    fun `the phone brew cue follows the status line`() {
        assertEquals("· Connected through Kitchen tablet", remote().remoteBrewCue())
        assertEquals("· Kitchen tablet isn't connected to the machine", remote(hasMachine = false).remoteBrewCue())
        assertEquals("· Reconnecting to Kitchen tablet…", remote(reconnecting = true).remoteBrewCue())
        assertNull(MainUiState().remoteBrewCue())
    }

    /** Validation D2: the Devices sheet says the turn-off-hub sentence once,
     *  in the notice with its button, not again in the DE1 row's status. */
    @Test
    fun `the turn-off-hub sentence is shown once on the devices sheet`() {
        val sheets = MainSources.kotlinFiles().toMap().getValue("coffee/crema/ui/phone/PhoneBrewSheets.kt")
        assertFalse("turnOffHub" in sheets, "the DE1 row's status repeats the notice")
        assertEquals(2, Regex("HubHoldsMachineNotice\\(").findAll(sheets).count(), "the notice on both layouts")
    }

    /** Validation D4: no user-facing string says "mirror" any more (hub and
     *  remote wording). Log lines and the wire's `"mirror"` scope are exempt. */
    @Test
    fun `no user-facing string says mirror`() {
        val literal = Regex("\"[^\"\\n]*[Mm]irror[^\"\\n]*\"")
        val hits = MainSources.kotlinFiles()
            .filter { (path, _) -> !path.startsWith("coffee/crema/core/") } // generated UniFFI bindings
            .flatMap { (path, text) ->
                text.lines().withIndex().flatMap { (i, line) ->
                    val code = line.trim()
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return@flatMap emptyList()
                    if (listOf("appendLog(", "Log.", "log(").any { it in line }) return@flatMap emptyList()
                    literal.findAll(line).map { it.value }.filter { it != "\"mirror\"" }.map { "$path:${i + 1}: $it" }.toList()
                }
            }
        assertEquals(emptyList(), hits)
    }

    /** The lines that open the blocks enclosing line [at], innermost first,
     *  up to the enclosing function: a brace-depth walk upwards. */
    private fun enclosingBlockOpeners(lines: List<String>, at: Int): List<String> {
        val openers = mutableListOf<String>()
        var depth = 0
        for (i in at - 1 downTo 0) {
            val line = lines[i]
            depth += line.count { it == '}' } - line.count { it == '{' }
            if (depth < 0) {
                openers += line
                depth = 0
                if (line.trimStart().startsWith("fun ") || line.contains(" fun ")) break
            }
        }
        return openers
    }
}
