package coffee.crema.ui

import android.app.Application
import android.os.Build
import coffee.crema.ble.BleTransport
import coffee.crema.ble.NordicBleTransport
import coffee.crema.ble.proxy.DeviceInfo
import coffee.crema.ble.proxy.FrameLink
import coffee.crema.ble.proxy.HubPairingRequest
import coffee.crema.ble.proxy.HubTrust
import coffee.crema.ble.proxy.LanRelayServer
import coffee.crema.ble.proxy.LinkState
import coffee.crema.ble.proxy.PairingDialer
import coffee.crema.ble.proxy.PairingState
import coffee.crema.ble.proxy.Peer
import coffee.crema.ble.proxy.PeerDiscovery
import coffee.crema.ble.proxy.PinWrite
import coffee.crema.ble.proxy.ProxyTransport
import coffee.crema.ble.proxy.ReconnectingClientLink
import coffee.crema.ble.proxy.RelayHub
import coffee.crema.ble.proxy.RelayHubPin
import coffee.crema.ble.proxy.RelayKeyLoad
import coffee.crema.ble.proxy.RelayKeyStore
import coffee.crema.ble.proxy.RelayRemotePin
import coffee.crema.ble.proxy.RelayUpgradeMigration
import coffee.crema.ble.proxy.RemoteClient
import coffee.crema.ble.proxy.RemotePeer
import coffee.crema.ble.proxy.ReplayBleTransport
import coffee.crema.ble.proxy.SCOPE_CONTROL
import coffee.crema.ble.proxy.SCOPE_MIRROR
import coffee.crema.ble.proxy.SecureRelayAcceptor
import coffee.crema.ble.proxy.SwitchableBleTransport
import coffee.crema.ble.proxy.TappingBleTransport
import coffee.crema.core.GateStatus
import coffee.crema.core.RelayFfiException
import coffee.crema.core.RelayKeys
import coffee.crema.core.RelayPairingGate
import coffee.crema.core.RelayPinSet
import coffee.crema.ble.De1Uuids
import coffee.crema.machine.CommandOrigin
import coffee.crema.machine.CommandOutcome
import coffee.crema.machine.LocalMachine
import coffee.crema.machine.ConfigEcho
import coffee.crema.machine.MachineActions
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.MachineCommands
import coffee.crema.machine.RelayMachine
import coffee.crema.machine.RelayedCommandDispatcher
import coffee.crema.persist.SafeFile
import coffee.crema.settings.AppPrefs
import java.io.File
import java.util.UUID
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/** This device's human label — the name peers see in the multi-device picker
 *  and the label stamped into backup envelopes. */
internal fun deviceLabel(): String = Build.MODEL ?: "crema"

/**
 * The hub's code-entry dialog (relay v4, spec §6.2): a remote asks to pair and
 * shows a 6-digit code; the hub user types it and chooses a scope. [shortId]
 * is the remote's claimed short id (unauthenticated, shown as a hint).
 * [wrongCodes] counts this window's failed codes; [lockedOut] is the third.
 */
data class PairingPrompt(
    val requestId: Long,
    val clientName: String,
    val shortId: String,
    val wrongCodes: Int = 0,
    /** The last code didn't match and the remote is retrying with a new one. */
    val lastWasWrong: Boolean = false,
)

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
    /** A mode switch landed: mirror role/host/port into MainUiState (sticky
     *  rules), drop the mirror overlay fields, and persist the prefs. */
    private val onModeApplied: (role: String, host: String, port: Int) -> Unit,
    /** The core's firmware lock (`bridge.firmwareLocksWrites()`): a flash or
     *  its erase / reboot window holds the DE1, so no mode switch may move the
     *  radio (audit r2 H1). */
    private val firmwareHoldsLink: () -> Boolean = { false },
    /** The relay v4 key store (tests pass one over a fake keystore). One per process. */
    relayStore: RelayKeyStore? = null,
    /** Where a primary runs relayed commands: Main, like its own UI (tests
     *  pass a test dispatcher). */
    private val commandLane: CoroutineContext = Dispatchers.Main.immediate,
    /** How long a remote's session may go without a Welcome before the
     *  re-find looks for its hub on a new port, and how long a re-find
     *  attempt has to be welcomed. */
    private val refindDelayMs: Long = REFIND_DELAY_MS,
    /** A role is about to start, before its transport is built: the VM
     *  creates or drops its mirror slice (I6). [refind] is a remote dialing
     *  its hub at a new address, which keeps the slice. */
    private val onRoleStarting: (role: String, refind: Boolean) -> Unit = { _, _ -> },
    /** A remote shows a relayed config edit until the hub answers (its mirror
     *  slice); the echo settles it. */
    private val showPending: (MachineCommand.Config) -> ConfigEcho = { ConfigEcho.NONE },
    /** A remote session was welcomed, with the hub's name. Called before any
     *  frame of that session is applied. */
    private val onWelcomed: (hubName: String) -> Unit = {},
    /** A re-find attempt was welcomed: the remote's saved target is now
     *  [host]:[port]. The VM shows and persists it (the role is unchanged). */
    private val onTargetMoved: (host: String, port: Int) -> Unit = { _, _ -> },
    /** Returns once the VM's prefs are loaded: a role change persisted before
     *  then would be overwritten by the load. */
    private val prefsReady: suspend () -> Unit = {},
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
        /** True when the hub's Welcome granted this mirror view-only. */
        val mirrorViewOnly: Boolean = false,
        /** The hub's code-entry dialog: a remote asks to pair (spec §6.2). */
        val pendingPairing: PairingPrompt? = null,
        /** Remotes connected through this hub: its read-only Remotes list. */
        val remotes: List<RemoteClient> = emptyList(),
        /** Seconds left in the hub's pairing window; 0 = closed. */
        val pairingSecondsLeft: Int = 0,
        /** Seconds left in the pairing lockout after three wrong codes; 0 = not locked. */
        val pairingLockedSecondsLeft: Int = 0,
        /** The remotes this hub has pinned (Paired devices). */
        val pairedRemotes: List<RelayRemotePin> = emptyList(),
        /** The hubs this remote has pinned (Paired hubs). */
        val pairedHubs: List<RelayHubPin> = emptyList(),
        /** The remote's pairing with a hub, for the code sheet (spec §6.1). */
        val pairing: PairingState = PairingState.Idle,
        /** The relay keys couldn't be read this start (transient): the relay is
         *  off (no listening, no dialing) until a restart (spec §6.3). */
        val relayKeysUnavailable: Boolean = false,
        /** A definitive key loss replaced the key: show the one-time notice. */
        val keyResetNotice: Boolean = false,
        /** The v3 upgrade: this remote must pair again before it dials (spec §6.4). */
        val needsRepair: Boolean = false,
        /** The v3 upgrade: this hub's one-time "pair your phones again" banner. */
        val upgradeNotice: Boolean = false,
        /** A remote's hub name, from the hub's Welcome; blank until then and
         *  in the other roles. */
        val hubName: String = "",
        /** A remote's hub lists the DE1 in its roster: it holds the machine. */
        val hubHasMachine: Boolean = false,
        /** The re-find gave up: three refusals in a row that weren't
         *  authenticated (the hub couldn't be verified, or kept refusing). The
         *  remote shows "Couldn't verify <hub>" with Pair again and Connect
         *  directly. */
        val refindGaveUp: Boolean = false,
        /** NSD lists this remote's hub address as a device that isn't a hub
         *  any more (it turned Hub mode off). Advice only: unauthenticated. */
        val hubStoppedHosting: Boolean = false,
        /** This remote holds a pin for its saved hub ([State.pairedHubs]). */
        val pairedHubPinned: Boolean = false,
        /** A remote's first session since the user's pick (or this start)
         *  hasn't been welcomed yet: "Connecting through ‹hub›…", never
         *  "Connected through" before the hub's Welcome (spec §6.1, AC-56). */
        val remoteConnecting: Boolean = false,
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
        hostName = { deviceLabel() },
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

    /** The relay port this hub bound last time (a tiny file, like [deviceId]).
     *  The next start asks for it first, so a remote's saved host:port still
     *  reaches the hub after a restart; 0 when none was saved. */
    private val relayPortFile = File(app.filesDir, "relayPort")

    private fun savedRelayPort(): Int =
        runCatching { relayPortFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() }.getOrNull()
            ?.takeIf { it in 1..65_535 } ?: 0

    /**
     * This device's relay v4 key and pins (spec §5.1): one store per process,
     * loaded once here, before anything reads the role, and the one-time v3
     * migration run right after (spec §6.4). A transient keystore error leaves
     * the relay off for this start ([State.relayKeysUnavailable]); a definitive
     * loss started over with a new key and no pins ([State.keyResetNotice]).
     */
    private val relay: RelayKeyStore = relayStore ?: RelayKeyStore.forProcess(app.filesDir)

    private val keyLoad: RelayKeyLoad = relay.load()

    /** This device's relay key, or null while the store is unavailable. */
    @Volatile private var relayKeys: RelayKeys? = (keyLoad as? RelayKeyLoad.Ready)?.keys

    init {
        when (val load = keyLoad) {
            is RelayKeyLoad.Unavailable -> appendLog("Relay: pairing keys couldn't be read this start (${load.reason}) — the relay stays off")
            is RelayKeyLoad.Ready -> {
                if (load.keyReset) appendLog("Relay: the pairing key was lost and replaced; every pairing was cleared")
                if (!load.persistent) appendLog("Relay: pairing can't be saved on this device (the key lives in memory only)")
            }
        }
        val outcome = RelayUpgradeMigration(app.filesDir, json).run(relay)
        if (outcome == RelayUpgradeMigration.Outcome.MIGRATED) appendLog("Relay: removed the v3 pairing state; devices pair again once")
    }

    /** The live pin set every hub connection reads (spec §2.6); updated before the store on a Forget. */
    private val livePins: RelayPinSet = RelayPinSet(relay.remotes.mapNotNull { pin -> runCatching { RelayKeyStore.publicKeyOf(pin.public) }.getOrNull() })

    /** The one pairing gate per hub process (spec §2.5), shared by every hub connection. */
    private val gate = RelayPairingGate()

    /** This process's NSD instance id: a random id per launch, advertised for
     *  discovery and used to filter our own advertisement out. */
    internal val instanceId: String = UUID.randomUUID().toString()

    /** Construction-time proxy-config snapshot — read `prefs.json` ONCE
     *  (review #36: role/host/port are restart-to-apply anyway). A PLAIN val,
     *  not a lazy: construction order stays linear within this class body
     *  (the lazy-below-use variant shipped a launch-dead APK — 4eeb92f2). */
    private val proxyConfigCache: ProxyConfig = readProxyConfigUncached()

    private val _state = MutableStateFlow(
        State(
            role = proxyConfigCache.role,
            relayKeysUnavailable = keyLoad is RelayKeyLoad.Unavailable,
            keyResetNotice = relay.keyResetNotice,
            needsRepair = relay.needsRepair,
            upgradeNotice = relay.upgradeNotice,
            pairedRemotes = relay.remotes,
            pairedHubs = relay.hubs,
            pairedHubPinned = savedHubPin() != null,
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** The open pairing window's countdown (reads the gate each second). */
    private var pairingWindowJob: Job? = null

    /** The hub's open pairing request (the code dialog's), or null. */
    @Volatile private var openRequest: HubPairingRequest? = null

    /** Remote addresses the hub user turned away in this window (the squat rule, spec §2.5). */
    private val turnedAway: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Rate limit for the "needs an update" notice. */
    /** When the last older-remote notice showed (one a minute in all), and how
     *  many refusals came in since without one. */
    private var outdatedNoticeAtMs = 0L
    private var outdatedNoticesHeld = 0
    private val outdatedNoticeLock = Any()

    /** Test seam: the older-remote notice's clock. */
    @androidx.annotation.VisibleForTesting
    internal var outdatedNoticeClock: () -> Long = System::currentTimeMillis

    /** The remote's pairing with a hub, while one runs ([pairWith]). */
    private var dialer: PairingDialer? = null
    private var dialerJob: Job? = null

    /** The hub's secure acceptor while hosting. */
    private var acceptor: SecureRelayAcceptor? = null

    /** Who picked a remote session's target: only the user's own pick
     *  ([USER_PICK]) is told to start over on its first refusal. Anything
     *  unattended — a restored target, an automatic re-mirror, a re-find —
     *  keeps the role through advisory refusals ([UNATTENDED]). Resume never
     *  pairs (spec Appendix B): pairing runs only from [pairWith]. */
    internal data class MirrorTrust(val userPick: Boolean) {
        companion object {
            val USER_PICK = MirrorTrust(userPick = true)
            val UNATTENDED = MirrorTrust(userPick = false)
        }
    }

    /** Serializes [applyMode]: mode switches launch from several async paths
     *  (the picker, the Hub mode switch, denied→normal) and interleaved
     *  teardown/build would leak a relay server or install two transports.
     *  Switches run one at a time, in the order they were requested. */
    private val modeMutex = Mutex()

    /** Bumped by every mode switch ([applyModeLocked]): a switch planned
     *  against one mode runs only if no other ran in between
     *  ([applyModeIfEpoch]). */
    @Volatile var modeEpoch = 0L
        private set

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
        val role = startRole()
        onRoleStarting(role, false)
        switchable.setDelegate(startPersistedRole(role))
        // A secondary (read-only mirror) doesn't record the primary's shot (issue 14).
        setRecorderEnabled(role != "secondary")
        collectPeers(peerDiscovery.peers)
        peerDiscovery.startDiscovery()
        refreshAdvertisement()
    }

    private fun collectPeers(source: Flow<List<Peer>>) {
        peersJob?.cancel()
        peersJob = scope.launch {
            source.collect { p ->
                _state.update { it.copy(peers = p) }
                onPeersChanged()
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

    /** Mirror [host]:[port] without the user picking it, with the saved hub's
     *  key: only that hub completes the handshake, and nothing about the
     *  address is saved before its Welcome. */
    internal fun remirrorUnattended(host: String, port: Int) {
        applyMode("secondary", host, port, trust = MirrorTrust.UNATTENDED)
    }

    // ── Re-find: a remote whose hub moved finds it again (spec §3.3) ─────────

    /** The session a remote's current transport serves. [attempt] is set when
     *  the re-find dialed it: a new port on the saved host, tried until its
     *  first Welcome. Callbacks from any other session are stale. */
    private class RemoteSession(
        val host: String,
        val port: Int,
        val trust: MirrorTrust,
        val attempt: Boolean,
        /** The hub pin this session dials with: the user's pick, or the saved
         *  hub. Its address and the saved hub change only on a Welcome. */
        val hub: RelayHubPin?,
    ) {
        /** Not welcomed for [refindDelayMs]: since the session started, or
         *  since its link last dropped. A socket that is open but silent
         *  counts as not welcomed. */
        var stalled = false
        /** The hub welcomed this session on one of its link's dials. A
         *  welcomed re-find attempt is the saved target from then on. */
        var welcomed = false
        /** This session was refused, so its link is closed and nothing
         *  redials it. */
        var refused = false
        val target: Pair<String, Int> get() = host to port
        /** A re-find attempt still waiting for its first Welcome. */
        val trying: Boolean get() = attempt && !welcomed
    }

    /** A switch the re-find asked for: try a new port on the saved host
     *  ([attempt]), or go back to the saved target. Runs only if [from] is
     *  still the session when the switch gets the mode lock. */
    private class RefindSwitch(val attempt: Boolean, val from: RemoteSession)

    @Volatile private var session: RemoteSession? = null

    /** The remote's saved target: what it restored, what the user picked, or
     *  a re-find attempt that was welcomed. The re-find dials only this host,
     *  and a refused attempt comes back here. */
    private var savedTarget: Pair<String, Int>? = null

    /** The remote target in the prefs: what this start read, or the last
     *  welcomed session's. An address reaches the prefs, the hub pin and the
     *  saved hub only once a session there was authenticated and welcomed
     *  (spec §2.9: NSD and the picker are unauthenticated input). */
    private var persistedTarget: Pair<String, Int>? =
        proxyConfigCache.takeIf { it.host.isNotBlank() && it.port > 0 }?.let { it.host to it.port }

    /** Unattended refusals and unanswered attempts since the last Welcome. */
    private var refindRefusals = 0

    /** A re-find switch is queued and hasn't started yet. */
    private var refindQueued = false

    /** Running while the next attempt must wait (5 s, 15 s, then 60 s). */
    private var refindBackoff: Job? = null

    /** The hubs NSD listed on the saved host when the re-find gave up, as
     *  (instance id, port): any new one resumes it, including the hub back
     *  on its saved port under a new id. */
    private var refindSeenAtGiveUp: Set<Pair<String, Int>> = emptySet()

    /** When each port on the saved host was last tried, as a running count:
     *  the least recently tried port goes first, so one bad advertisement
     *  can't take every attempt. */
    private val refindTriedAt = mutableMapOf<Int, Long>()
    private var refindTries = 0L

    /** Held by the leave-remote flow (P2's frozen slice): no re-find runs. */
    private var refindHeld = false

    /** Hold or release the re-find. While held it switches nothing: no new
     *  attempt, no go-back from an attempt that ends (it waits for the
     *  release), and a re-find switch already queued is dropped. A release
     *  re-checks at once. */
    fun holdRefind(hold: Boolean) {
        refindHeld = hold
        if (!hold) evaluateRefind()
    }

    /** The user chose a mode: forget everything the re-find learnt. */
    private fun resetRefind() {
        refindBackoff?.cancel()
        refindBackoff = null
        refindRefusals = 0
        refindQueued = false
        refindSeenAtGiveUp = emptySet()
        refindTriedAt.clear()
        _state.update { it.copy(refindGaveUp = false) }
    }

    /** The hubs NSD lists on the saved target's host: a primary with a relay
     *  port, not this device. Only the port may differ from the saved target
     *  (a hub that restarted and couldn't get its port back); another address
     *  or device is never dialed unattended, because nothing binds a session
     *  to the endpoint it dialed (an on-path relay stays possible until
     *  protocol v3). Not [Peer.isMirrorSource]: a hub that restarted before
     *  its DE1 reconnected must still be found. */
    private fun hubsOnSavedHost(): List<Peer> {
        val (host, _) = savedTarget ?: return emptyList()
        return _state.value.peers.filter {
            it.role == "primary" && it.port > 0 && it.id != instanceId && sameAddress(it.host, host)
        }
    }

    /** A port the re-find may try: on the saved host, not the current
     *  target, and only for a target whose hub this device has pinned (so
     *  never from a user's pick still waiting for its Welcome). The least
     *  recently tried port first; never-tried ones in NSD's order. */
    private fun refindCandidate(current: RemoteSession): Peer? {
        if (savedHubPin() == null || (current.trust.userPick && !current.welcomed)) return null
        return hubsOnSavedHost().filter { it.port != current.port }.minByOrNull { refindTriedAt[it.port] ?: 0L }
    }

    /** Check the re-find's conditions and, when they hold, dial a candidate
     *  or go back to the saved target. */
    private fun evaluateRefind() {
        val current = session ?: return
        if (_state.value.role != "secondary" || refindHeld || refindQueued || _state.value.refindGaveUp) return
        // The handshake deadline: an attempt not welcomed in time ends. So
        // does one refused while the re-find was held.
        if (current.trying) {
            if (current.refused) endAttempt(current, "was refused")
            else if (current.stalled) endAttempt(current, "didn't answer")
            return
        }
        if (refindBackoff?.isActive == true) return
        if (!current.stalled && !current.refused) return
        if (refindRefusals >= REFIND_MAX_REFUSALS) {
            giveUpRefind()
            return
        }
        val candidate = refindCandidate(current)
        when {
            candidate != null -> {
                appendLog("Re-find: trying ${candidate.name} at ${candidate.host}:${candidate.port}")
                refindTriedAt[candidate.port] = ++refindTries
                requestRefindSwitch(candidate.host, candidate.port, attempt = true, from = current)
            }
            // A refused link never redials: dial the saved target again.
            current.refused -> savedTarget?.let { (host, port) ->
                requestRefindSwitch(host, port, attempt = false, from = current)
            }
        }
    }

    private fun requestRefindSwitch(host: String, port: Int, attempt: Boolean, from: RemoteSession) {
        refindQueued = true
        applyMode(
            "secondary", host, port, trust = MirrorTrust.UNATTENDED,
            refind = RefindSwitch(attempt, from),
        )
    }

    private fun giveUpRefind() {
        refindSeenAtGiveUp = hubsOnSavedHost().map { it.id to it.port }.toSet()
        appendLog("Re-find: no hub proved this device's pairing — giving up until a new one appears")
        _state.update { it.copy(refindGaveUp = true) }
    }

    /** Count an unattended refusal and wait before the next try. */
    private fun backOff() {
        refindRefusals++
        val wait = REFIND_BACKOFF_MS[minOf(refindRefusals, REFIND_BACKOFF_MS.size) - 1]
        refindBackoff?.cancel()
        refindBackoff = scope.launch {
            delay(wait)
            refindBackoff = null // done waiting: this job is still active in its own body
            evaluateRefind()
        }
    }

    /** A re-find attempt was refused or didn't answer in time: count it, back
     *  off, and go back to the saved target now. Nothing about the attempt is
     *  saved. */
    private fun endAttempt(attempt: RemoteSession, why: String) {
        // Held (the leave-remote prompt is open): no switch now. A refused
        // attempt is marked, so the release ends it.
        if (refindHeld) {
            appendLog("Re-find: ${attempt.host}:${attempt.port} $why — held, going back on release")
            return
        }
        appendLog("Re-find: ${attempt.host}:${attempt.port} $why — going back")
        backOff()
        savedTarget?.let { (host, port) -> requestRefindSwitch(host, port, attempt = false, from = attempt) }
    }

    /**
     * A remote session was welcomed: the hub proved the pairing (IK with the
     * pinned key, then `Welcome`). Only now is anything about its address
     * saved: the dialed hub becomes the saved hub, its pin follows the
     * address, and the target reaches the prefs. A re-find attempt becomes
     * the saved target.
     */
    private fun onRemoteWelcomed(welcomed: RemoteSession) {
        if (welcomed !== session) return
        welcomed.welcomed = true
        if (savedTarget != welcomed.target) {
            if (welcomed.attempt) appendLog("Re-find: reattached at ${welcomed.host}:${welcomed.port}")
            savedTarget = welcomed.target
        }
        commitWelcomedTarget(welcomed)
        refindBackoff?.cancel()
        refindBackoff = null
        refindRefusals = 0
        _state.update { it.copy(refindGaveUp = false) }
    }

    /** Save a welcomed session's hub and address (see [onRemoteWelcomed]). */
    private fun commitWelcomedTarget(welcomed: RemoteSession) {
        val hub = welcomed.hub ?: return
        runCatching {
            if (relay.savedHub != hub.id) relay.setSavedHub(hub.id)
            val pin = relay.hubs.firstOrNull { it.id == hub.id }
            if (pin != null && (pin.host != welcomed.host || pin.port != welcomed.port)) {
                relay.pinHub(pin.copy(host = welcomed.host, port = welcomed.port))
            }
        }
        refreshPins()
        if (persistedTarget != welcomed.target) {
            persistedTarget = welcomed.target
            onTargetMoved(welcomed.host, welcomed.port)
        }
    }

    /**
     * The hub refused a remote session ([ProxyTransport]'s `onRefused`). Only
     * an authenticated refusal changes trust state or role (spec §2.9):
     * - authenticated `Revoked` / `NotPaired`: the hub forgot this device. The
     *   hub pin is dropped and the remote becomes normal;
     * - `HubStopped`, `Normal`, `Protocol`, `Timeout`: role and pin kept; the
     *   link redials with its back-off (a `HubStopped` says so once);
     * - anything else is advisory (an `ABORT`, a legacy or newer hub, a hub
     *   that couldn't be verified): role and pin kept, back-off, and after
     *   three in a row the re-find gives up ("Couldn't verify <hub>"). A
     *   re-find attempt goes back to the saved target.
     */
    private fun handleSessionRefused(refused: RemoteSession?, reason: String, authenticated: Boolean, terminal: Boolean) {
        if (refused == null || refused !== session || _state.value.role != "secondary") return
        when {
            authenticated && (reason == REFUSED_REVOKED || reason == REFUSED_NOT_PAIRED) -> {
                // The hub this session dialed signed it (with its pinned key):
                // drop that pin, never another hub's. The saved hub is cleared
                // only when it is that hub.
                refused.hub?.let { pin ->
                    runCatching { relay.unpinHub(pin.id) }
                    refreshPins()
                }
                demote(reason)
            }
            !terminal -> {
                appendLog("Hub session closed ($reason) — reconnecting")
                if (reason == REFUSED_HUB_STOPPED) notify(refusalMessage(reason, hubLabel()))
            }
            refused.trying -> {
                refused.refused = true
                endAttempt(refused, "refused ($reason)")
            }
            else -> {
                refused.refused = true
                appendLog("Hub refused the session ($reason, unauthenticated) — staying a remote")
                if (refindRefusals == 0) notify(refusalMessage(reason, hubLabel()))
                backOff()
            }
        }
    }

    /** Called by tests for the session the current transport serves. */
    @androidx.annotation.VisibleForTesting
    internal fun refuseCurrentSessionForTest(reason: String, authenticated: Boolean, terminal: Boolean = true) =
        handleSessionRefused(session, reason, authenticated, terminal)

    /** The hub this remote dials, by name for the copy. */
    private fun hubLabel(): String =
        _state.value.hubName.ifBlank { (session?.hub ?: savedHubPin())?.name ?: session?.host ?: savedTarget?.first.orEmpty() }

    /** The hub refused this device: stop being a remote, with the reason.
     *  Only from the mode it refused (R1): a switch that ran first — a
     *  remote → standalone commit above all — already took this device out
     *  of the remote role, and must not be undone by a second switch. */
    private fun demote(reason: String) {
        val epoch = modeEpoch
        appendLog("Mirror refused by the hub: $reason")
        notify(refusalMessage(reason, hubLabel()))
        applyModeIfEpoch("normal", epoch)
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

    /** NSD changed: resume after a give-up when a new hub appears on the
     *  saved host (a new port, or a new instance on a known one), and
     *  re-check. */
    private fun onPeersChanged() {
        if (_state.value.refindGaveUp && (hubsOnSavedHost().map { it.id to it.port }.toSet() - refindSeenAtGiveUp).isNotEmpty()) {
            appendLog("Re-find: a hub appeared on ${savedTarget?.first} — trying again")
            refindRefusals = 0
            _state.update { it.copy(refindGaveUp = false) }
        }
        refreshHubStoppedHosting()
        evaluateRefind()
    }

    /** The pin of the hub this remote is saved to: the store's `savedHub`, or
     *  null when it has none (never paired, forgotten, or the v3 upgrade). */
    private fun savedHubPin(): RelayHubPin? = relay.savedHub?.let { id -> relay.hubs.firstOrNull { it.id == id } }

    /** Mirror the store's pins and flags into [state]. */
    private fun refreshPins() {
        _state.update {
            it.copy(
                pairedRemotes = relay.remotes,
                pairedHubs = relay.hubs,
                pairedHubPinned = savedHubPin() != null,
                keyResetNotice = relay.keyResetNotice,
                needsRepair = relay.needsRepair,
                upgradeNotice = relay.upgradeNotice,
            )
        }
    }

    /** The relay store, for tests. */
    @androidx.annotation.VisibleForTesting
    internal val relayStore: RelayKeyStore get() = relay

    /** Refresh the hub's Remotes list, wired to [RelayHub.onClientsChanged].
     *  No-op off-hub. */
    private fun refreshRemotes() {
        val remotes = relayHub?.remotes() ?: emptyList()
        _state.update { it.copy(remotes = remotes) }
    }

    // ── Pairing, hub side (relay v4, spec §2.4, §2.5, §6.2) ─────────────────

    /**
     * Open the hub's pairing window ([PAIRING_WINDOW_S] seconds, the core gate's
     * `PAIR_WINDOW_MS`): only then does a remote's pairing request get a slot
     * and the code dialog. It closes on its own, after one pairing, after the
     * third wrong code (a lockout follows), on a mode switch, or with
     * [closePairingWindow]. Refused (and logged) during a lockout.
     */
    fun openPairingWindow() {
        val now = System.currentTimeMillis().toULong()
        try {
            gate.open(now)
        } catch (e: RelayFfiException) {
            appendLog("Pairing window not opened: ${relayKind(e)}")
            refreshGate()
            return
        }
        turnedAway.clear()
        appendLog("Pairing window open for ${PAIRING_WINDOW_S}s")
        watchGate()
    }

    /** Close the pairing window now: the request holding the slot aborts at once. */
    fun closePairingWindow() {
        gate.closeWindow()
        turnedAway.clear()
        openRequest?.let { r -> scope.launch { r.tick() } }
        refreshGate()
    }

    /** Follow the gate each second while it is open or locked out (the countdowns). */
    private fun watchGate() {
        pairingWindowJob?.cancel()
        pairingWindowJob = scope.launch {
            while (true) {
                val status = refreshGate()
                if (status == null || (!status.open && status.locked_secs_left == 0u)) break
                delay(1_000L)
            }
            turnedAway.clear()
        }
    }

    /** Read the gate into [State]; null when it couldn't be read. */
    private fun refreshGate(): GateStatus? {
        val status = runCatching {
            gateJson.decodeFromString(GateStatus.serializer(), gate.status(System.currentTimeMillis().toULong()))
        }.getOrNull()
        _state.update {
            it.copy(
                pairingSecondsLeft = status?.takeIf { s -> s.open }?.secs_left?.toInt() ?: 0,
                pairingLockedSecondsLeft = status?.locked_secs_left?.toInt() ?: 0,
            )
        }
        return status
    }

    /**
     * The hub user typed [code] in the code dialog and chose a scope
     * ([canControl] = View and control). The core refuses anything but six
     * digits without starting SPAKE2 or counting an attempt.
     */
    fun submitPairingCode(code: String, canControl: Boolean) {
        val r = openRequest ?: return
        scope.launch {
            if (!r.enterCode(code, canControl)) appendLog("Pairing code not accepted for ${r.remoteName}")
        }
    }

    /** The hub user cancelled the code dialog: `ABORT(CANCELLED)`, no failure
     *  counted, and that address gets BUSY for the rest of the window. */
    fun cancelPairing() {
        val r = openRequest ?: return
        openRequest = null
        turnedAway += r.address
        _state.update { it.copy(pendingPairing = null) }
        scope.launch { r.cancel() }
    }

    /** What the hub's connections need from this controller. */
    private val hubTrust = object : HubTrust {
        override val keys: RelayKeys get() = relayKeys ?: error("relay keys unavailable")
        override val gate: RelayPairingGate get() = this@ProxyController.gate
        override val pins: RelayPinSet get() = livePins
        override val hubName: String get() = deviceLabel()

        override fun onPairRequest(request: HubPairingRequest) {
            val previous = _state.value.pendingPairing
            openRequest = request
            _state.update {
                it.copy(
                    pendingPairing = PairingPrompt(
                        requestId = request.id,
                        clientName = request.remoteName,
                        shortId = request.shortId,
                        // A remote retrying after a wrong code keeps the count on screen.
                        wrongCodes = previous?.wrongCodes ?: 0,
                        lastWasWrong = previous?.lastWasWrong ?: false,
                    ),
                )
            }
        }

        override fun storePin(remoteId: String, remoteName: String, publicKey: ByteArray, canControl: Boolean): PinWrite? =
            runCatching {
                relay.pinRemoteSaved(
                    RelayRemotePin(
                        id = remoteId,
                        public = RelayKeyStore.b64(publicKey),
                        name = remoteName,
                        scope = if (canControl) SCOPE_CONTROL else SCOPE_MIRROR,
                        pairedAtMs = System.currentTimeMillis(),
                    ),
                )
            }.getOrNull()

        override fun restorePin(remoteId: String, previous: RelayRemotePin?) {
            runCatching { relay.restoreRemote(remoteId, previous) }
            refreshPins()
        }

        override fun onPairingFailed(request: HubPairingRequest, reason: String, failures: Int, locked: Boolean) {
            if (openRequest?.id != request.id) return
            openRequest = null
            when {
                locked -> {
                    notify(LOCKED_OUT_COPY)
                    _state.update { it.copy(pendingPairing = null) }
                    watchGate()
                }
                reason == "WrongCode" -> {
                    // The dialog stays for the remote's next attempt (spec §6.2);
                    // with none within the window, the window's end closes it.
                    _state.update { it.copy(pendingPairing = it.pendingPairing?.copy(wrongCodes = failures, lastWasWrong = true)) }
                    scope.launch {
                        delay(WRONG_CODE_GRACE_MS)
                        if (openRequest == null && _state.value.pendingPairing?.requestId == request.id) {
                            _state.update { it.copy(pendingPairing = null) }
                            notify(wrongCodeLine(request.remoteName, failures + 1))
                        }
                    }
                }
                else -> _state.update { it.copy(pendingPairing = null) }
            }
        }

        override fun onPairingNotSaved(request: HubPairingRequest) {
            if (openRequest?.id == request.id) openRequest = null
            _state.update { it.copy(pendingPairing = null) }
            notify(NOT_SAVED_COPY)
        }

        override fun onPaired(request: HubPairingRequest) {
            if (openRequest?.id == request.id) openRequest = null
            val pin = relay.remotes.firstOrNull { it.id == request.remoteId }
            _state.update { it.copy(pendingPairing = null) }
            refreshPins()
            refreshGate()
            val paired = pairedRemote(request.remoteName, pin?.scope == SCOPE_CONTROL)
            appendLog(paired)
            notify(paired)
            if (relay.upgradeNotice) relay.clearUpgradeFlags()
        }

        override fun onLegacyPeer(name: String) = onOutdatedPeer(name)

        override fun peerFor(deviceId: String): RemotePeer? =
            relay.remotes.firstOrNull { it.id == deviceId }?.let { RemotePeer(it.id, it.name, it.scope == SCOPE_CONTROL) }

        override fun onAdmitted(peer: RemotePeer) {
            runCatching { relay.touchRemote(peer.deviceId, System.currentTimeMillis()) }
        }

        override fun isTurnedAway(address: String): Boolean = address in turnedAway
    }

    /** The hub forgets the remote [deviceId] (Paired devices ▸ Forget, spec
     *  §2.10): out of the live pin set first, then the store; its live
     *  sessions end with an authenticated `CLOSE(REVOKED)`. */
    fun forgetRemote(deviceId: String) {
        relay.remotes.firstOrNull { it.id == deviceId }?.let { pin ->
            runCatching { livePins.remove(RelayKeyStore.publicKeyOf(pin.public)) }
        }
        runCatching { relay.unpinRemote(deviceId) }
        relayHub?.revoke(deviceId)
        refreshPins()
        appendLog("Forgot remote $deviceId")
    }

    // ── Pairing, remote side (spec §2.4, §6.1) ─────────────────────────────

    /**
     * Pair with the hub at [host]:[port] ([label] names it until its
     * ServerHello does): the user's tap ("Pair again", or a picked hub this
     * device hasn't pinned). Up to three automatic attempts; on success the hub
     * pin is stored and the remote role starts with a resume. Never runs
     * unattended (spec Appendix B).
     */
    fun pairWith(host: String, port: Int, label: String) {
        val keys = relayKeys
        if (keys == null) {
            notify(KEYS_UNAVAILABLE_COPY)
            return
        }
        dialer?.cancel()
        dialerJob?.cancel()
        // The saved hub at this address, if any: pairing with a different key
        // there means its key changed (a reset or reinstall, spec §6.3), or
        // it's another hub at that address now (DHCP). Its pin stays, a dead
        // entry the user can forget (spec §2.10); the new hub becomes the
        // saved one.
        val previous = savedHubPin()?.takeIf { sameAddress(it.host, host) }
        var keyChanged = false
        val d = PairingDialer(
            url = "ws://$host:$port${LanRelayServer.PATH}",
            hubLabel = label.ifBlank { host },
            keys = keys,
            myName = deviceLabel(),
            scope = scope,
            storePin = { hubId, hubName, hubKey ->
                runCatching {
                    relay.pinHub(RelayHubPin(hubId, RelayKeyStore.b64(hubKey), hubName, host, port, System.currentTimeMillis()))
                    relay.setSavedHub(hubId)
                    if (previous != null && previous.id != hubId) keyChanged = true
                    if (relay.needsRepair) relay.clearUpgradeFlags()
                    refreshPins()
                }.isSuccess
            },
        )
        dialer = d
        appendLog("Pairing with $label at $host:$port")
        dialerJob = scope.launch {
            d.state.collect { st ->
                _state.update { it.copy(pairing = st) }
                if (st is PairingState.Paired) {
                    appendLog("Paired with ${st.hubName}${if (keyChanged) " (its key changed)" else ""}")
                    notify(if (keyChanged) hubKeyChanged(st.hubName) else pairedWith(st.hubName))
                    _state.update { it.copy(pairing = PairingState.Idle) }
                    resetRefind()
                    applyMode("secondary", host, port, trust = MirrorTrust.USER_PICK)
                }
            }
        }
        d.start()
    }

    /**
     * Pair again with the saved target: the remote's "Pair again" after the
     * upgrade (§6.4) or once its hub couldn't be verified (§6.3). A user tap
     * only; nothing calls it on its own.
     */
    fun pairAgain() {
        val (host, port) = savedTarget ?: persistedTarget ?: savedHubPin()?.let { it.host to it.port } ?: run {
            notify("Choose the hub under Other devices to pair with it.")
            return
        }
        pairWith(host, port, savedHubPin()?.name ?: _state.value.hubName.ifBlank { host })
    }

    /** Cancel the remote's pairing (the code sheet's Cancel). */
    fun cancelRemotePairing() {
        dialer?.cancel()
        dialer = null
        dialerJob?.cancel()
        dialerJob = null
        _state.update { it.copy(pairing = PairingState.Idle) }
    }

    /** The code sheet's Try again: three new attempts with the same hub. */
    fun retryRemotePairing() {
        dialer?.start()
    }

        /** Dismiss the code sheet after an outcome (Paired, Failed, Refused). */
    fun dismissPairing() {
        if (_state.value.pairing !is PairingState.Waiting && _state.value.pairing !is PairingState.ShowCode) {
            _state.update { it.copy(pairing = PairingState.Idle) }
        }
    }

    /** This remote forgets the hub [hubId] (Paired hubs ▸ Forget, spec §2.10):
     *  the pin goes; if it was the saved target, the remote becomes normal. */
    fun forgetHub(hubId: String) {
        val wasSaved = relay.savedHub == hubId
        runCatching { relay.unpinHub(hubId) }
        refreshPins()
        appendLog("Forgot hub $hubId on this device")
        if (wasSaved && _state.value.role == "secondary") switchToNormal()
    }

    // ── Keys: reset, notices, erase ─────────────────────────────────────────

    /**
     * Reset pairing key (Advanced ▸ Paired devices, spec §2.10): a new key,
     * every pin deleted in both directions, live sessions revoked, and a
     * remote becomes normal. Also the way out of a store that stays
     * unreadable ([State.relayKeysUnavailable], SR-3a P2-1): the old file moves
     * to `relay-lost/` and a new key starts. An explicit user action only.
     */
    fun resetPairingKey() {
        val fresh = relay.resetKey(fromUnavailable = relayKeys == null)
        if (fresh == null) {
            notify(KEYS_UNAVAILABLE_COPY)
            return
        }
        relayKeys = fresh
        for (pin in _state.value.pairedRemotes) runCatching { livePins.remove(RelayKeyStore.publicKeyOf(pin.public)) }
        relayHub?.let { hub -> hub.remotes().forEach { hub.revoke(it.clientId) } }
        _state.update { it.copy(relayKeysUnavailable = false) }
        refreshPins()
        appendLog("Reset this device's pairing key: every pairing was cleared")
        when (_state.value.role) {
            "secondary" -> switchToNormal()
            // The relay restarts with the new key (and starts at all after an unreadable store).
            "primary" -> applyMode("primary", "", 0)
            else -> refreshAdvertisement()
        }
    }

    /** The one-time key-reset notice was shown. */
    fun dismissKeyResetNotice() {
        runCatching { relay.clearKeyResetNotice() }
        refreshPins()
    }

    /** The one-time upgrade banner was shown (hub) or "Pair again" was dismissed (remote). */
    fun dismissUpgradeNotice() {
        runCatching { relay.clearUpgradeFlags() }
        refreshPins()
    }

    /**
     * Erase all data: the relay key store with its copies, `relay-lost/`, and
     * the v3 `proxy-pairing.json` copies (SR-3a). This device starts over as a
     * fresh install would: a new key and no pins (and the live sessions end).
     */
    fun eraseRelayData() {
        SafeFile.erase(File(app.filesDir, RelayUpgradeMigration.V3_PAIRING_FILE))
        SafeFile.erase(File(app.filesDir, RelayUpgradeMigration.V3_DEVICE_ID_FILE))
        File(app.filesDir, RelayKeyStore.LOST_DIR).deleteRecursively()
        SafeFile.erase(File(app.filesDir, RelayKeyStore.FILE_NAME))
        if (relayKeys != null) {
            relay.resetKey(fromUnavailable = false)?.let { relayKeys = it }
            for (pin in _state.value.pairedRemotes) runCatching { livePins.remove(RelayKeyStore.publicKeyOf(pin.public)) }
            relayHub?.let { hub -> hub.remotes().forEach { hub.revoke(it.clientId) } }
            relay.clearKeyResetNotice()
        }
        refreshPins()
    }

    /**
     * A remote on an older protocol version got the legacy denial: tell the
     * hub user (spec §6.3). The name comes from an unauthenticated hello, so
     * the notice and the log line show at most once a minute in all, whatever
     * the names (a LAN device sending hellos with random names gets one
     * notice a minute and no growing state), and the name is sanitized.
     */
    private fun onOutdatedPeer(clientName: String) {
        val name = coffee.crema.ble.proxy.sanitizePeerName(clientName)
        val now = outdatedNoticeClock()
        val more = synchronized(outdatedNoticeLock) {
            if (outdatedNoticeAtMs != 0L && now - outdatedNoticeAtMs < OUTDATED_NOTICE_COOLDOWN_MS) {
                outdatedNoticesHeld++
                return
            }
            outdatedNoticeAtMs = now
            outdatedNoticesHeld.also { outdatedNoticesHeld = 0 }
        }
        appendLog("Refused $name: older Crema version" + if (more > 0) " ($more more refused in the last minute)" else "")
        notify(olderCrema(name))
    }

    /** Test seam: a legacy hello from [clientName], as the acceptor reports it. */
    @androidx.annotation.VisibleForTesting
    internal fun legacyPeerForTest(clientName: String) = onOutdatedPeer(clientName)

    // ── Mode construction ─────────────────────────────────────────────────────

    /**
     * The role this start runs: the persisted one, except a remote whose key was
     * just reset (a definitive loss cleared every pin, spec §5.1 step 3): it
     * becomes normal, persisted once the prefs have loaded.
     */
    private fun startRole(): String {
        val persisted = readProxyConfigSync().role
        if (persisted == "secondary" && (keyLoad as? RelayKeyLoad.Ready)?.keyReset == true) {
            appendLog("Relay: the pairing key was reset — this device is no longer a remote")
            _state.update { it.copy(role = "normal") }
            scope.launch {
                prefsReady()
                onModeApplied("normal", "", 0)
            }
            return "normal"
        }
        return persisted
    }

    private fun startPersistedRole(role: String): BleTransport {
        val cfg = readProxyConfigSync()
        // A secondary's core mirrors the DE1 but must never drive it — make it a
        // read-only observer from the start (preserved across session resets).
        setCoreReadOnly(role == "secondary")
        return when (role) {
            "secondary" -> {
                // No address saved yet (the app stopped before the picked
                // hub's Welcome): the saved hub's last welcomed address.
                val (host, port) = persistedTarget ?: savedHubPin()?.let { it.host to it.port } ?: (cfg.host to cfg.port)
                startSecondaryMode(host, port, MirrorTrust.UNATTENDED, refind = null)
            }
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
        val hub = RelayHub(
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
            // Built on Main (the command lane), like every push (§6.1).
            configSource = { withContext(commandLane) { snapshotConfig() } },
            // Keep the Remotes list live as remotes come and go.
            onClientsChanged = { refreshRemotes() },
        )
        relayHub = hub
        val tapping = TappingBleTransport(real, hub, scope)
        tappingRef = tapping
        if (relayKeys == null) {
            // Spec §6.3: the keys couldn't be read this start, so the relay
            // doesn't listen; the DE1 still works on this device.
            appendLog("LAN relay not started: pairing keys couldn't be read (restart Crema to try again)")
            return tapping
        }
        val secure = SecureRelayAcceptor(hub, hubTrust)
        acceptor = secure
        val wantedPort = savedRelayPort()
        val server = LanRelayServer(handler = { ws, address -> secure.accept(ws, address) }, requestedPort = wantedPort)
        relayServer = server
        scope.launch {
            runCatching {
                // Not before the prefs load: a remote that dials a restarting
                // hub meets a closed port and redials.
                prefsReady()
                relayPort = server.start()
                appendLog("LAN relay listening on :$relayPort")
                if (relayPort != wantedPort) {
                    runCatching { SafeFile.write(relayPortFile, relayPort.toString(), keepBackup = false) }
                }
                refreshAdvertisement()
            }.onFailure { appendLog("LAN relay failed to start: ${it.message}") }
        }
        return tapping
    }

    /** SECONDARY: a self-connecting ProxyTransport to a primary's relay, resuming
     *  with the hub pinned at pairing (spec §2.6). With no usable pin (never
     *  paired, the v3 upgrade, the keys unreadable) it doesn't dial: the role
     *  stays, and the status says to pair again or restart. */
    private fun startSecondaryMode(
        host: String,
        port: Int,
        trust: MirrorTrust,
        refind: RefindSwitch?,
        pick: RelayHubPin? = null,
    ): BleTransport {
        val url = "ws://$host:$port${LanRelayServer.PATH}"
        appendLog("Multi-device: SECONDARY → $url")
        val hub = pick ?: savedHubPin()
        val remote = RemoteSession(host, port, trust, attempt = refind?.attempt == true, hub = hub)
        session = remote
        // A picked hub isn't the saved target before its Welcome (not even
        // when there was none): a refused pick goes back to the last welcomed
        // one, and the re-find never searches the picked host.
        if (!remote.attempt && pick == null) savedTarget = remote.target
        refindQueued = false
        // A child scope for everything this mode launches (the link-state + roster
        // collectors AND the ProxyTransport's own init collectors), so applyMode can
        // cancel them ALL on the next switch instead of leaking ~4 coroutines + a dead
        // ProxyTransport/link per secondary→x→secondary cycle (issue 10).
        val modeScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        secondaryScope = modeScope
        val keys = relayKeys
        val hubKey = hub?.let { runCatching { RelayKeyStore.publicKeyOf(it.public) }.getOrNull() }
        if (keys == null || hubKey == null || relay.needsRepair) {
            appendLog(
                when {
                    keys == null -> "Not dialing $host:$port: pairing keys couldn't be read"
                    else -> "Not dialing $host:$port: this device has to pair with the hub again"
                },
            )
            _state.update { it.copy(mirrorReconnecting = true, remoteConnecting = false) }
            return ProxyTransport(SilentLink, modeScope)
        }
        val link = ReconnectingClientLink(url, modeScope, keys, hubKey)
        proxyLink = link
        // A re-find switch keeps the remote's "Reconnecting" wording.
        if (refind == null) _state.update { it.copy(remoteConnecting = true) }
        // Reflect the link into the UI so a dropped hub shows "Reconnecting…",
        // not a frozen "Connected through" (issue 03). The link only counts as
        // up once the hub has welcomed this session (onWelcome below).
        // The re-find's clock runs while the session isn't welcomed: from
        // the start, and again from each drop. An open socket doesn't stop
        // it, so a silent endpoint can't hold the re-find.
        var stallTimer: Job? = null
        var live = false
        fun armStallTimer() {
            stallTimer?.cancel()
            remote.stalled = false
            stallTimer = modeScope.launch {
                delay(refindDelayMs)
                remote.stalled = true
                evaluateRefind()
            }
        }
        armStallTimer()
        modeScope.launch {
            link.state.collect { s ->
                if (s == LinkState.CONNECTING) {
                    _state.update { it.copy(mirrorReconnecting = true) }
                    if (live) {
                        live = false
                        armStallTimer()
                    }
                }
            }
        }
        val proxy = ProxyTransport(
            link, modeScope,
            // Snap this mirror's config to the primary's on every attach (T2).
            onConfig = { applyConfig(it) },
            // Reflect the granted scope: view-only remotes can't control. The
            // hub's name labels the status line from now on ("Connected
            // through" only now, on Welcome, spec §2.6).
            onWelcome = { scope, hubName ->
                onWelcomed(hubName)
                _state.update { it.copy(mirrorViewOnly = scope != SCOPE_CONTROL, hubName = hubName, mirrorReconnecting = false, remoteConnecting = false) }
                live = true
                stallTimer?.cancel()
                remote.stalled = false
                onRemoteWelcomed(remote)
                remote.hub?.let { pin -> runCatching { relay.touchHub(pin.id, System.currentTimeMillis()) } }
            },
            // A refusal: only an authenticated one changes the role (§2.9).
            onRefused = { reason, authenticated, terminal -> handleSessionRefused(remote, reason, authenticated, terminal) },
            // "Who's driving" (issue 11): another mirror drove the machine → toast.
            onEvent = { text -> notify(text) },
        )
        // The VM's commands now go to this primary.
        relayMachine = RelayMachine(
            send = { method, args -> proxy.sendControl(method, args) },
            showConfig = { showPending(it) },
            machineStateName = machineStateName,
            scope = scope,
            notify = notify,
            log = appendLog,
            viewOnly = { _state.value.mirrorViewOnly },
            hubName = { _state.value.hubName.ifBlank { host } },
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
     *  DE1 directly (Stop, Connect directly, and a hub's refusal). */
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

    /**
     * Mirror the DE1 from the hub at [host]:[port] — the picker's "Connect
     * through" or Manual hub (spec §6.1). When the picked entry's `kid` matches
     * a pinned hub (or a manual address matches a pinned hub's host) the remote
     * resumes with that pin's key; otherwise it pairs first ([pairWith]). The
     * `kid` and the address are unauthenticated: nothing is saved here. The
     * pin's address, the saved hub and the prefs follow only the session's
     * Welcome ([onRemoteWelcomed]), so an advertiser copying a pinned hub's
     * `kid` changes nothing stored.
     */
    fun switchToSecondary(host: String, port: Int) {
        resetRefind()
        val peer = _state.value.peers.firstOrNull { sameAddress(it.host, host) && it.port == port }
        val pinned = relay.hubs.firstOrNull { pin ->
            val kid = peer?.kid
            if (kid != null) pin.id.startsWith(kid) else sameAddress(pin.host, host)
        }
        if (pinned == null || relayKeys == null) {
            pairWith(host, port, peer?.name ?: host)
            return
        }
        applyMode("secondary", host, port, trust = MirrorTrust.USER_PICK, hub = pinned)
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
        hub: RelayHubPin? = null,
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
                // it: a user's switch (or a refusal) queued ahead of it wins.
                if (refind != null && (refind.from !== session || _state.value.role != "secondary")) {
                    appendLog("Re-find: a newer switch ran first — skipping")
                    if (refind.from === session) refindQueued = false
                    return@withLock
                }
                // Again under the lock: a switch queued behind another one may
                // start after a flash has taken the DE1.
                if (!refusedForFirmware(role)) applyModeLocked(role, host, port, reconnect, trust, refind, hub)
                else if (refind != null) refindQueued = false
            }
        }
    }

    /**
     * Switch to [role] (`"normal"` or `"primary"`) only if no other switch ran
     * since the caller saw [epoch], running [before] first under the same
     * mode lock (spec §7.5 step 4): the remote → standalone commit adopts and
     * saves there, so nothing can interleave. Stale (or refused during a
     * firmware flash): neither [before] nor the switch runs, and [onStale] is
     * told.
     */
    fun applyModeIfEpoch(role: String, epoch: Long, before: suspend () -> Unit = {}, onStale: () -> Unit = {}) {
        resetRefind()
        scope.launch {
            modeMutex.withLock {
                if (modeEpoch != epoch) {
                    appendLog("Mode switch to $role skipped: another switch ran first")
                    onStale()
                    return@withLock
                }
                if (refusedForFirmware(role)) {
                    onStale()
                    return@withLock
                }
                before()
                applyModeLocked(role, "", 0, reconnect = true, trust = MirrorTrust.UNATTENDED)
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
        hub: RelayHubPin? = null,
    ) {
        modeSwitchProbe?.invoke(role)
        // A re-find switch doesn't run while the re-find is held (the
        // leave-remote prompt is open): it is dropped, and the release
        // re-checks. It never ran, so the mode epoch stays.
        if (refind != null && refindHeld) {
            appendLog("Re-find: held — dropping a queued switch")
            refindQueued = false
            return
        }
        modeEpoch++
        // Off the remote role nothing holds the re-find.
        if (role != "secondary") refindHeld = false
        closePairingWindow()
        // Not reconnecting (Hub mode off): a full user disconnect, which also
        // drops a DE1 scan still waiting for the machine, so nothing re-grabs it.
        if (!reconnect) disconnect()
        disconnectDevices()
        delay(150)
        // Hosting stops: every admitted remote gets an authenticated
        // CLOSE(HUB_STOPPED) (spec §2.9) before the server drops the sockets.
        acceptor?.closeAll(); acceptor = null
        relayServer?.stop(); relayServer = null; relayHub = null
        // Cancel the previous SECONDARY mode's collectors + ProxyTransport before
        // releasing the link, so nothing leaks across the switch (issue 10).
        secondaryScope?.cancel(); secondaryScope = null
        relayMachine = null
        proxyLink?.dispose(); proxyLink = null
        session = null
        onRoleStarting(role, refind != null)
        switchable.setDelegate(
            when (role) {
                "secondary" -> startSecondaryMode(host, port, trust, refind, hub)
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
                remoteConnecting = role == "secondary" && it.remoteConnecting,
            )
        }
        // The VM mirrors role/host/port into MainUiState (with its sticky
        // rules), drops the mirror overlay, and persists the prefs. Not for a
        // re-find switch: the role and the saved target stay. A remote's new
        // address is saved only once it is welcomed (onTargetMoved): until
        // then the prefs keep the last welcomed one.
        if (refind == null) {
            if (role == "secondary") {
                // Nothing welcomed yet: the dialed hub's pin, whose address a
                // pairing or an earlier Welcome proved.
                val (savedHost, savedPort) = persistedTarget ?: (hub ?: savedHubPin())?.let { it.host to it.port } ?: ("" to 0)
                onModeApplied(role, savedHost, savedPort)
            } else {
                onModeApplied(role, host, port)
            }
        }
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
        val port = if (role == "primary" && relayServer != null) relayPort else 0
        // The short relay id only while hosting (spec §4.8): advisory, for resume-vs-pair.
        val kid = if (port > 0) relayKeys?.shortId() else null
        peerDiscovery.advertise(deviceLabel(), role, holdsDe1, port, kid)
    }

    /** Push the primary's device roster to attached mirrors — called when the
     *  DE1/scale connection set changes (issue 04). No-op off-primary. */
    fun pushRoster() {
        relayHub?.pushRoster()
    }

    /** Push the primary's session config to attached mirrors — called when the
     *  single-owner config changes (T2). No-op off-primary. */
    fun pushConfig() {
        relayHub?.pushConfig(snapshotConfig())
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

/** How long a remote's session goes unwelcomed before the re-find looks on
 *  NSD, and how long a re-find attempt has to be welcomed. */
internal const val REFIND_DELAY_MS = 5_000L

/** The wait after each unattended refusal or unanswered attempt: the first, second, and every later one. */
internal val REFIND_BACKOFF_MS = listOf(5_000L, 15_000L, 60_000L)

/** Unattended refusals and unanswered attempts in a row before the re-find gives up. */
internal const val REFIND_MAX_REFUSALS = 3

/** Whether two host strings name the same address: NSD's resolved address
 *  against a saved target, ignoring case and IPv6 brackets. A saved name
 *  (`kitchen.local`) never matches an address, so the re-find doesn't run for
 *  it. */
internal fun sameAddress(a: String, b: String): Boolean {
    fun norm(h: String) = h.trim().removePrefix("[").removeSuffix("]").lowercase()
    return norm(a).isNotEmpty() && norm(a) == norm(b)
}

/** How long the host's pairing window stays open. */
internal const val PAIRING_WINDOW_S = 120

private const val OUTDATED_NOTICE_COOLDOWN_MS = 60_000L

/** How long the hub's code dialog waits for a remote's next attempt after a wrong code. */
private const val WRONG_CODE_GRACE_MS = 15_000L

private val gateJson = Json { ignoreUnknownKeys = true }

/** A [RelayFfiException]'s kind, never its detail. */
private fun relayKind(e: RelayFfiException): String = (e as? RelayFfiException.Failed)?.kind ?: e.javaClass.simpleName

/** A link that never connects: a remote role with nothing it may dial (no pin, or no keys). */
private object SilentLink : FrameLink {
    override suspend fun send(frame: coffee.crema.ble.proxy.Frame) = error("this remote doesn't dial")
    override fun incoming(): Flow<coffee.crema.ble.proxy.Frame> = flow { awaitCancellation() }
    override fun events(): Flow<coffee.crema.ble.proxy.LinkEvent> = flow { awaitCancellation() }
    override suspend fun close() {}
    override suspend fun sendOn(epoch: Long, frame: coffee.crema.ble.proxy.Frame): Boolean = false
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
