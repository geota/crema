package coffee.crema.ui

import android.app.Application
import android.os.Build
import coffee.crema.ble.BleTransport
import coffee.crema.ble.NordicBleTransport
import coffee.crema.ble.proxy.DenyReason
import coffee.crema.ble.proxy.DeviceInfo
import coffee.crema.ble.proxy.LanRelayServer
import coffee.crema.ble.proxy.LinkState
import coffee.crema.ble.proxy.PairingCrypto
import coffee.crema.ble.proxy.PairingDecision
import coffee.crema.ble.proxy.PairingRequest
import coffee.crema.ble.proxy.PairingStore
import coffee.crema.ble.proxy.Peer
import coffee.crema.ble.proxy.PeerDiscovery
import coffee.crema.ble.proxy.ProxyTransport
import coffee.crema.ble.proxy.ReconnectingClientLink
import coffee.crema.ble.proxy.RelayHub
import coffee.crema.ble.proxy.RemoteClient
import coffee.crema.ble.proxy.ReplayBleTransport
import coffee.crema.ble.proxy.SwitchableBleTransport
import coffee.crema.ble.proxy.TappingBleTransport
import coffee.crema.ble.De1Uuids
import coffee.crema.machine.CommandOrigin
import coffee.crema.machine.CommandOutcome
import coffee.crema.machine.LocalMachine
import coffee.crema.machine.MachineActions
import coffee.crema.machine.MachineCommands
import coffee.crema.machine.RelayMachine
import coffee.crema.machine.RelayedCommandDispatcher
import coffee.crema.persist.SafeFile
import coffee.crema.security.DeviceSecretBox
import coffee.crema.security.SecretBox
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.PairedDevice
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/** This device's human label — the name peers see in the multi-device picker
 *  and the label stamped into backup envelopes. */
internal fun deviceLabel(): String = Build.MODEL ?: "crema"

/** A pending host-side "Allow this device?" pairing prompt (issue 02) — while the
 *  host's pairing window is open, a secondary without a pairing secret is
 *  connecting; the UI shows it and the user's choice resolves the held handshake. */
data class PairingPrompt(val clientId: String, val clientName: String)

/** The host's answer to a [PairingPrompt] (issue 02). */
enum class PairingChoice { DENY, MIRROR_ONLY, ALLOW_CONTROL }

/*
 * The multi-device proxy controller — mode switching (normal / hub / remote;
 * `primary` / `secondary` in code), the LAN relay + remote link lifecycle,
 * peer discovery, TOFU pairing and a remote's re-find of a hub that moved,
 * extracted from MainViewModel (review #43).
 *
 * Follows the VisualizerSync / DriveSync pattern: a self-contained controller
 * with its own [state] flow; the VM constructs it, wires callbacks, and mirrors
 * [State] into MainUiState. It OWNS the app-wide switchable BLE transport — the
 * VM builds the scanner and both BLE managers on [transport], so this
 * controller must be constructed BEFORE them (it is what gets swapped on a
 * mode switch).
 *
 * Whole-app concerns stay VM-side, reached through constructor callbacks: the
 * verb bodies behind machine commands ([machineActions]), the session-config
 * snapshot ([snapshotConfig] / [applyConfig]) and the paired-device list
 * (persisted with the app prefs).
 *
 * It also picks where the VM's commands go ([machine]): a [RelayMachine] while
 * mirroring, else the [LocalMachine], swapped with the transport. On a primary
 * a secondary's relayed control runs through [RelayedCommandDispatcher] into
 * the same [LocalMachine].
 */
class ProxyController(
    private val app: Application,
    private val json: Json,
    private val scope: CoroutineScope,
    /** `bridge.setReadOnly` — a secondary's core mirrors but must never drive.
     *  Never invoked during construction: [start] applies the persisted role. */
    private val setCoreReadOnly: (Boolean) -> Unit,
    /** `bleRecorder.enabled` — a mirror doesn't record (issue 14). Never invoked
     *  during construction (the recorder is built after this controller). */
    private val setRecorderEnabled: (Boolean) -> Unit,
    /** Surface a user-facing message (the VM's snackbar channel). */
    private val notify: (String) -> Unit,
    /** Append to the session event log. Invoked during construction. */
    private val appendLog: (String) -> Unit,
    /** The VM's full DE1 connect (scan + bind). */
    private val connect: () -> Unit,
    /** The VM's full DE1 disconnect (cancels the scan, clears the live UI). */
    private val disconnect: () -> Unit,
    /** Manager-level teardown of both links for a mode switch — no UI clearing. */
    private val disconnectDevices: () -> Unit,
    /** The VM's scale connect (scan + bind) — the mirror's roster-driven attach. */
    private val connectScale: () -> Unit,
    /** True when the scale manager is neither READY nor SCANNING — i.e. a
     *  roster-driven attach should fire. */
    private val scaleAttachWanted: () -> Boolean,
    /** Whether the DE1 manager currently holds a device (drives advertisement). */
    private val de1Connected: () -> Boolean,
    /** The DE1's current machine-state name (`MachineState.string`), null when
     *  unknown — words the "who's driving" notice. */
    private val machineStateName: () -> String?,
    /** The VM's verb bodies, which machine commands run on this device. Never
     *  invoked during construction. */
    private val machineActions: MachineActions,
    /** The primary's session-config snapshot JSON for a mirror (T2). */
    private val snapshotConfig: () -> String,
    /** Apply a primary's pushed config snapshot on this secondary (T2). */
    private val applyConfig: (String) -> Unit,
    /** The devices this primary holds (DE1 + scale) for the relay roster. */
    private val roster: () -> List<DeviceInfo>,
    /** Whether (service, char) is snapshot-on-attach vs a counted live stream. */
    private val isSnapshotChar: (service: UUID, char: UUID) -> Boolean,
    /** The remembered TOFU peers (persisted with the app prefs, VM-owned). */
    private val pairedDevices: () -> List<PairedDevice>,
    /** Persist a TOFU decision so the peer's reconnects are silent. */
    private val rememberPaired: (id: String, name: String, canControl: Boolean) -> Unit,
    /** A mode switch landed: mirror role/host/port into MainUiState (sticky
     *  rules), drop the mirror overlay fields, and persist the prefs. */
    private val onModeApplied: (role: String, host: String, port: Int) -> Unit,
    /** The core's firmware lock (`bridge.firmwareLocksWrites()`): a flash or
     *  its erase / reboot window holds the DE1, so no mode switch may move the
     *  radio (audit r2 H1). */
    private val firmwareHoldsLink: () -> Boolean = { false },
    /** Seals the pairing secrets at rest (tests pass a fake cipher). */
    secretBox: SecretBox = DeviceSecretBox.instance,
    /** Where a primary runs relayed commands: Main, like its own UI (tests
     *  pass a test dispatcher). */
    commandLane: CoroutineContext = Dispatchers.Main.immediate,
    /** How long a remote's link must stay CONNECTING before it looks for its
     *  hub elsewhere on NSD (the re-find). */
    private val refindDelayMs: Long = REFIND_DELAY_MS,
) {

    /** The proxy slice of the UI snapshot — the VM mirrors this into MainUiState. */
    data class State(
        /** The LIVE role (`"normal" | "primary" | "secondary"`) — seeded from the
         *  persisted prefs at construction, updated by [applyMode]. */
        val role: String = "normal",
        /** LAN peers discovered over NSD (the multi-device picker). */
        val peers: List<Peer> = emptyList(),
        /** True while a remote's link to its hub is (re)dialing or not yet
         *  welcomed (issue 03). */
        val mirrorReconnecting: Boolean = false,
        /** True when the host admitted this mirror view-only (issue 02). */
        val mirrorViewOnly: Boolean = false,
        /** A host-side TOFU prompt awaiting the user's choice (issue 02). */
        val pendingPairing: PairingPrompt? = null,
        /** Remotes connected through this hub: its read-only Remotes list. */
        val remotes: List<RemoteClient> = emptyList(),
        /** Seconds left in the host's pairing window; 0 = closed. */
        val pairingSecondsLeft: Int = 0,
        /** Paired devices that hold a pairing secret from this host. A remembered
         *  device missing here has to pair again. */
        val pairedWithSecret: Set<String> = emptySet(),
        /** A remote's hub name, from the hub's Welcome; blank until then and
         *  in the other roles. */
        val hubName: String = "",
        /** A remote's hub lists the DE1 in its roster: it holds the machine. */
        val hubHasMachine: Boolean = false,
        /** The re-find gave up: three hubs it tried couldn't prove the pairing
         *  this device holds, and NSD lists no other. The remote shows
         *  "Couldn't verify <hub>" with Connect directly. */
        val refindGaveUp: Boolean = false,
        /** NSD lists this remote's hub address as a device that isn't a hub
         *  any more (it turned Hub mode off). Advice only: unauthenticated. */
        val hubStoppedHosting: Boolean = false,
        /** A host that couldn't prove the secret this device holds for it, as
         *  (host id, name): the UI offers to forget it on this device. */
        val changedHost: Pair<String, String>? = null,
    )

    private data class ProxyConfig(val role: String, val host: String, val port: Int, val replayPrimary: Boolean = false)

    // Role-specific resources. In NORMAL, [nordicTransport] holds the real
    // transport; [relayHub]/[relayServer] (PRIMARY) and [proxyLink] (SECONDARY)
    // are the role-specific extras, torn down on a mode switch and in [close].
    // Nothing assigns them during construction: the persisted role starts in
    // [start], once the VM is fully built.
    private var nordicTransport: NordicBleTransport? = null
    @androidx.annotation.VisibleForTesting
    internal var relayHub: RelayHub? = null
    private var relayServer: LanRelayServer? = null
    private var proxyLink: ReconnectingClientLink? = null

    /** A secondary's relay to its primary; null in the other roles. */
    @Volatile private var relayMachine: RelayMachine? = null

    /** Runs commands on this device: this device's UI, and a primary's relayed
     *  controls. */
    private val localMachine = LocalMachine(
        actions = machineActions,
        notify = { notify(it) },
    )

    /** A primary's entry for relayed controls (wired into [RelayHub]). */
    private val relayedCommands = RelayedCommandDispatcher(
        machine = localMachine,
        hostName = { deviceLabel() },
        machineIdle = { machineStateName() in IDLE_STATES },
        // "Who's driving" (issue 11, loose model): the other mirrors and this
        // primary learn who drove the machine; the originator already knows.
        announce = { text, origin ->
            relayHub?.broadcastEvent(text, exceptClientId = origin.id)
            notify(text)
        },
        log = { appendLog(it) },
        lane = commandLane,
    )

    /** Where the VM's verbs send their commands: the relay while mirroring,
     *  else this device. Chosen with the transport on each mode switch. */
    val machine: MachineCommands get() = relayMachine ?: localMachine

    /** Child scope for the SECONDARY mode's collectors + its [ProxyTransport],
     *  cancelled on every mode switch and in [close] so they don't leak across
     *  secondary→x→secondary cycles (issue 10). */
    private var secondaryScope: CoroutineScope? = null

    /** The primary relay's bound port (0 when not hosting) — advertised so a
     *  secondary can dial it. */
    private var relayPort: Int = 0

    /** Stable per-install id (persisted in a tiny file — issue 14): the
     *  secondary's `clientId`, which tells a host which pairing record to check.
     *  It is not advertised (see [instanceId]). */
    val deviceId: String = run {
        val f = File(app.filesDir, "deviceId")
        runCatching { f.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null } }.getOrNull()
            ?: UUID.randomUUID().toString().also { runCatching { SafeFile.write(f, it, keepBackup = false) } }
    }

    /** The LAN pairing secrets and this install's host id. */
    private val pairing = PairingStore(File(app.filesDir, PairingStore.FILE_NAME), secretBox, json)

    /** This process's NSD instance id: a random id per launch, advertised for
     *  discovery and used to filter our own advertisement out. */
    internal val instanceId: String = UUID.randomUUID().toString()

    /** Construction-time proxy-config snapshot — read `prefs.json` ONCE
     *  (review #36: role/host/port are restart-to-apply anyway). A PLAIN val,
     *  not a lazy: construction order stays linear within this class body
     *  (the lazy-below-use variant shipped a launch-dead APK — 4eeb92f2). */
    private val proxyConfigCache: ProxyConfig = readProxyConfigUncached()

    private val _state = MutableStateFlow(State(role = proxyConfigCache.role, pairedWithSecret = pairing.issuedIds()))
    val state: StateFlow<State> = _state.asStateFlow()

    // Multi-device pairing (issue 02). The relay doesn't auto-accept: a
    // connecting secondary is authorized in [authorizePeer]. A paired device
    // proves its pairing secret; anything else is considered only while the
    // host's pairing window is open, one prompt at a time, with a cooldown.
    private val pairingMutex = Mutex()
    @Volatile private var pairingDeferred: CompletableDeferred<PairingChoice?>? = null

    /** The open pairing window's countdown; null or finished = closed. */
    private var pairingWindowJob: Job? = null

    /** Prompts shown in the current window (capped at [MAX_PROMPTS_PER_WINDOW]). */
    @Volatile private var promptsThisWindow = 0

    /** True for [PROMPT_COOLDOWN_MS] after a prompt ends. */
    @Volatile private var promptCooling = false

    /** Client ids the host denied while hosting; not prompted again until
     *  hosting stops. */
    private val deniedWhileHosting: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Rate limit for the "needs an update" notice. */
    @Volatile private var outdatedNoticeCooling = false

    /** How the next secondary session may treat its host. Only the user's own
     *  pick ([USER_PICK]) clears the pin and may pair. Anything unattended — a
     *  restored target, an automatic re-mirror — keeps the pin and connects
     *  only to a host that proves a secret this device already holds
     *  ([UNATTENDED]). Passed with each switch to [startSecondaryMode]. */
    internal data class MirrorTrust(val repin: Boolean, val allowRepair: Boolean) {
        companion object {
            val USER_PICK = MirrorTrust(repin = true, allowRepair = true)
            val UNATTENDED = MirrorTrust(repin = false, allowRepair = false)
        }
    }

    /** Serializes [applyMode]: mode switches launch from several async paths
     *  (the picker, the Hub mode switch, denied→normal) and interleaved
     *  teardown/build would leak a relay server or install two transports.
     *  Switches run one at a time, in the order they were requested. */
    private val modeMutex = Mutex()

    /** Test seam: runs at the top of every [applyModeLocked], under the mode
     *  lock, before anything is torn down. */
    @androidx.annotation.VisibleForTesting
    internal var modeSwitchProbe: (suspend (role: String) -> Unit)? = null

    // The managers + scanner sit on this facade so the transport can be swapped at
    // runtime — M2 mode switches with NO app restart. It holds
    // [NotStartedTransport] until [start] installs the persisted role's
    // transport ([startPersistedRole]); [applyMode] swaps it after that.
    private val switchable = SwitchableBleTransport(NotStartedTransport)

    /** The app-wide BLE transport facade — the VM builds the scanner and both
     *  BLE managers on this. */
    val transport: BleTransport = switchable

    /** LAN peer discovery (NSD) for the multi-device picker (M2) — advertises this
     *  instance and browses for peers, exposed as [State.peers]. */
    private val peerDiscovery = PeerDiscovery(app, scope, instanceId)

    private var started = false

    /** Feeds [State.peers]: NSD discovery, or a test's flow ([overridePeers]). */
    private var peersJob: Job? = null

    /**
     * Post-construction start, called once from the VM's `init` (after every VM
     * field — the recorder, both managers — exists): start the persisted role
     * (a primary's relay, a secondary's mirror link, or the plain radio), arm
     * the recorder gate, start NSD discovery, and advertise this instance.
     * Nothing role-specific runs during construction: a role started from a
     * field initializer reached VM fields that didn't exist yet (the relay's
     * advertisement read the DE1 manager; review #36's launch-dead APKs).
     */
    fun start() {
        check(!started) { "ProxyController.start() runs once" }
        started = true
        switchable.setDelegate(startPersistedRole())
        // A secondary (read-only mirror) doesn't record the primary's shot (issue 14).
        setRecorderEnabled(readProxyConfigSync().role != "secondary")
        collectPeers(peerDiscovery.peers)
        peerDiscovery.startDiscovery()
        refreshAdvertisement()
    }

    private fun collectPeers(source: Flow<List<Peer>>) {
        peersJob?.cancel()
        peersJob = scope.launch {
            source.collect { p ->
                _state.update { it.copy(peers = p) }
                onPeersChanged(p)
            }
        }
    }

    /** Test seam: [State.peers] follows [flow] instead of NSD, which doesn't
     *  run under Robolectric. */
    @androidx.annotation.VisibleForTesting
    internal fun overridePeers(flow: Flow<List<Peer>>) = collectPeers(flow)

    /** Tear down whatever the current role holds. Called from the VM's
     *  `onCleared`, after the managers disconnected. Closing the Nordic
     *  environment unregisters its Bluetooth receiver and cancels its scope.
     *  (Only one of these is non-null per proxy role; NORMAL has just the Nordic.) */
    fun close() {
        closePairingWindow()
        nordicTransport?.close()
        relayServer?.stop()
        secondaryScope?.cancel()
        proxyLink?.dispose()
        peerDiscovery.close()
    }

    // ── A secondary's relayed control, on this primary ───────────────────────

    /** Run a secondary's relayed control through [relayedCommands]: decoded,
     *  checked, then run on Main by the same [LocalMachine] the primary's own
     *  UI uses, so `startShot` runs the full shot orchestration and that
     *  complexity never crosses the wire. Wired into the [RelayHub] by
     *  [startPrimaryMode]. */
    internal suspend fun handleRelayedControl(
        method: String,
        args: String,
        originId: String? = null,
        originName: String? = null,
    ): CommandOutcome = relayedCommands.dispatch(method, args, CommandOrigin(originId, originName))

    /** Mirror [host]:[port] without the user picking it: the pin stays, and only
     *  a host that proves a secret this device holds is accepted. Anything
     *  else is refused and stays in the picker for the user. */
    internal fun remirrorUnattended(host: String, port: Int) {
        applyMode("secondary", host, port, trust = MirrorTrust.UNATTENDED)
    }

    // ── Re-find: a remote whose hub moved finds it again (spec §3.3) ─────────

    /** The session a remote's current transport serves. [candidate] is set
     *  when the re-find dialed it, with the target it left ([previous]), so a
     *  refusal can go back there. Callbacks from any other session are stale. */
    private class RemoteSession(
        val host: String,
        val port: Int,
        val trust: MirrorTrust,
        val candidate: Peer?,
        val previous: Pair<String, Int>?,
    ) {
        /** The link has been CONNECTING for [refindDelayMs]. */
        var connectingLong = false
        val target: Pair<String, Int> get() = host to port
    }

    /** A switch the re-find asked for: try [candidate], or go back ([candidate]
     *  null) to a target after a refusal. Runs only if [from] is still the
     *  session when the switch gets the mode lock. */
    private class RefindSwitch(val candidate: Peer?, val previous: Pair<String, Int>?, val from: RemoteSession)

    @Volatile private var session: RemoteSession? = null

    /** NSD instance ids the re-find won't dial: they refused this device, or
     *  never answered. An id leaves when NSD stops listing it. */
    private val refindSkipped = mutableSetOf<String>()

    /** Candidates refused so far without a success in between. */
    private var refindRefusals = 0

    /** The last unattended session was refused for a trust reason. */
    private var lastUnattendedRefused = false

    /** A re-find switch is queued and hasn't started yet. */
    private var refindQueued = false

    /** Running while the next attempt must wait (5 s, 15 s, then 60 s). */
    private var refindBackoff: Job? = null

    /** Instance ids NSD listed when the re-find gave up: only a new one
     *  resumes it. */
    private var refindSeenAtGiveUp: Set<String> = emptySet()

    /** Held by the leave-remote flow (P2's frozen slice): no re-find runs. */
    private var refindHeld = false

    /** Hold or release the re-find. While held it starts nothing; a release
     *  re-checks at once. */
    fun holdRefind(hold: Boolean) {
        refindHeld = hold
        if (!hold) evaluateRefind()
    }

    /** The user chose a mode: forget everything the re-find learnt. */
    private fun resetRefind() {
        refindBackoff?.cancel()
        refindBackoff = null
        refindSkipped.clear()
        refindRefusals = 0
        lastUnattendedRefused = false
        refindQueued = false
        refindSeenAtGiveUp = emptySet()
        _state.update { it.copy(refindGaveUp = false) }
    }

    /** A hub NSD lists that this remote may dial unattended: a primary with a
     *  relay port, not this device, not skipped, at another address than the
     *  current target. Not [Peer.isMirrorSource]: a hub that restarted before
     *  its DE1 reconnected must still be found. */
    private fun refindCandidate(current: RemoteSession): Peer? = _state.value.peers.firstOrNull {
        it.role == "primary" && it.port > 0 && it.id != instanceId && it.id !in refindSkipped &&
            (it.host to it.port) != current.target
    }

    /** Check the re-find's conditions and, when they hold, dial a candidate. */
    private fun evaluateRefind() {
        val current = session ?: return
        if (_state.value.role != "secondary" || refindHeld || refindQueued || _state.value.refindGaveUp) return
        if (refindBackoff?.isActive == true) return
        if (!current.connectingLong && !lastUnattendedRefused) return
        // A candidate that never answered counts as tried: skip it and go back.
        if (current.candidate != null) {
            if (!current.connectingLong) return
            appendLog("Re-find: ${current.candidate.name} didn't answer — going back")
            refindSkipped += current.candidate.id
            requestRefindSwitch(null, current.previous, current)
            return
        }
        val candidate = refindCandidate(current)
        if (candidate == null) {
            if (refindRefusals >= REFIND_MAX_REFUSALS) giveUpRefind()
            return
        }
        appendLog("Re-find: trying ${candidate.name} at ${candidate.host}:${candidate.port}")
        requestRefindSwitch(candidate, current.target, current)
    }

    private fun requestRefindSwitch(candidate: Peer?, target: Pair<String, Int>?, from: RemoteSession) {
        val (host, port) = (if (candidate != null) candidate.host to candidate.port else target) ?: return
        refindQueued = true
        applyMode(
            "secondary", host, port, trust = MirrorTrust.UNATTENDED,
            refind = RefindSwitch(candidate, target, from),
        )
    }

    private fun giveUpRefind() {
        refindSeenAtGiveUp = _state.value.peers.map { it.id }.toSet()
        appendLog("Re-find: no hub proved this device's pairing — giving up until a new one appears")
        _state.update { it.copy(refindGaveUp = true) }
    }

    /** A remote session was welcomed: the hub proved the pairing. */
    private fun onRemoteWelcomed(welcomed: RemoteSession) {
        if (welcomed !== session) return
        if (welcomed.candidate != null) appendLog("Re-find: reattached to ${welcomed.candidate.name}")
        refindRefusals = 0
        lastUnattendedRefused = false
        _state.update { it.copy(refindGaveUp = false) }
    }

    /**
     * A remote session ended in a refusal ([ProxyTransport]'s `onRefused`).
     * - The user's own pick: back to normal with the reason, as before.
     * - A hub that proved the held secret and then refused (revoked, another
     *   protocol version): normal, as before.
     * - A re-find candidate otherwise: skipped by instance id, back-off, and
     *   the previous target re-applied, because a refused link never redials.
     * - The current target, refused for a trust reason (host mismatch or
     *   changed, unverified, handshake mismatch): stays a remote, and the
     *   re-find may look elsewhere. Any other refusal there (the host's own
     *   Denied) still makes it normal.
     */
    private fun handleSessionRefused(refused: RemoteSession?, reason: String, byHost: Boolean, hostProved: Boolean) {
        if (refused == null || refused !== session || _state.value.role != "secondary") return
        val verifiedDenial = hostProved && (byHost || reason.startsWith(DenyReason.VERSION_MISMATCH))
        val trustRefusal = !byHost && reason in TRUST_REFUSALS
        when {
            refused.trust.repin -> demote(reason)
            refused.candidate != null && !verifiedDenial -> {
                refindSkipped += refused.candidate.id
                refindRefusals++
                lastUnattendedRefused = true
                appendLog("Re-find: ${refused.candidate.name} refused ($reason) — skipped, going back")
                val wait = REFIND_BACKOFF_MS[minOf(refindRefusals, REFIND_BACKOFF_MS.size) - 1]
                refindBackoff?.cancel()
                refindBackoff = scope.launch {
                    delay(wait)
                    refindBackoff = null // done waiting: this job is still active in its own body
                    evaluateRefind()
                }
                requestRefindSwitch(null, refused.previous, refused)
            }
            refused.candidate == null && trustRefusal -> {
                appendLog("Unattended session refused ($reason) — staying a remote")
                notify(deniedMessage(reason))
                lastUnattendedRefused = true
                evaluateRefind()
            }
            else -> demote(reason)
        }
    }

    /** Called by tests for the session the current transport serves. */
    @androidx.annotation.VisibleForTesting
    internal fun refuseCurrentSessionForTest(reason: String, byHost: Boolean, hostProved: Boolean) =
        handleSessionRefused(session, reason, byHost, hostProved)

    /** The host refused this device: stop being a remote, with the reason. */
    private fun demote(reason: String) {
        scope.launch {
            appendLog("Mirror denied by host: $reason")
            notify(deniedMessage(reason))
            switchToNormal()
        }
    }

    /** NSD says the address this remote dials now belongs to a device that
     *  isn't a hub. */
    private fun refreshHubStoppedHosting() {
        val current = session
        val stopped = current != null && _state.value.peers.any {
            it.host == current.host && it.role != "primary" && it.id != instanceId
        }
        if (_state.value.hubStoppedHosting != stopped) _state.update { it.copy(hubStoppedHosting = stopped) }
    }

    /** NSD changed: forget skipped ids that left, resume after a give-up when
     *  a new instance appears, and re-check. */
    private fun onPeersChanged(peers: List<Peer>) {
        val ids = peers.map { it.id }.toSet()
        refindSkipped.retainAll(ids)
        if (_state.value.refindGaveUp && (ids - refindSeenAtGiveUp).isNotEmpty()) {
            appendLog("Re-find: a new hub appeared — trying again")
            _state.update { it.copy(refindGaveUp = false) }
        }
        refreshHubStoppedHosting()
        evaluateRefind()
    }

    /** Whether this device holds a pairing secret for the host its target is
     *  pinned to: a discovered hub at that target is one it has paired with. */
    fun hasPinnedHub(): Boolean = pairing.pinnedHost?.let { pairing.heldSecret(it) != null } ?: false

    /** The pairing store, for tests. */
    @androidx.annotation.VisibleForTesting
    internal val pairingStore: PairingStore get() = pairing

    /** Refresh the hub's Remotes list, wired to [RelayHub.onClientsChanged].
     *  No-op off-hub. */
    private fun refreshRemotes() {
        val remotes = relayHub?.remotes() ?: emptyList()
        _state.update { it.copy(remotes = remotes) }
    }

    // ── Multi-device pairing (issue 02) ───────────────────────────────────────

    /**
     * Open the host's pairing window for [PAIRING_WINDOW_S] seconds: only then
     * does a device without a pairing secret raise the "Allow this device?"
     * prompt. It closes on its own, after one approval, on a mode switch, or
     * with [closePairingWindow].
     */
    fun openPairingWindow() {
        pairingWindowJob?.cancel()
        promptsThisWindow = 0
        appendLog("Pairing window open for ${PAIRING_WINDOW_S}s")
        pairingWindowJob = scope.launch {
            try {
                for (left in PAIRING_WINDOW_S downTo 1) {
                    _state.update { it.copy(pairingSecondsLeft = left) }
                    delay(1_000L)
                }
            } finally {
                _state.update { it.copy(pairingSecondsLeft = 0) }
                // A prompt still up when the window ends expires with it.
                pairingDeferred?.complete(null)
            }
        }
    }

    /** Close the pairing window now (a pending prompt expires). */
    fun closePairingWindow() {
        pairingWindowJob?.cancel()
        pairingWindowJob = null
    }

    private val pairingOpen: Boolean get() = pairingWindowJob?.isActive == true

    /**
     * Primary-side gate wired into [RelayHub]'s `authorize`. A remembered device
     * that proves the secret this host issued it is admitted silently with its
     * stored scope. Anything else is refused unless the pairing window is open;
     * then the host sees one "Allow this device?" prompt at a time
     * ([State.pendingPairing]), with a cooldown between prompts and a cap per
     * window. Approving issues a new secret (sent once, in the Welcome), stores
     * it, remembers the device and closes the window. A denial is remembered
     * until hosting stops.
     */
    internal suspend fun authorizePeer(request: PairingRequest): PairingDecision {
        val id = request.clientId
        val known = pairedDevices().firstOrNull { it.id == id }
        val secret = pairing.issuedSecret(id)
        if (known != null && secret != null && request.proves(secret)) {
            return PairingDecision.Allowed(known.canControl)
        }
        if (id in deniedWhileHosting) return PairingDecision.Denied(DenyReason.NOT_AUTHORIZED)
        // A device that already holds a secret from us must prove it; a new
        // pairing for it needs the host user to Forget it first.
        if (known != null && secret != null) {
            appendLog("Pairing request from ${request.clientName} refused: already paired (Forget it to pair again)")
            return PairingDecision.Denied(DenyReason.ALREADY_PAIRED)
        }
        if (!pairingOpen) {
            appendLog("Pairing request from ${request.clientName} refused: pairing is closed")
            return PairingDecision.Denied(DenyReason.PAIRING_CLOSED)
        }
        if (promptCooling || promptsThisWindow >= MAX_PROMPTS_PER_WINDOW || !pairingMutex.tryLock()) {
            return PairingDecision.Denied(DenyReason.BUSY)
        }
        try {
            promptsThisWindow++
            val deferred = CompletableDeferred<PairingChoice?>()
            pairingDeferred = deferred
            _state.update { it.copy(pendingPairing = PairingPrompt(id, request.clientName)) }
            // finally: the prompt never outlives its request (cancelled when the
            // relay stops or the scope ends), and the next one waits a cooldown.
            // A peer that merely disconnects mid-prompt does not cancel it yet:
            // the relay isn't reading its socket while the handshake is pending.
            val choice = try {
                deferred.await()
            } finally {
                pairingDeferred = null
                _state.update { it.copy(pendingPairing = null) }
                startPromptCooldown()
            }
            return when (choice) {
                // The window closed with the prompt up: not a denial.
                null -> PairingDecision.Denied(DenyReason.PAIRING_CLOSED)
                PairingChoice.DENY -> {
                    deniedWhileHosting += id
                    PairingDecision.Denied(DenyReason.NOT_AUTHORIZED)
                }
                PairingChoice.MIRROR_ONLY, PairingChoice.ALLOW_CONTROL -> {
                    val canControl = choice == PairingChoice.ALLOW_CONTROL
                    val issued = PairingCrypto.newSecret()
                    if (!pairing.putIssued(id, issued)) appendLog("Pairing secret for ${request.clientName} not saved: this session works, but the device will have to pair again next time")
                    rememberPaired(id, request.clientName, canControl)
                    _state.update { it.copy(pairedWithSecret = pairing.issuedIds()) }
                    closePairingWindow() // one pairing per window
                    PairingDecision.Allowed(canControl, issuedSecret = issued)
                }
            }
        } finally {
            pairingMutex.unlock()
        }
    }

    private fun startPromptCooldown() {
        promptCooling = true
        scope.launch {
            delay(PROMPT_COOLDOWN_MS)
            promptCooling = false
        }
    }

    /** The Activity resolves a pending [PairingPrompt] with the user's choice. */
    fun resolvePairing(choice: PairingChoice) {
        pairingDeferred?.complete(choice)
    }

    /** This device forgets the host [hostId]: drop the secret it holds for it
     *  (and the pin, if it pointed there), so picking that host again may ask
     *  to pair. The only way a held secret is ever replaced. */
    fun forgetHost(hostId: String) {
        pairing.removeHeld(hostId)
        if (pairing.pinnedHost == hostId) pairing.pinHost(null)
        _state.update { it.copy(changedHost = null) }
        appendLog("Forgot host $hostId on this device")
    }

    /** Keep the held secret; just dismiss the notice. */
    fun dismissChangedHost() {
        _state.update { it.copy(changedHost = null) }
    }

    /** The host forgot [clientId] (Settings ▸ Advanced ▸ Paired devices): drop its secret
     *  and end its live session, if any. */
    fun forgetPaired(clientId: String) {
        pairing.removeIssued(clientId)
        relayHub?.revoke(clientId)
        _state.update { it.copy(pairedWithSecret = pairing.issuedIds()) }
    }

    /** A secondary on another protocol version was turned away: tell the host
     *  user, at most once a minute. */
    private fun onOutdatedPeer(clientName: String) {
        appendLog("Refused $clientName: different Crema version")
        if (outdatedNoticeCooling) return
        outdatedNoticeCooling = true
        notify("$clientName runs a different Crema version — update both devices to connect")
        scope.launch {
            delay(OUTDATED_NOTICE_COOLDOWN_MS)
            outdatedNoticeCooling = false
        }
    }

    // ── Mode construction ─────────────────────────────────────────────────────

    private fun startPersistedRole(): BleTransport {
        val cfg = readProxyConfigSync()
        // A secondary's core mirrors the DE1 but must never drive it — make it a
        // read-only observer from the start (preserved across session resets).
        setCoreReadOnly(cfg.role == "secondary")
        return when (cfg.role) {
            "secondary" -> startSecondaryMode(cfg.host, cfg.port, MirrorTrust.UNATTENDED, refind = null)
            "primary" -> startPrimaryMode()
            else -> normalMode()
        }
    }

    /** NORMAL: the one real Nordic transport (created lazily, reused across switches). */
    private fun normalMode(): BleTransport =
        nordicTransport ?: NordicBleTransport(app).also { nordicTransport = it }

    /** PRIMARY: tap the real (or replayed) link into a relay + start the LAN server. */
    private fun startPrimaryMode(): BleTransport {
        // Replay a captured shot as a fake DE1 ONLY when explicitly opted in (an
        // emulator with no Bluetooth). Otherwise live BT — without this gate a
        // primary's own session recordings in `captures/` would be auto-replayed on
        // the next launch, hijacking a real primary into a fake DE1 and starving the
        // real DE1/scale scan (the transport would be the replay).
        val capture = if (readProxyConfigSync().replayPrimary) newestCapture() else null
        val real: BleTransport = if (capture != null) {
            appendLog("Multi-device: PRIMARY (replay ${capture.name})")
            ReplayBleTransport(
                ReplayBleTransport.parse(capture.readText()),
                scope, deviceName = "DE1", deviceAddress = "DE1:REPLAY", route = ::replayRoute,
            )
        } else {
            appendLog("Multi-device: PRIMARY (live BLE)")
            normalMode()
        }
        var tappingRef: TappingBleTransport? = null
        deniedWhileHosting.clear() // a new hosting session
        val hub = RelayHub(
            // The stable host id a secondary files its pairing secret under.
            primaryId = pairing.hostId,
            primaryName = deviceLabel(),
            // The devices the primary holds: the DE1, plus the scale when one is
            // connected (issue 04) — its advertised name lets a secondary's scale
            // manager scan-match + re-derive the codec via connectScale.
            roster = roster,
            readSource = { a, s, c -> tappingRef!!.readByAddress(a, s, c) },
            // Latest-value state/identity chars snapshot on attach; the COUNTED
            // streams — the DE1 ShotSample and the scale weight — stay live-only or
            // the mirror's core double-counts (issue 04; M1-PROTOCOL §5).
            isSnapshotChar = isSnapshotChar,
            // Relayed user intent from a secondary → the machine command seam.
            controlHandler = { method, args, originId, originName ->
                when (val outcome = handleRelayedControl(method, args, originId, originName)) {
                    CommandOutcome.Accepted -> Result.success(Unit)
                    is CommandOutcome.Refused -> Result.failure(IllegalStateException(outcome.reason))
                    is CommandOutcome.Unknown -> Result.failure(IllegalStateException(outcome.detail))
                }
            },
            // The single-owner session config a mirror snaps to on attach (T2).
            configSource = { snapshotConfig() },
            // Pairing gate (issue 02): a valid pairing secret, or the host's
            // prompt while its pairing window is open.
            authorize = { request -> authorizePeer(request) },
            // Keep the Remotes list live as remotes come and go.
            onClientsChanged = { refreshRemotes() },
            onOutdatedPeer = { name -> onOutdatedPeer(name) },
            // The host proves the secret it issued before a device proves it back.
            pairingSecretFor = { clientId ->
                if (pairedDevices().any { it.id == clientId }) pairing.issuedSecret(clientId) else null
            },
        )
        relayHub = hub
        val tapping = TappingBleTransport(real, hub, scope)
        tappingRef = tapping
        val server = LanRelayServer(hub)
        relayServer = server
        scope.launch {
            runCatching {
                relayPort = server.start()
                appendLog("LAN relay listening on :$relayPort")
                refreshAdvertisement()
            }.onFailure { appendLog("LAN relay failed to start: ${it.message}") }
        }
        return tapping
    }

    /** SECONDARY: a self-connecting ProxyTransport to a primary's relay. */
    private fun startSecondaryMode(host: String, port: Int, trust: MirrorTrust, refind: RefindSwitch?): BleTransport {
        val url = "ws://$host:$port${LanRelayServer.PATH}"
        appendLog("Multi-device: SECONDARY → $url")
        val remote = RemoteSession(host, port, trust, refind?.candidate, refind?.previous)
        session = remote
        refindQueued = false
        // A child scope for everything this mode launches (the link-state + roster
        // collectors AND the ProxyTransport's own init collectors), so applyMode can
        // cancel them ALL on the next switch instead of leaking ~4 coroutines + a dead
        // ProxyTransport/link per secondary→x→secondary cycle (issue 10).
        if (trust.repin) pairing.pinHost(null)
        val modeScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        secondaryScope = modeScope
        val link = ReconnectingClientLink(url, modeScope)
        proxyLink = link
        // Reflect the link into the UI so a dropped hub shows "Reconnecting…",
        // not a frozen "Connected through" (issue 03). The link only counts as
        // up once the hub has welcomed this session (onWelcome below): until
        // then a socket can be open while the hub's pairing prompt waits.
        // The re-find's clock: CONNECTING for refindDelayMs.
        var connectingTimer: Job? = null
        modeScope.launch {
            link.state.collect { s ->
                if (s == LinkState.CONNECTING) {
                    _state.update { it.copy(mirrorReconnecting = true) }
                    connectingTimer?.cancel()
                    connectingTimer = modeScope.launch {
                        delay(refindDelayMs)
                        remote.connectingLong = true
                        evaluateRefind()
                    }
                } else {
                    connectingTimer?.cancel()
                    remote.connectingLong = false
                }
            }
        }
        val proxy = ProxyTransport(
            // clientId = the STABLE per-install id (issue 14) so the host finds
            // THIS device's pairing record, not every same-model phone's;
            // clientName = the human label shown in the host's prompt.
            link, modeScope, clientId = deviceId, clientName = deviceLabel(),
            // Snap this mirror's config to the primary's on every attach (T2).
            onConfig = { applyConfig(it) },
            // Reflect the granted scope (issue 02): view-only remotes can't
            // control. The hub's name labels the status line from now on.
            onWelcome = { scope, hubName ->
                _state.update { it.copy(mirrorViewOnly = scope == "mirror", hubName = hubName, mirrorReconnecting = false) }
                onRemoteWelcomed(remote)
            },
            // A refusal: the host declined or revoked us (issue 02), or this
            // device refused the host. handleSessionRefused decides the role.
            onRefused = { reason, byHost, hostProved -> handleSessionRefused(remote, reason, byHost, hostProved) },
            // "Who's driving" (issue 11): another mirror drove the machine → toast.
            onEvent = { text -> notify(text) },
            // Prove the pairing secret this device holds for the host it reaches,
            // and keep the one a host issues when it approves this device.
            pairingSecretFor = { hostId -> pairing.heldSecret(hostId) },
            onPaired = { hostId, hostName, secret ->
                if (pairing.putHeld(hostId, secret)) appendLog("Paired with $hostName")
                else appendLog("Paired with $hostName, but the pairing secret wasn't saved")
            },
            // Pin this target to the host it completes with; a restored or
            // automatic redial then refuses any other host at that address.
            pinnedHostId = { pairing.pinnedHost },
            onHostConfirmed = { hostId -> pairing.pinHost(hostId) },
            allowRepair = trust.allowRepair,
            // Offer to forget a host only for a target this device chose or
            // restored: an address the re-find dialed off an unauthenticated
            // advertisement must not raise "forget this host".
            onHostChanged = { hostId, hostName ->
                if (refind?.candidate == null) _state.update { it.copy(changedHost = hostId to hostName) }
                else appendLog("Re-find: $hostName at $host:$port couldn't prove the pairing this device holds for it")
            },
        )
        // The VM's commands now go to this primary.
        relayMachine = RelayMachine(
            send = { method, args -> proxy.sendControl(method, args) },
            showConfig = { machineActions.showConfig(it) },
            machineStateName = machineStateName,
            scope = scope,
            notify = notify,
            log = appendLog,
        )
        // Mirror the primary's SCALE (issue 04), roster-driven: attach it only once
        // the primary actually advertises one — no blind scan, so a scaleless primary
        // leaves us on "Not paired" (not a stuck "Scanning…"). connectScale's scan is
        // satisfied by the same roster, so this just times the trigger.
        modeScope.launch {
            proxy.deviceRoster.collect { devices ->
                _state.update { it.copy(hubHasMachine = devices.any { d -> d.kind == "de1" }) }
                val hasScale = devices.any { it.kind == "scale" }
                if (hasScale && scaleAttachWanted()) {
                    appendLog("Mirror: primary has a scale — attaching")
                    connectScale()
                }
            }
        }
        return proxy
    }

    // ── Live mode switches (M2 — no restart) ────────────────────────────────

    /** Stop being a remote: become a standalone NORMAL device and connect the
     *  DE1 directly (Stop, Connect directly, and a host's Denied). */
    fun switchToNormal() {
        resetRefind()
        applyMode("normal", "", 0)
    }

    /** Hold the DE1 and relay it to others (becomes the primary). */
    fun switchToPrimary() {
        resetRefind()
        applyMode("primary", "", 0)
    }

    /**
     * The Hub mode switch. On: become the hub, reconnecting over the tapping
     * transport. Off: step down to normal **without reconnecting**, leaving
     * the DE1 user-disconnected, so neither the reconnect loop nor companion
     * presence grabs it again and another device can connect. The user takes
     * it back here with Connect. Both are refused during a firmware flash.
     */
    fun setHubMode(on: Boolean) {
        if (on) {
            switchToPrimary()
        } else {
            resetRefind()
            applyMode("normal", "", 0, reconnect = false)
        }
    }

    /** Mirror the DE1 from a primary at [host]:[port] — the picker's "Mirror from X". */
    fun switchToSecondary(host: String, port: Int) {
        // The user picked this target: it may be a new host, or the same host
        // after a reset that needs pairing again.
        resetRefind()
        applyMode("secondary", host, port, trust = MirrorTrust.USER_PICK)
    }

    /**
     * Swap the transport at runtime: tear the managers' connections down, dispose
     * the old mode's resources, install the new delegate, persist the role (via
     * [onModeApplied]), and reconnect over it. The disconnect →
     * [SwitchableBleTransport.setDelegate] → reconnect bracket keeps any
     * notification flow from spanning the swap; the brief settle lets the
     * managers' fire-and-forget transport release run against the OLD delegate
     * before it is replaced.
     */
    private fun applyMode(
        role: String,
        host: String,
        port: Int,
        reconnect: Boolean = true,
        trust: MirrorTrust = MirrorTrust.UNATTENDED,
        refind: RefindSwitch? = null,
    ) {
        // Never move the radio while a firmware flash or its erase / reboot
        // window holds the DE1 (audit r2 H1 / r1 NOTE 8).
        if (refusedForFirmware(role)) {
            if (refind != null) refindQueued = false
            return
        }
        scope.launch {
            modeMutex.withLock {
                // A re-find switch runs only from the session that asked for
                // it: a user's switch (or a Denied) queued ahead of it wins.
                if (refind != null && (refind.from !== session || _state.value.role != "secondary")) {
                    appendLog("Re-find: a newer switch ran first — skipping")
                    if (refind.from === session) refindQueued = false
                    return@withLock
                }
                // Again under the lock: a switch queued behind another one may
                // start after a flash has taken the DE1.
                if (!refusedForFirmware(role)) applyModeLocked(role, host, port, reconnect, trust, refind)
                else if (refind != null) refindQueued = false
            }
        }
    }

    /** True (logged, user told) when a firmware flash holds the DE1, so a
     *  switch to [role] must not move the radio. */
    private fun refusedForFirmware(role: String): Boolean {
        if (!firmwareHoldsLink()) return false
        appendLog("Mode switch to $role refused: $FIRMWARE_BUSY_REASON")
        notify(FIRMWARE_BUSY_NOTICE)
        return true
    }

    private suspend fun applyModeLocked(
        role: String,
        host: String,
        port: Int,
        reconnect: Boolean,
        trust: MirrorTrust,
        refind: RefindSwitch? = null,
    ) {
        modeSwitchProbe?.invoke(role)
        closePairingWindow()
        // Not reconnecting (Hub mode off): a full user disconnect, which also
        // drops a DE1 scan still waiting for the machine, so nothing re-grabs it.
        if (!reconnect) disconnect()
        disconnectDevices()
        delay(150)
        relayServer?.stop(); relayServer = null; relayHub = null
        // Cancel the previous SECONDARY mode's collectors + ProxyTransport before
        // releasing the link, so nothing leaks across the switch (issue 10).
        secondaryScope?.cancel(); secondaryScope = null
        relayMachine = null
        proxyLink?.dispose(); proxyLink = null
        session = null
        switchable.setDelegate(
            when (role) {
                "secondary" -> startSecondaryMode(host, port, trust, refind)
                "primary" -> startPrimaryMode()
                else -> normalMode()
            },
        )
        _state.update {
            it.copy(
                role = role,
                // The granted scope only applies while secondary (issue 02);
                // a fresh attach's Welcome repopulates it.
                mirrorViewOnly = if (role == "secondary") it.mirrorViewOnly else false,
                // The Remotes list only applies while hub.
                remotes = if (role == "primary") it.remotes else emptyList(),
                // A new target's Welcome names the hub again. The re-find only
                // reaches the hub this device is pinned to, so it keeps the name.
                hubName = if (refind != null) it.hubName else "",
            )
        }
        // The VM mirrors role/host/port into MainUiState (with its sticky
        // rules), drops the mirror overlay, and persists the prefs.
        onModeApplied(role, host, port)
        // A secondary mirrors the machine but must never drive it: make its
        // core a read-only observer so the autonomous writes it derives from
        // the mirrored stream (SAW, frame-skip) are suppressed. normal/primary
        // are authoritative. reset() preserves this across reconnects.
        setCoreReadOnly(role == "secondary")
        setRecorderEnabled(role != "secondary") // a mirror doesn't record (issue 14)
        // Reconnect the DE1 over the new delegate (a secondary attaches to the
        // primary's DE1; primary/normal scan the real or replayed radio). The
        // scale, if any, is reconnected by the user — DE1-only mirror for now.
        // Hub mode off passes reconnect=false so the stepped-down hub does NOT
        // re-grab the DE1: another device needs it free.
        if (reconnect) connect()
        refreshAdvertisement()
        refreshHubStoppedHosting()
    }

    /** (Re)advertise this device's current role / DE1-hold / relay-port over NSD, so
     *  other devices' pickers see it (and can "Mirror from" it when it's hosting). */
    fun refreshAdvertisement() {
        val role = _state.value.role
        val holdsDe1 = role != "secondary" && de1Connected()
        val port = if (role == "primary") relayPort else 0
        peerDiscovery.advertise(deviceLabel(), role, holdsDe1, port)
    }

    /** Push the primary's device roster to attached mirrors — called when the
     *  DE1/scale connection set changes (issue 04). No-op off-primary. */
    fun pushRoster() {
        relayHub?.pushRoster()
    }

    /** Push the primary's session config to attached mirrors — called when the
     *  single-owner config changes (T2). No-op off-primary. */
    fun pushConfig() {
        relayHub?.pushConfig()
    }

    // ── Persisted proxy config ────────────────────────────────────────────────

    /** Read just the proxy role/host/port straight off `prefs.json`, synchronously,
     *  for [startPersistedRole] (which runs before the VM's async prefs load) —
     *  served from the construction-time [proxyConfigCache]. */
    private fun readProxyConfigSync(): ProxyConfig = proxyConfigCache

    private fun readProxyConfigUncached(): ProxyConfig = runCatching {
        val f = File(app.filesDir, "prefs.json")
        if (!f.exists()) return ProxyConfig("normal", "", 0)
        val p = json.decodeFromString(AppPrefs.serializer(), f.readText())
        ProxyConfig(p.proxyRole, p.proxyPrimaryHost, p.proxyPrimaryPort, p.replayPrimary)
    }.getOrDefault(ProxyConfig("normal", "", 0))

    /** Newest `session-*.jsonl` capture for the replay-backed PRIMARY demo. Checks the
     *  app's INTERNAL `filesDir/captures` FIRST — a newer-Android emulator (scoped
     *  storage) blocks the app from reading `adb push`ed files in its external dir, so
     *  `run-as <pkg> cp` a capture into internal storage there — then falls back to the
     *  EXTERNAL `getExternalFilesDir/captures`, where the app's own session recordings
     *  land on a real device. Internal-first also means a hand-placed demo capture wins
     *  over the replay's own auto-recordings (which write to external). */
    private fun newestCapture(): File? = runCatching {
        listOfNotNull(
            File(app.filesDir, "captures"),
            app.getExternalFilesDir(null)?.let { File(it, "captures") },
        ).firstNotNullOfOrNull { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.maxByOrNull { it.lastModified() }
        }
    }.getOrNull()

    /** Map a capture `src` label to its DE1 `(service, characteristic)` for replay. */
    private fun replayRoute(src: String): Pair<UUID, UUID>? = when (src) {
        "DE1_STATE" -> De1Uuids.SERVICE to De1Uuids.STATE_INFO
        "DE1_SHOT_SAMPLE" -> De1Uuids.SERVICE to De1Uuids.SHOT_SAMPLE
        "DE1_WATER_LEVELS" -> De1Uuids.SERVICE to De1Uuids.WATER_LEVELS
        "DE1_SHOT_SETTINGS" -> De1Uuids.SERVICE to De1Uuids.SHOT_SETTINGS
        "DE1_MMR_READ" -> De1Uuids.SERVICE to De1Uuids.MMR_READ
        else -> null
    }
}

/** Machine states that read as idle or asleep: a relayed IDLE in one of them
 *  is worded as a wake rather than a stop. `Idle` covers warm-up/ready. */
internal val IDLE_STATES = setOf("Sleep", "GoingToSleep", "Idle", "SchedIdle")

/** The `ControlErr` reason a primary gives while a firmware flash holds the DE1. */
internal const val FIRMWARE_BUSY_REASON = "DE1 is updating firmware"

/** What this device shows when it refuses a mode switch itself. */
internal const val FIRMWARE_BUSY_NOTICE = "Not now — the DE1 is updating firmware"

/** How long a remote's link stays CONNECTING before the re-find looks on NSD. */
internal const val REFIND_DELAY_MS = 5_000L

/** The wait after each refused re-find candidate: the first, second, and every later one. */
internal val REFIND_BACKOFF_MS = listOf(5_000L, 15_000L, 60_000L)

/** Refused candidates, with no other left, before the re-find gives up. */
internal const val REFIND_MAX_REFUSALS = 3

/** Refusals this device makes itself when a host can't show it's the one this
 *  device trusts: an unattended session refused for one keeps the remote role. */
internal val TRUST_REFUSALS = setOf(
    DenyReason.HOST_MISMATCH, DenyReason.HOST_CHANGED, DenyReason.HOST_UNVERIFIED, DenyReason.HANDSHAKE_MISMATCH,
)

/** How long the host's pairing window stays open. */
internal const val PAIRING_WINDOW_S = 120

/** Pause between two pairing prompts. */
internal const val PROMPT_COOLDOWN_MS = 3_000L

/** Pairing prompts allowed in one window. */
internal const val MAX_PROMPTS_PER_WINDOW = 5

private const val OUTDATED_NOTICE_COOLDOWN_MS = 60_000L

/** What a secondary shows when its host turns it away, from the host's reason. */
internal fun deniedMessage(reason: String): String = when {
    reason == DenyReason.PAIRING_CLOSED ->
        "Pairing needed — on the host, open Settings ▸ Advanced ▸ Paired devices ▸ Pair a device, then connect again"
    reason == DenyReason.BUSY -> "The host is busy — try again in a moment"
    reason == DenyReason.REVOKED -> "The host removed this device — pair again to reconnect"
    reason == DenyReason.ALREADY_PAIRED ->
        "The host already has a pairing for this device — on the host, Forget it under Paired devices, then pair again"
    reason == DenyReason.HOST_MISMATCH -> "This isn't the host this device mirrors — choose a host under Other devices"
    reason == DenyReason.HOST_CHANGED -> "This host's identity changed — forget it on this device to pair again"
    reason == DenyReason.HOST_UNVERIFIED ->
        "Couldn't verify this host for this device — choose it under Other devices to connect (it may need pairing again)"
    reason.startsWith(DenyReason.VERSION_MISMATCH) -> "Update Crema on both devices to connect"
    else -> "The host hasn't allowed this device"
}

/**
 * The transport behind [ProxyController.transport] between construction and
 * [ProxyController.start]: nothing to scan, nothing to connect to. The VM
 * builds its scanner and managers on the facade during construction but
 * drives them only after `start` has installed the persisted role.
 */
private object NotStartedTransport : BleTransport {
    private fun notStarted(): Nothing = error("the proxy transport is used before ProxyController.start()")

    override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> = emptyFlow()

    override suspend fun connect(device: BleTransport.DeviceHandle) = notStarted()

    override suspend fun disconnect(device: BleTransport.DeviceHandle) {}

    override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> =
        MutableStateFlow(BleTransport.ConnState.DISCONNECTED)

    override fun observe(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): Flow<BleTransport.Notification> =
        emptyFlow()

    override suspend fun write(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
        withoutResponse: Boolean,
    ) = notStarted()

    override suspend fun read(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): ByteArray = notStarted()
}
