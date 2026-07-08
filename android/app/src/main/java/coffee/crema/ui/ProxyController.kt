package coffee.crema.ui

import android.app.Application
import android.os.Build
import coffee.crema.ble.BleTransport
import coffee.crema.ble.NordicBleTransport
import coffee.crema.ble.proxy.DeviceInfo
import coffee.crema.ble.proxy.HandoffTarget
import coffee.crema.ble.proxy.LanRelayServer
import coffee.crema.ble.proxy.LinkState
import coffee.crema.ble.proxy.PairingDecision
import coffee.crema.ble.proxy.Peer
import coffee.crema.ble.proxy.PeerDiscovery
import coffee.crema.ble.proxy.ProxyTransport
import coffee.crema.ble.proxy.ReconnectingClientLink
import coffee.crema.ble.proxy.RelayHub
import coffee.crema.ble.proxy.ReplayBleTransport
import coffee.crema.ble.proxy.SwitchableBleTransport
import coffee.crema.ble.proxy.TappingBleTransport
import coffee.crema.ble.De1Uuids
import coffee.crema.core.MachineRequest
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.PairedDevice
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** This device's human label — the name peers see in the multi-device picker
 *  and the label stamped into backup envelopes. */
internal fun deviceLabel(): String = Build.MODEL ?: "crema"

/** A pending host-side "Allow this device?" pairing prompt (issue 02) — a secondary
 *  with an unknown [clientId] is connecting; the UI shows it and the user's choice
 *  resolves the held handshake. */
data class PairingPrompt(val clientId: String, val clientName: String)

/** The host's answer to a [PairingPrompt] (issue 02). */
enum class PairingChoice { DENY, MIRROR_ONLY, ALLOW_CONTROL }

/*
 * The multi-device proxy controller — mode switching (normal / primary /
 * secondary), the LAN relay + mirror link lifecycle, peer discovery, TOFU
 * pairing, and handoff, extracted from MainViewModel (review #43).
 *
 * Follows the VisualizerSync / DriveSync pattern: a self-contained controller
 * with its own [state] flow; the VM constructs it, wires callbacks, and mirrors
 * [State] into MainUiState. It OWNS the app-wide switchable BLE transport — the
 * VM builds the scanner and both BLE managers on [transport], so this
 * controller must be constructed BEFORE them (it is what gets swapped on a
 * mode switch).
 *
 * Whole-app concerns stay VM-side, reached through constructor callbacks: the
 * relayed-control dispatch ([dispatchControl]), the session-config snapshot
 * ([snapshotConfig] / [applyConfig]), the paired-device list (persisted with
 * the app prefs), and the mirrored-profile promotion on handoff.
 */
class ProxyController(
    private val app: Application,
    private val json: Json,
    private val scope: CoroutineScope,
    /** `bridge.setReadOnly` — a secondary's core mirrors but must never drive.
     *  Invoked SYNCHRONOUSLY during construction (buildInitialDelegate). */
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
     *  unknown — gates handoff (idle-only) and words the "who's driving" notice. */
    private val machineStateName: () -> String?,
    /** Run a relayed control verb on the VM's command router; throws on an
     *  unknown method (becomes a `ControlErr` back to the secondary). */
    private val dispatchControl: (method: String, args: String) -> Unit,
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
    /** A pull-handoff was granted: promote the mirrored custom profile into the
     *  local library before becoming the primary (issue 05). */
    private val onHandoffGranted: () -> Unit,
    /** A mode switch landed: mirror role/host/port into MainUiState (sticky
     *  rules), drop the mirror overlay fields, and persist the prefs. */
    private val onModeApplied: (role: String, host: String, port: Int) -> Unit,
) {

    /** The proxy slice of the UI snapshot — the VM mirrors this into MainUiState. */
    data class State(
        /** The LIVE role (`"normal" | "primary" | "secondary"`) — seeded from the
         *  persisted prefs at construction, updated by [applyMode]. */
        val role: String = "normal",
        /** LAN peers discovered over NSD (the multi-device picker). */
        val peers: List<Peer> = emptyList(),
        /** True while a secondary's link to its primary is (re)dialing (issue 03). */
        val mirrorReconnecting: Boolean = false,
        /** True when the host admitted this mirror view-only (issue 02). */
        val mirrorViewOnly: Boolean = false,
        /** A host-side TOFU prompt awaiting the user's choice (issue 02). */
        val pendingPairing: PairingPrompt? = null,
        /** Secondaries mirroring this primary — the push-handoff targets (issue 07). */
        val mirrorClients: List<HandoffTarget> = emptyList(),
        /** A pushed handoff offer awaiting accept/decline, by host name (issue 07). */
        val pendingHandoffOffer: String? = null,
    )

    private data class ProxyConfig(val role: String, val host: String, val port: Int, val replayPrimary: Boolean = false)

    // Role-specific resources. In NORMAL, [nordicTransport] holds the real
    // transport; [relayHub]/[relayServer] (PRIMARY) and [proxyLink] (SECONDARY)
    // are the role-specific extras, torn down on a mode switch and in [close].
    // ALL of these are declared BEFORE [switchable]: [buildInitialDelegate]
    // assigns them during that field's initializer, and a later declaration
    // would be zeroed by its own init afterwards (the field-init-order trap —
    // review #36's launch-dead APK).
    private var nordicTransport: NordicBleTransport? = null
    private var relayHub: RelayHub? = null
    private var relayServer: LanRelayServer? = null
    private var proxyLink: ReconnectingClientLink? = null

    /** Child scope for the SECONDARY mode's collectors + its [ProxyTransport],
     *  cancelled on every mode switch and in [close] so they don't leak across
     *  secondary→x→secondary cycles (issue 10). */
    private var secondaryScope: CoroutineScope? = null

    /** The primary relay's bound port (0 when not hosting) — advertised so a
     *  secondary can dial it. */
    private var relayPort: Int = 0

    /** Stable per-install id (persisted in a tiny file — issue 14) used to advertise
     *  + self-filter in NSD discovery AND as the secondary's `clientId` for the
     *  host's TOFU pairing (issue 02). */
    val deviceId: String = run {
        val f = File(app.filesDir, "deviceId")
        runCatching { f.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null } }.getOrNull()
            ?: UUID.randomUUID().toString().also { runCatching { f.writeText(it) } }
    }

    /** Construction-time proxy-config snapshot — read `prefs.json` ONCE
     *  (review #36: role/host/port are restart-to-apply anyway). A PLAIN val,
     *  not a lazy: construction order stays linear within this class body
     *  (the lazy-below-use variant shipped a launch-dead APK — 4eeb92f2). */
    private val proxyConfigCache: ProxyConfig = readProxyConfigUncached()

    private val _state = MutableStateFlow(State(role = proxyConfigCache.role))
    val state: StateFlow<State> = _state.asStateFlow()

    // Multi-device pairing (TOFU — issue 02). The relay doesn't auto-accept: a
    // connecting secondary is authorized in [authorizePeer]. One host-side prompt
    // at a time; concurrent unknown peers queue on the mutex.
    private val pairingMutex = Mutex()
    private var pairingDeferred: CompletableDeferred<PairingChoice>? = null

    /** Machine states it's safe to hand off the radio in. An **allowlist** (issue
     *  09): everything else — dispensing, cleaning, calibrating, erroring, booting —
     *  refuses, and so does an unknown/`null` state, so we never grant during a
     *  moment we can't positively call idle. `Idle` covers warm-up/ready. */
    private val handoffIdleStates = setOf("Sleep", "GoingToSleep", "Idle", "SchedIdle")

    // The managers + scanner sit on this facade so the transport can be swapped at
    // runtime — M2 mode switches (Mirror/Hand-off) with NO app restart. The startup
    // delegate is the persisted role (see [buildInitialDelegate]); [applyMode] swaps it.
    private val switchable = SwitchableBleTransport(buildInitialDelegate())

    /** The app-wide BLE transport facade — the VM builds the scanner and both
     *  BLE managers on this. */
    val transport: BleTransport = switchable

    /** LAN peer discovery (NSD) for the multi-device picker (M2) — advertises this
     *  instance and browses for peers, exposed as [State.peers]. */
    private val peerDiscovery = PeerDiscovery(app, scope, deviceId)

    /**
     * Post-construction start, called from the VM's `init` (after every VM field
     * — the recorder and both managers — exists): arm the recorder gate, start
     * NSD discovery, and advertise this instance.
     */
    fun start() {
        // A secondary (read-only mirror) doesn't record the primary's shot (issue
        // 14). Set here, not in buildInitialDelegate — that runs during the
        // [switchable] field init, before the recorder exists.
        setRecorderEnabled(readProxyConfigSync().role != "secondary")
        scope.launch { peerDiscovery.peers.collect { p -> _state.update { it.copy(peers = p) } } }
        peerDiscovery.startDiscovery()
        refreshAdvertisement()
    }

    /** Tear down whatever the current role holds. Called from the VM's
     *  `onCleared`, after the managers disconnected. Closing the Nordic
     *  environment unregisters its Bluetooth receiver and cancels its scope.
     *  (Only one of these is non-null per proxy role; NORMAL has just the Nordic.) */
    fun close() {
        nordicTransport?.close()
        relayServer?.stop()
        secondaryScope?.cancel()
        proxyLink?.dispose()
        peerDiscovery.close()
    }

    // ── Relaying a secondary's user intent to the primary ────────────────────

    /**
     * On a secondary (read-only mirror), relay user-intent control [method] to
     * the primary instead of running it locally — the secondary's core can't
     * drive the machine, so a tap on its Brew controls crosses the LAN to the
     * primary's command router ([coffee.crema.ble.proxy.Frame.Control]). Returns
     * `true` when relayed (the caller must `return`, skipping the local path);
     * `false` on a normal/primary device, where the caller runs the action.
     */
    fun relayIfSecondary(method: String, args: String = ""): Boolean {
        if (_state.value.role != "secondary") return false
        val proxy = switchable.delegate as? ProxyTransport ?: return false
        scope.launch {
            proxy.control(method, args).onFailure {
                appendLog("Relay $method failed: ${it.message}")
                notify(relayFailureMessage(it, applied = false))
            }
        }
        return true
    }

    /** User-facing copy for a failed relay: an authorization refusal (issue 02
     *  view-only peer) reads differently from a dropped link. */
    private fun relayFailureMessage(error: Throwable, applied: Boolean): String =
        if (error.message?.contains("not authorized") == true) {
            "View-only — the host hasn't allowed this device to control the machine"
        } else if (applied) {
            "Couldn't reach the machine — change reverted"
        } else {
            "Couldn't reach the machine — change not applied"
        }

    /**
     * Relay a **config** verb from a secondary, optimistic-apply style (issue 06).
     * The caller has already updated its UI locally (snappy — no round-trip lag);
     * this relays and, on failure, runs [revert] + surfaces it, so a dropped link
     * never silently eats the change. On success the primary's authoritative
     * `Config` push reconciles (usually a no-op). Returns true when handled as a
     * secondary (the caller must `return`, skipping its persist + machine write).
     *
     * Distinct from [relayIfSecondary], which stays authority-first (no local
     * apply) for machine-control verbs (start/stop/tare) where optimism is unsafe.
     */
    fun relayConfigOptimistic(method: String, args: String, revert: () -> Unit): Boolean {
        if (_state.value.role != "secondary") return false
        val proxy = switchable.delegate as? ProxyTransport ?: return false
        scope.launch {
            proxy.control(method, args).onFailure {
                appendLog("Relay $method failed: ${it.message}")
                notify(relayFailureMessage(it, applied = true))
                revert()
            }
        }
        return true
    }

    /** Run a secondary's relayed control intent on this (primary) device via the
     *  VM's [dispatchControl] router — the same verbs the primary's own UI calls.
     *  Wired into the [RelayHub] by [startPrimaryMode]; `startShot` runs the
     *  primary's full shot orchestration, so that complexity never crosses the
     *  wire. */
    private fun handleRelayedControl(
        method: String,
        args: String,
        originId: String? = null,
        originName: String? = null,
    ): Result<Unit> = runCatching {
        // "Who's driving" notice (issue 11, loose model): compute BEFORE dispatch so
        // an IDLE request reads as stop-vs-wake against the PRE-action machine state.
        // Only a secondary-initiated control carries an originId.
        val phrase = if (originId != null) controlPhrase(method, args) else null
        dispatchControl(method, args)
        // Dispatch succeeded (a throw above is caught by runCatching, skipping this):
        // fan the notice to the OTHER mirrors + surface it on this primary, so
        // everyone but the originator learns who drove the machine (issue 11).
        if (phrase != null) {
            val text = "${originName ?: "A mirror"} $phrase"
            relayHub?.broadcastEvent(text, exceptClientId = originId)
            notify(text)
        }
    }

    /** A short human phrase for a relayed machine-control verb (issue 11) — the
     *  predicate of "<who> …". Returns null for verbs not worth announcing (config
     *  edits, unknowns). IDLE is read against the current state: stop while running,
     *  otherwise wake. */
    private fun controlPhrase(method: String, args: String): String? = when (method) {
        "startShot" -> "started a shot"
        "tareScale" -> "tared the scale"
        "machineState" -> when (args) {
            MachineRequest.ESPRESSO.name -> "started a shot"
            MachineRequest.STEAM.name, MachineRequest.STEAM_RINSE.name -> "started steam"
            MachineRequest.HOT_WATER.name -> "started hot water"
            MachineRequest.FLUSH.name -> "started a flush"
            MachineRequest.SLEEP.name -> "put the machine to sleep"
            MachineRequest.IDLE.name ->
                if (machineStateName() in handoffIdleStates) "woke the machine"
                else "stopped the machine"
            else -> null
        }
        else -> null
    }

    // ── Handoff (M3) ──────────────────────────────────────────────────────────

    /** Primary side of an M3 handoff: a secondary asked for the DE1. Refuse unless
     *  the machine is positively idle; otherwise grant by stepping down to NORMAL so
     *  the taker can connect its own radio. The release is delayed a beat so this
     *  grant (a `ControlOk`) flushes to the secondary before [applyMode] tears the
     *  relay down. Throwing here becomes a `ControlErr` → the taker stays put.
     *  Reached through the VM's control router (the `"handoff"` verb). */
    internal fun grantHandoff() {
        val state = machineStateName()
        require(state in handoffIdleStates) { "machine not idle (${state ?: "unknown"}) — handoff is idle-only" }
        appendLog("Handoff granted — releasing the DE1")
        scope.launch {
            delay(400) // let this grant (ControlOk) flush before the relay tears down
            // RELEASE: step down to normal but do NOT reconnect — the taker must be
            // able to acquire the now-free DE1 (issue 01). The old path reconnected,
            // so the "released" holder immediately re-grabbed the radio and the
            // take-over could never land on real hardware.
            applyMode("normal", "", 0, reconnect = false)
            armHandoffReclaim()
        }
    }

    /** After granting a handoff we release the DE1 without reconnecting. If the
     *  taker never actually acquires it (BT off, a crash, out of range) the machine
     *  would be orphaned — so after a grace period, probe: if the DE1 came **free**
     *  the take-over didn't stick, so reclaim it and resume hosting; if it's
     *  **taken**, the handoff succeeded and we stay a plain normal device. The
     *  free/busy test is the real BLE scan, so this recovery is hardware-exercised
     *  (on a no-Bluetooth emulator the probe always finds nothing → stays normal).
     *  (issue 01 — release + reclaim-on-timeout: a failed take-over never orphans.) */
    private fun armHandoffReclaim() {
        scope.launch {
            delay(8_000L) // grace for the taker to acquire the radio
            if (_state.value.role != "normal" || de1Connected()) return@launch
            appendLog("Handoff reclaim: probing whether the DE1 came free")
            connect() // scans + binds the DE1 only if it is free
            delay(3_000L) // give the probe scan time to bind
            if (de1Connected()) {
                appendLog("Handoff reclaim: DE1 was free — resuming as primary")
                switchToPrimary()
            } else {
                appendLog("Handoff reclaim: DE1 is taken — re-mirroring the new host")
                disconnect() // cancel the pending scan
                autoMirrorDiscoveredPrimary() // don't go dark: become the taker's mirror (issue 07)
            }
        }
    }

    /** After stepping down from a handoff, don't sit blank (issue 07): if a primary
     *  holding the DE1 has appeared on NSD, mirror it — a true role-swap, not
     *  A→primary/B→dark. NSD doesn't cross the emulator NAT, so the cross-device
     *  re-mirror is hardware-validated; on the emulator [State.peers] is empty →
     *  no-op (the user can still re-mirror via the picker's manual peer). */
    private fun autoMirrorDiscoveredPrimary() {
        if (_state.value.role != "normal") return
        val host = _state.value.peers.firstOrNull { it.isMirrorSource && it.id != deviceId } ?: run {
            appendLog("Re-mirror: no DE1-holding primary discovered yet (NSD)")
            return
        }
        appendLog("Re-mirror: auto-mirroring ${host.name} (${host.host}:${host.port})")
        switchToSecondary(host.host, host.port)
    }

    /** Secondary side of an M3 handoff (the picker's "Take over"): ask the primary
     *  to release the DE1, and on grant become the host ourselves. Idle-only — the
     *  primary refuses mid-shot. NOTE: the old primary stepping back as *our*
     *  mirror needs an endpoint exchange / NSD (it doesn't know our relay), and on
     *  a no-Bluetooth emulator "becoming primary" falls to a replay; the real radio
     *  move is hardware-gated. Both are documented follow-ups. */
    fun requestHandoff() {
        scope.launch {
            val proxy = switchable.delegate as? ProxyTransport
            if (proxy == null) {
                appendLog("Handoff: not currently mirroring a primary")
                return@launch
            }
            proxy.control("handoff")
                .onSuccess {
                    appendLog("Handoff granted — acquiring the DE1")
                    notify("Taking over the machine…")
                    onHandoffGranted() // keep the mirrored custom profile (issue 05)
                    delay(600) // let the primary release first
                    switchToPrimary()
                }
                .onFailure {
                    appendLog("Handoff refused: ${it.message}")
                    notify("Can't take over — the machine is busy")
                }
        }
    }

    // ── Push handoff ("hand off TO X" — issue 07) ─────────────────────────────

    /** Refresh the list of secondaries mirroring this primary (the push targets),
     *  wired to [RelayHub.onClientsChanged]. No-op off-primary. */
    private fun refreshMirrorClients() {
        val targets = relayHub?.handoffTargets() ?: emptyList()
        _state.update { it.copy(mirrorClients = targets) }
    }

    /** Primary side: offer the machine to a specific mirroring secondary. The peer
     *  prompts and, on accept, runs its normal "Take over" back at us — so the
     *  idle-gated release + reclaim (issue 01) handles the swap. */
    fun offerHandoff(clientId: String) {
        if (relayHub?.offerHandoff(clientId) == true) {
            notify("Offered the machine — waiting for the other device")
        } else {
            appendLog("Handoff offer failed: $clientId not connected")
        }
    }

    /** Secondary side: the host pushed us an offer. Raise the accept prompt. */
    private fun onHandoffOffered(fromName: String) {
        _state.update { it.copy(pendingHandoffOffer = fromName) }
    }

    /** The user accepted a pushed offer → take the machine (the normal pull). */
    fun acceptHandoffOffer() {
        _state.update { it.copy(pendingHandoffOffer = null) }
        requestHandoff()
    }

    /** The user declined a pushed offer — a local no-op (the host keeps the DE1). */
    fun declineHandoffOffer() {
        _state.update { it.copy(pendingHandoffOffer = null) }
    }

    // ── Multi-device pairing (TOFU — issue 02) ────────────────────────────────

    /**
     * Primary-side TOFU gate wired into [RelayHub]'s `authorize`. A remembered peer
     * is admitted silently with its stored scope; an unknown one raises a host-side
     * "Allow this device?" prompt ([State.pendingPairing]) and suspends until
     * the user chooses. Accepting persists the peer (via [rememberPaired]) so
     * reconnects are silent. The mutex serializes prompts so two unknown peers
     * don't fight over the dialog.
     */
    private suspend fun authorizePeer(clientId: String, clientName: String): PairingDecision {
        pairedDevices().firstOrNull { it.id == clientId }?.let {
            return PairingDecision.Allowed(it.canControl)
        }
        return pairingMutex.withLock {
            // Re-check inside the lock: a peer approved while we queued is now known.
            val known = pairedDevices().firstOrNull { it.id == clientId }
            if (known != null) return@withLock PairingDecision.Allowed(known.canControl)
            val deferred = CompletableDeferred<PairingChoice>()
            pairingDeferred = deferred
            _state.update { it.copy(pendingPairing = PairingPrompt(clientId, clientName)) }
            val choice = deferred.await()
            pairingDeferred = null
            _state.update { it.copy(pendingPairing = null) }
            when (choice) {
                PairingChoice.DENY -> PairingDecision.Denied("not authorized")
                PairingChoice.MIRROR_ONLY -> { rememberPaired(clientId, clientName, false); PairingDecision.Allowed(false) }
                PairingChoice.ALLOW_CONTROL -> { rememberPaired(clientId, clientName, true); PairingDecision.Allowed(true) }
            }
        }
    }

    /** The Activity resolves a pending [PairingPrompt] with the user's choice. */
    fun resolvePairing(choice: PairingChoice) {
        pairingDeferred?.complete(choice)
    }

    /** Secondary side: the host declined (or revoked) this device (issue 02). Stop
     *  mirroring and tell the user, rather than silently retry-looping a denied link. */
    private fun onProxyDenied(reason: String) {
        if (_state.value.role != "secondary") return
        scope.launch {
            appendLog("Mirror denied by host: $reason")
            notify("The host hasn't allowed this device")
            switchToNormal()
        }
    }

    // ── Mode construction ─────────────────────────────────────────────────────

    private fun buildInitialDelegate(): BleTransport {
        val cfg = readProxyConfigSync()
        // A secondary's core mirrors the DE1 but must never drive it — make it a
        // read-only observer from the start (preserved across session resets).
        setCoreReadOnly(cfg.role == "secondary")
        return when (cfg.role) {
            "secondary" -> startSecondaryMode(cfg.host, cfg.port)
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
            primaryId = deviceLabel(),
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
            // Relayed user intent from a secondary → the primary's command router.
            controlHandler = { method, args, originId, originName ->
                handleRelayedControl(method, args, originId, originName)
            },
            // The single-owner session config a mirror snaps to on attach (T2).
            configSource = { snapshotConfig() },
            // TOFU gate (issue 02): prompt for an unknown peer, remember the choice.
            authorize = { id, name -> authorizePeer(id, name) },
            // Keep the "hand off to <device>" list live as mirrors come/go (issue 07).
            onClientsChanged = { refreshMirrorClients() },
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
    private fun startSecondaryMode(host: String, port: Int): BleTransport {
        val url = "ws://$host:$port${LanRelayServer.PATH}"
        appendLog("Multi-device: SECONDARY → $url")
        // A child scope for everything this mode launches (the link-state + roster
        // collectors AND the ProxyTransport's own init collectors), so applyMode can
        // cancel them ALL on the next switch instead of leaking ~4 coroutines + a dead
        // ProxyTransport/link per secondary→x→secondary cycle (issue 10).
        val modeScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        secondaryScope = modeScope
        val link = ReconnectingClientLink(url, modeScope)
        proxyLink = link
        // Reflect the link's CONNECTING/CONNECTED into the UI so a dropped primary
        // shows "Reconnecting…", not a frozen "Mirroring" (issue 03).
        modeScope.launch {
            link.state.collect { s -> _state.update { it.copy(mirrorReconnecting = s == LinkState.CONNECTING) } }
        }
        val proxy = ProxyTransport(
            // clientId = the STABLE per-install id (issue 14) so the host's TOFU
            // pairing (issue 02) remembers THIS device, not every same-model phone;
            // clientName = the human label shown in the host's prompt.
            link, modeScope, clientId = deviceId, clientName = deviceLabel(),
            // Snap this mirror's config to the primary's on every attach (T2).
            onConfig = { applyConfig(it) },
            // Reflect the granted scope (issue 02): view-only mirrors can't control.
            onWelcome = { scope -> _state.update { it.copy(mirrorViewOnly = scope == "mirror") } },
            // Host declined/revoked us → stop mirroring with a message (issue 02).
            onDenied = { reason -> onProxyDenied(reason) },
            // The host is pushing us the machine (issue 07) → prompt to accept.
            onHandoffOffer = { fromName -> onHandoffOffered(fromName) },
            // "Who's driving" (issue 11): another mirror drove the machine → toast.
            onEvent = { text -> notify(text) },
            // Re-run the attach handshake when the link redials (issue 03).
            reconnects = link.reconnects,
        )
        // Mirror the primary's SCALE (issue 04), roster-driven: attach it only once
        // the primary actually advertises one — no blind scan, so a scaleless primary
        // leaves us on "Not paired" (not a stuck "Scanning…"). connectScale's scan is
        // satisfied by the same roster, so this just times the trigger.
        modeScope.launch {
            proxy.deviceRoster.collect { devices ->
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

    /** Stop relaying/mirroring: become a standalone NORMAL device. */
    fun switchToNormal() = applyMode("normal", "", 0)

    /** Hold the DE1 and relay it to others (becomes the primary). */
    fun switchToPrimary() = applyMode("primary", "", 0)

    /** Mirror the DE1 from a primary at [host]:[port] — the picker's "Mirror from X". */
    fun switchToSecondary(host: String, port: Int) = applyMode("secondary", host, port)

    /**
     * Swap the transport at runtime: tear the managers' connections down, dispose
     * the old mode's resources, install the new delegate, persist the role (via
     * [onModeApplied]), and reconnect over it. The disconnect →
     * [SwitchableBleTransport.setDelegate] → reconnect bracket keeps any
     * notification flow from spanning the swap; the brief settle lets the
     * managers' fire-and-forget transport release run against the OLD delegate
     * before it is replaced.
     */
    private fun applyMode(role: String, host: String, port: Int, reconnect: Boolean = true) {
        scope.launch {
            disconnectDevices()
            delay(150)
            relayServer?.stop(); relayServer = null; relayHub = null
            // Cancel the previous SECONDARY mode's collectors + ProxyTransport before
            // releasing the link, so nothing leaks across the switch (issue 10).
            secondaryScope?.cancel(); secondaryScope = null
            proxyLink?.dispose(); proxyLink = null
            switchable.setDelegate(
                when (role) {
                    "secondary" -> startSecondaryMode(host, port)
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
                    // Push-handoff state (issue 07): the client list only applies while
                    // primary; a pending offer is dropped on any role change.
                    mirrorClients = if (role == "primary") it.mirrorClients else emptyList(),
                    pendingHandoffOffer = null,
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
            // A handoff release passes reconnect=false so the stepped-down holder
            // does NOT re-grab the DE1 — the taker needs it free (issue 01).
            if (reconnect) connect()
            refreshAdvertisement()
        }
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
     *  for [buildInitialDelegate] (which runs before the VM's async prefs load) —
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
