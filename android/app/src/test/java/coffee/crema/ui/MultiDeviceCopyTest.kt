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

    private fun pin(id: String, host: String) =
        coffee.crema.ble.proxy.RelayHubPin(id, "AAAA", "Kitchen tablet", host, 8765, pinnedAtMs = 1, lastSeenMs = 0)

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
        val unverified = "Couldn't verify Kitchen tablet. If Crema on Kitchen tablet was reset or reinstalled, pair again."
        val gaveUp = remote(reconnecting = true).copy(refindGaveUp = true)
        assertEquals(unverified, gaveUp.remoteStatus())
        assertTrue(gaveUp.canConnectDirectly())
        val stopped = remote(reconnecting = true).copy(hubStoppedHosting = true)
        assertEquals("Kitchen tablet isn't a hub any more", stopped.remoteStatus())
        assertTrue(stopped.canConnectDirectly())
        assertEquals(unverified, stopped.copy(refindGaveUp = true).remoteStatus(), "the give-up comes first")
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
        // A pin for another hub isn't this one.
        assertFalse(unpaired.copy(pairedHubs = listOf(pin("bbbbbbbb00", "192.168.1.99"))).isVerifiedHub(kitchen))

        val pinned = unpaired.copy(pairedHubs = listOf(pin("aaaaaaaa00", kitchen.host)))
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

    // ── Relay v4 (spec §6) ──────────────────────────────────────────────────

    /** AC-37: each advisory a remote can meet gives its §6 string. */
    @Test
    fun `each advisory refusal gives its spec string`() {
        val hub = "Kitchen tablet"
        val expected = mapOf(
            "PairingClosed" to "Kitchen tablet isn't accepting new devices. On Kitchen tablet, open Settings ▸ Advanced ▸ Paired devices ▸ Pair a device, then try again.",
            "Busy" to "Kitchen tablet is pairing another device. Try again in a moment.",
            "LockedOut" to "Kitchen tablet paused pairing after too many wrong codes. Try again in a minute.",
            "Cancelled" to "Pairing was cancelled on Kitchen tablet.",
            "Timeout" to "The code expired. Try again.",
            "HubOutdated" to "Kitchen tablet runs an older Crema — update it to connect.",
            "Version" to "Update Crema on both devices to connect.",
            "HubUnverified" to "Couldn't verify Kitchen tablet. If Crema on Kitchen tablet was reset or reinstalled, pair again.",
            "HostMismatch" to "Couldn't verify Kitchen tablet. If Crema on Kitchen tablet was reset or reinstalled, pair again.",
            "Revoked" to "Kitchen tablet removed this device. Pair again to reconnect.",
            "NotPaired" to "Kitchen tablet removed this device. Pair again to reconnect.",
            "HubStopped" to "Kitchen tablet turned off Hub mode.",
        )
        for ((reason, line) in expected) assertEquals(line, refusalMessage(reason, hub), reason)
        // The hub's notice for an old remote.
        assertEquals("Sam's phone runs an older Crema — update it to connect.", olderCrema("Sam's phone"))
    }

    /** §6.1: the code sheet's and the hub dialog's words. */
    @Test
    fun `the pairing words`() {
        assertEquals("Pairing with Kitchen tablet…", pairingWaitingTitle("Kitchen tablet"))
        assertEquals("Enter this code on Kitchen tablet", pairingCodeTitle("Kitchen tablet"))
        assertEquals("482 913", groupedCode("482913"))
        assertEquals("4 8 2, 9 1 3", spokenCode("482913"), "TalkBack reads it digit by digit")
        assertEquals("Expires in 1:52", expiresIn(112))
        assertEquals("Expires in 0:05", expiresIn(5))
        assertEquals("Attempt 2 of 3", pairingAttemptLine(2))
        assertEquals("That code didn't work. Here's a new one: enter it on Kitchen tablet.", pairingRetryLine("Kitchen tablet"))
        assertEquals(
            "Pairing with Kitchen tablet didn't work. Make sure you're entering the code on Kitchen tablet itself, then try again.",
            pairingFailedLine("Kitchen tablet"),
        )
        assertEquals("Pair Sam's phone?", pairRemoteTitle("Sam's phone"))
        assertEquals("Enter the 6-digit code shown on Sam's phone.", enterCodeLine("Sam's phone"))
        assertEquals("That code didn't match. Sam's phone is showing a new code — enter that one. (2 of 3)", wrongCodeLine("Sam's phone", 2))
        assertEquals("Paired Sam's phone (View and control)", pairedRemote("Sam's phone", canControl = true))
        assertEquals("Paired Sam's phone (View only)", pairedRemote("Sam's phone", canControl = false))
    }

    /** §6.2: the Pair a device row, closed, open and paused. */
    @Test
    fun `the pairing window row`() {
        assertEquals("Pair a device", pairWindowTitle(0, 0))
        assertEquals("Pairing open · 1:58", pairWindowTitle(118, 0))
        assertEquals("Pairing paused · 0:42", pairWindowTitle(0, 42))
        assertEquals("On your phone, open Other devices and choose Kitchen tablet.", pairWindowLine(118, 0, "Kitchen tablet"))
        assertEquals(LOCKED_OUT_COPY, pairWindowLine(0, 42, "Kitchen tablet"))
    }

    /** §6.5: "Last connected", relative. */
    @Test
    fun `last connected reads relative`() {
        val now = 10_000_000_000L
        assertEquals("Never connected", lastConnected(0, now))
        assertEquals("Last connected just now", lastConnected(now - 20_000, now))
        assertEquals("Last connected 5 minutes ago", lastConnected(now - 5 * 60_000, now))
        assertEquals("Last connected 1 hour ago", lastConnected(now - 61 * 60_000, now))
        assertEquals("Last connected yesterday", lastConnected(now - 25 * 3_600_000L, now))
        assertEquals("Last connected 3 days ago", lastConnected(now - 3 * 86_400_000L, now))
    }

    /** §6.1, AC-56: before the first Welcome since the pick, the line says
     *  Connecting, never Connected. */
    @Test
    fun `a remote says connecting until its hub welcomes it`() {
        assertEquals("Connecting through Kitchen tablet…", remote(reconnecting = true).copy(remoteConnecting = true).remoteStatus())
        assertEquals("Connecting through Kitchen tablet…", remote().copy(remoteConnecting = true).remoteStatus())
    }

    /** §6.3, §6.4: Pair again only where the remote must pair before it dials. */
    @Test
    fun `pair again is offered after the upgrade and once the hub couldn't be verified`() {
        assertFalse(remote().canPairAgain())
        assertTrue(remote().copy(relayNeedsRepair = true).canPairAgain())
        assertEquals("Crema updated how devices connect. Pair again with Kitchen tablet.", remote().copy(relayNeedsRepair = true).remoteStatus())
        assertTrue(remote(reconnecting = true).copy(refindGaveUp = true).canPairAgain())
        assertFalse(remote(reconnecting = true).copy(refindGaveUp = true, relayKeysUnavailable = true).canPairAgain(), "no keys, no pairing")
        assertFalse(MainUiState(proxyRole = "normal", relayNeedsRepair = true).canPairAgain())
    }

    /** §4.8, §6.1: the picker says Paired for a pinned hub and Needs a Crema update for an old one. */
    @Test
    fun `the picker labels paired and outdated hubs`() {
        val withKid = kitchen.copy(kid = "aaaaaaaa")
        val ui = MainUiState(pairedHubs = listOf(pin("aaaaaaaa00", "10.0.0.9")))
        assertEquals("Paired · holds the DE1 · 192.168.1.20", ui.pickerLine(withKid), "the kid matches a pin, wherever it is")
        assertEquals("Says it's a hub · unverified · 192.168.1.20", ui.pickerLine(withKid.copy(kid = "bbbbbbbb")))
        assertEquals("Needs a Crema update · 192.168.1.20", ui.pickerLine(kitchen.copy(version = 3)))
    }
}
