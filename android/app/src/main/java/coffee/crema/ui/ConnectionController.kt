package coffee.crema.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import coffee.crema.ble.BleScanner
import coffee.crema.ble.BleTransport
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The device-connection controller — DE1 + scale connect/disconnect verbs, the
 * manager-state collectors, the Bluetooth-adapter watcher, per-device
 * auto-connect (remembered addresses), the scale keep-alive heartbeat, and the
 * DE1 keep-awake tick — extracted from MainViewModel (review #43).
 *
 * Follows the VisualizerSync / DriveSync / ProxyController pattern: a
 * self-contained controller with its own [state] flow the VM mirrors into
 * MainUiState. The BLE managers and the shared scanner are constructed by the
 * VM (their core-output routing is a VM concern) and passed in; this
 * controller owns their connection LIFECYCLE. Whole-app reactions to
 * connection changes — the connect-time register sweep, the profile
 * upload-skip cache, the proxy roster/advertisement pushes, and the UI-slice
 * clears on disconnect — stay VM-side behind the constructor callbacks.
 */
class ConnectionController(
    private val app: Application,
    private val scope: CoroutineScope,
    /** The single-threaded core lane (review #28) — the heartbeat's bridge
     *  call must not run on Main. */
    private val coreDispatcher: CoroutineDispatcher,
    private val ble: De1BleManager,
    private val scale: ScaleBleManager,
    private val bleScanner: BleScanner,
    /** The app-wide transport facade ([ProxyController.transport]) — used for
     *  the scan-free direct connect when the adapter comes back on. */
    private val transport: BleTransport,
    /** Append to the session event log. */
    private val appendLog: (String) -> Unit,
    /** Persist the app prefs (reads the remembered addresses back from
     *  [state], so call AFTER the state update). */
    private val persistPrefs: () -> Unit,
    /** A user-initiated DE1 connect is starting: reset the session event log. */
    private val onConnectStarted: () -> Unit,
    /** A user-initiated DE1 disconnect: cancel the in-flight register sweep,
     *  drop the pending gated-start, and clear the live-telemetry /
     *  machine-identity UI slices. */
    private val onDe1SessionClosed: () -> Unit,
    /** A user-initiated scale disconnect: clear the scale UI slice
     *  (readings, capabilities, identity). */
    private val onScaleSessionClosed: () -> Unit,
    /** The DE1 just reached READY (once per connection): fire the
     *  machine read-sweep. */
    private val onDe1Ready: () -> Unit,
    /** The DE1 left READY: the machine no longer holds our profile — drop the
     *  upload-skip cache (issue 11). */
    private val onDe1Dropped: () -> Unit,
    /** A DE1/scale connection change altered the device roster this primary
     *  advertises to its mirrors (issue 04). */
    private val pushRoster: () -> Unit,
    /** The DE1-hold changed — refresh the NSD advertisement. */
    private val refreshAdvertisement: () -> Unit,
    /** The connected scale's heartbeat cadence, ms — null until the
     *  capability read lands (the loop idles at a slow poll until then). */
    private val heartbeatIntervalMs: () -> Long?,
    /** Send one scale keep-alive through the core; runs on [coreDispatcher].
     *  Throws on failure (logged here). */
    private val sendScaleHeartbeat: () -> Unit,
    /** One DE1 keep-awake tick, every 60 s — the VM gates it on the
     *  suppress-sleep pref, the screensaver and a sleeping machine, and
     *  writes UserPresent. */
    private val keepAliveTick: () -> Unit,
) {

    /** The connection slice of the UI snapshot — the VM mirrors this into
     *  MainUiState. */
    data class State(
        /** Coarse DE1 connection state (drives the status pips + gating). */
        val de1: De1BleManager.State = De1BleManager.State.IDLE,
        /** Coarse scale connection state. */
        val scale: ScaleBleManager.State = ScaleBleManager.State.IDLE,
        /** The connected DE1's Bluetooth address, null while disconnected. */
        val de1Address: String? = null,
        /** Whether the system Bluetooth adapter is ON. */
        val bluetoothOn: Boolean = true,
        /** Remembered DE1 address — non-null ⟺ its Auto-connect is ON. */
        val rememberedDe1Address: String? = null,
        /** Remembered scale address — non-null ⟺ its Auto-connect is ON. */
        val rememberedScaleAddress: String? = null,
        /** The remembered scale's advertised name (the codec re-derive key). */
        val rememberedScaleName: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The scale keep-alive loop, running only while a scale is READY. */
    private var scaleHeartbeatJob: Job? = null

    /** Receiver for the system Bluetooth on/off broadcast — registered in
     *  [start], unregistered in [close]. Reflects adapter state into [state]
     *  and, on a transition to ON, recovers any remembered device whose
     *  reconnect loop gave up while the adapter was off. */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                BluetoothAdapter.STATE_ON
            _state.update { it.copy(bluetoothOn = on) }
            if (on) onBluetoothEnabled()
        }
    }

    /**
     * Post-construction start, called from the VM's `init`: seed + subscribe
     * the Bluetooth-adapter watcher, collect the managers' coarse
     * connection-state flows, and arm the DE1 keep-awake tick.
     */
    fun start() {
        // Track the Bluetooth adapter: drive the "Bluetooth is off" UI and, when
        // it returns, re-trigger auto-connect — a BT-off drop otherwise exhausts
        // the managers' reconnect budget and never retries once the adapter is back.
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        _state.update { it.copy(bluetoothOn = adapter?.isEnabled == true) }
        ContextCompat.registerReceiver(
            app, bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Collect the managers' coarse connection-state flows so the UI
        // snapshot updates promptly when either advances — rather than only
        // when an unrelated event happens to arrive.
        scope.launch {
            var wasReady = false
            // Guarded (review #36): a throw inside a StateFlow collector
            // cancels it FOREVER — BLE-state→UI sync would silently stop.
            ble.state.collect { state ->
                runCatching {
                    _state.update { it.copy(de1 = state, de1Address = ble.connectedAddress) }
                    // A DE1 connect/disconnect changes the roster this primary
                    // advertises to its mirrors (issue 04) — re-push so a secondary
                    // that attached earlier tracks the change without reconnecting.
                    pushRoster()
                    // Fire the machine read-sweep once per connection, on the first
                    // transition into READY (services discovered + subscribed).
                    if (state == De1BleManager.State.READY && !wasReady) {
                        wasReady = true
                        onDe1Ready()
                        // Connecting auto-remembers the DE1 → its Auto-connect turns ON.
                        ble.connectedAddress?.let { addr ->
                            ble.autoReconnectEnabled = true
                            if (_state.value.rememberedDe1Address != addr) {
                                _state.update { it.copy(rememberedDe1Address = addr) }
                                persistPrefs()
                            }
                        }
                        // Hosting now holds the DE1 — refresh our NSD advertisement.
                        refreshAdvertisement()
                    } else if (state != De1BleManager.State.READY) {
                        wasReady = false
                        onDe1Dropped()
                        refreshAdvertisement()
                    }
                }.onFailure { appendLog("DE1 state handling failed: ${it.message}") }
            }
        }
        scope.launch {
            scale.state.collect { state ->
                runCatching {
                    _state.update { it.copy(scale = state) }
                    // Capability-driven keep-alive: some scales need a periodic
                    // heartbeat write — the Decent's LCD (2 s) and the Acaia,
                    // which stops streaming weight entirely without one (3 s,
                    // Decenza acaiascale.cpp:264-277).
                    updateScaleHeartbeat(state == ScaleBleManager.State.READY)
                    // Connecting auto-remembers the scale → its Auto-connect turns ON.
                    if (state == ScaleBleManager.State.READY) {
                        scale.connectedAddress?.let { addr ->
                            scale.autoReconnectEnabled = true
                            val name = scale.connectedName
                            if (_state.value.rememberedScaleAddress != addr ||
                                _state.value.rememberedScaleName != name
                            ) {
                                _state.update { it.copy(
                                    rememberedScaleAddress = addr,
                                    rememberedScaleName = name,
                                ) }
                                persistPrefs()
                            }
                        }
                    }
                    // A scale connect/disconnect changes the roster this primary
                    // advertises to its mirrors (issue 04) — re-push so a secondary
                    // that attached BEFORE the scale was connected attaches it now,
                    // without needing to reconnect (the Welcome roster is attach-time
                    // only).
                    pushRoster()
                }.onFailure { appendLog("Scale state handling failed: ${it.message}") }
            }
        }
        // The keep-awake heartbeat: every 60 s the VM decides whether to
        // rewrite UserPresent (the DE1's sleep timer is minutes-scale, so a
        // minute cadence keeps it pinned without chattering the bus).
        scope.launch {
            while (true) {
                delay(60_000)
                keepAliveTick()
            }
        }
    }

    /** Unregister the adapter watcher and tear both device links down.
     *  Called from the VM's `onCleared`, before the transport closes. */
    fun close() {
        runCatching { app.unregisterReceiver(bluetoothReceiver) }
        bleScanner.cancel(SCAN_LABEL_DE1)
        bleScanner.cancel(SCAN_LABEL_SCALE)
        ble.disconnect()
        scale.disconnect()
    }

    // ── Connect / disconnect verbs ────────────────────────────────────────────

    /** Scan for and connect to a DE1. */
    fun connect() {
        onConnectStarted()
        // Show "scanning" on the connection-status UI while the shared scanner
        // hunts; the scanner's onFound hands the matched DE1 to ble.connect.
        ble.markScanning()
        bleScanner.scanFor(SCAN_LABEL_DE1, De1BleManager::isDe1Name) { device, _ ->
            ble.connect(device)
        }
        // The manager's state flow is collected in [start]; markScanning() above
        // already pushed the new state, so no manual reflection is needed here.
    }

    fun disconnect() {
        // Drop any outstanding scan want too — the user may disconnect mid-scan.
        bleScanner.cancel(SCAN_LABEL_DE1)
        ble.disconnect()
        // Snap the coarse state immediately (the manager's own DISCONNECTED
        // emission follows) so the status UI doesn't lag the tap.
        _state.update { it.copy(de1 = De1BleManager.State.DISCONNECTED) }
        onDe1SessionClosed()
    }

    /** Scan for and connect to a scale. Independent of the DE1. */
    fun connectScale() {
        scale.markScanning()
        // AND6: scan for EVERY supported scale's advertised-name prefix (the
        // core-owned registry), not a hardcoded Bookoo rule — the web shell does
        // the same. Resolved once per scan; the connected model's codec + UUIDs
        // come from the core in ScaleBleManager.establish(). Case-INSENSITIVE
        // to match the core's `Scale::identify` (the scan used to be stricter
        // than identify, so a mixed-case unit could pass identify but never
        // scan-match — e.g. "eCompass" vs "ECOMPASS").
        val prefixes = scale.supportedScaleNamePrefixes()
        bleScanner.scanFor(SCAN_LABEL_SCALE, { name -> prefixes.any { name.startsWith(it, ignoreCase = true) } }) { device, name ->
            scale.connect(device, name)
        }
    }

    fun disconnectScale() {
        bleScanner.cancel(SCAN_LABEL_SCALE)
        scale.disconnect()
        _state.update { it.copy(scale = ScaleBleManager.State.DISCONNECTED) }
        onScaleSessionClosed()
    }

    // ── Auto-connect (per device) ─────────────────────────────────────────────

    /** Per-device "Auto-connect" toggle for the DE1. ON remembers the device (so
     *  the app reconnects on an unexpected drop and connects on launch); OFF
     *  forgets it — clears the saved address and disarms reconnect, WITHOUT
     *  dropping the current session. Mirrored into the manager immediately. */
    fun setDe1AutoConnect(on: Boolean) {
        val addr = if (on) (_state.value.de1Address ?: _state.value.rememberedDe1Address) else null
        _state.update { it.copy(rememberedDe1Address = addr) }
        ble.autoReconnectEnabled = addr != null
        // Turning it off while a (cold-start) scan/connect is still in flight cancels
        // it, so a pending auto-connect can't immediately re-remember the device. A
        // live (READY) session is left connected — off just means "don't reconnect".
        if (!on && ble.state.value != De1BleManager.State.READY) disconnect()
        persistPrefs()
    }

    /** Per-device "Auto-connect" toggle for the scale (the scale-side twin). */
    fun setScaleAutoConnect(on: Boolean) {
        val addr = if (on) (scale.connectedAddress ?: _state.value.rememberedScaleAddress) else null
        _state.update { it.copy(rememberedScaleAddress = addr) }
        scale.autoReconnectEnabled = addr != null
        if (!on && scale.state.value != ScaleBleManager.State.READY) disconnectScale()
        persistPrefs()
    }

    /**
     * Hydrate the remembered addresses from the persisted prefs (the VM's
     * prefs load), arm each manager's link-drop reconnect loop, and cold-start
     * auto-connect to each remembered device. Connects to the first DE1/scale
     * matched by name — in the common single-machine setup that is the
     * remembered one. Best-effort: a missing BLE permission (or no device in
     * range) just no-ops.
     */
    fun hydrateRemembered(de1Address: String?, scaleAddress: String?, scaleName: String?) {
        _state.update { it.copy(
            rememberedDe1Address = de1Address,
            rememberedScaleAddress = scaleAddress,
            rememberedScaleName = scaleName,
        ) }
        // Per-device auto-connect = a remembered address: arm each manager's
        // link-drop loop only for a device whose Auto-connect is ON.
        ble.autoReconnectEnabled = de1Address != null
        scale.autoReconnectEnabled = scaleAddress != null
        runCatching {
            if (de1Address != null && ble.state.value == De1BleManager.State.IDLE) connect()
            if (scaleAddress != null && scale.state.value == ScaleBleManager.State.IDLE) connectScale()
        }
    }

    /** Bluetooth came back ON: kick a fresh connect for any remembered device that
     *  isn't already connecting/connected. Its reconnect loop may have given up
     *  (MAX_RECONNECT_ATTEMPTS) while the adapter was off, so nothing else retries. */
    private fun onBluetoothEnabled() {
        // Re-kick anything not already connected or mid-handshake. SCANNING is
        // included on purpose: a scan in flight when the adapter went off is
        // parked on the shared scan. The transport self-heals that scan when the
        // adapter powers on (NordicBleTransport.rawScan's retryWhen), so a
        // first-time pairing recovers on its own — but for a REMEMBERED device we
        // still prefer the scan-free DIRECT connect below, which also works when
        // the (unfiltered) scan is throttled to silence with the screen off
        // (reaprime #107). connectScale/connect cancel+replace the stale want; the
        // direct branch cancels it explicitly (see below) so the self-healed scan
        // can't fire a second, competing connect for the same device.
        fun stale(s: De1BleManager.State) =
            s == De1BleManager.State.IDLE || s == De1BleManager.State.DISCONNECTED || s == De1BleManager.State.SCANNING
        fun stale(s: ScaleBleManager.State) =
            s == ScaleBleManager.State.IDLE || s == ScaleBleManager.State.DISCONNECTED || s == ScaleBleManager.State.SCANNING
        runCatching {
            // Prefer a scan-free DIRECT connect by the remembered address:
            // this receiver can fire with the app backgrounded and the
            // screen off, where Android throttles our (deliberately)
            // unfiltered scan to zero results — the reaprime #107 failure.
            // A direct GATT connect is never throttled. Fall back to the
            // name scan when the transport can't mint address handles
            // (LAN-proxy mode) or the scale's name was never persisted.
            val st = _state.value
            if (st.rememberedDe1Address != null && stale(ble.state.value)) {
                val direct = transport.resolveByAddress(st.rememberedDe1Address, "DE1")
                if (direct != null) {
                    // Drop any parked cold-start scan want first: the now-healed
                    // scan must not deliver a match that fires a second, competing
                    // ble.connect() against this direct connect.
                    bleScanner.cancel(SCAN_LABEL_DE1)
                    ble.markScanning()
                    ble.connect(direct)
                } else {
                    connect()
                }
            }
            if (st.rememberedScaleAddress != null && stale(scale.state.value)) {
                val name = st.rememberedScaleName
                val direct = if (name != null) {
                    transport.resolveByAddress(st.rememberedScaleAddress, name)
                } else {
                    null
                }
                if (direct != null && name != null) {
                    bleScanner.cancel(SCAN_LABEL_SCALE)
                    scale.connect(direct, name)
                } else {
                    connectScale()
                }
            }
        }
    }

    // ── Scale keep-alive ──────────────────────────────────────────────────────

    /** Start/stop the capability-driven scale heartbeat. The cadence comes
     *  from `ScaleCapabilities.heartbeat_interval_ms`; scales without one
     *  idle the loop at a slow poll so a late-arriving capability read (it
     *  lands just after READY) still picks the clock up. */
    private fun updateScaleHeartbeat(ready: Boolean) {
        if (!ready) {
            scaleHeartbeatJob?.cancel()
            scaleHeartbeatJob = null
            return
        }
        if (scaleHeartbeatJob?.isActive == true) return
        // The heartbeat's bridge call runs on the core lane, not Main
        // (review #28).
        scaleHeartbeatJob = scope.launch(coreDispatcher) {
            while (true) {
                val interval = heartbeatIntervalMs()
                if (interval != null) {
                    runCatching { sendScaleHeartbeat() }
                        .onFailure { appendLog("Scale heartbeat failed: ${it.message}") }
                    delay(interval)
                } else {
                    delay(1_000)
                }
            }
        }
    }

    private companion object {
        /** [BleScanner] want labels — one per device the app discovers. */
        const val SCAN_LABEL_DE1 = "DE1"
        const val SCAN_LABEL_SCALE = "Scale"
    }
}
