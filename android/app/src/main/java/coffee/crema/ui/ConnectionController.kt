package coffee.crema.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.SystemClock
import coffee.crema.ble.BleScanner
import coffee.crema.ble.BleTransport
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.KickAction
import coffee.crema.ble.KickDebouncer
import coffee.crema.ble.ReconnectKicker
import coffee.crema.ble.ReconnectPhase
import coffee.crema.ble.ReconnectTimelineRecorder
import coffee.crema.ble.ReconnectTrigger
import coffee.crema.ble.ScaleBleManager
import coffee.crema.ble.companion.CompanionCoordinator
import coffee.crema.ble.companion.CompanionDevice
import coffee.crema.ble.companion.CompanionPresence
import coffee.crema.ble.companion.PresenceAction
import coffee.crema.ble.companion.presenceAction
import coffee.crema.ble.companion.presenceTarget
import coffee.crema.ble.planKick
import coffee.crema.ble.showsReconnecting
import coffee.crema.ble.toLinkState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
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
    /** Whether the activity is visible — gates the unlock (USER_PRESENT) kick. */
    private val isAppForeground: () -> Boolean = { true },
    /** Reconnect timelines; a kick that starts a fresh connect opens an episode. */
    private val timeline: ReconnectTimelineRecorder? = null,
    /** Monotonic clock (debounce, scan age). */
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    /** Companion-device associations + presence (null = not used). */
    private val companion: CompanionCoordinator? = null,
    /** Presence reports from the companion service ([CompanionPresence.events]). */
    private val presenceEvents: Flow<CompanionPresence.Event> = emptyFlow(),
    /** A device was just remembered (auto-remember on its first READY) — the
     *  VM may offer "Reconnect automatically when it's nearby". */
    private val onDeviceRemembered: (CompanionDevice, String) -> Unit = { _, _ -> },
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
        /** The DE1 is being reconnected (between / during re-attempts, or a
         *  cold-start scan) — the UI shows "Reconnecting…" + "Retry now". */
        val de1Reconnecting: Boolean = false,
        /** The scale twin of [de1Reconnecting]. */
        val scaleReconnecting: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The scale keep-alive loop, running only while a scale is READY. */
    private var scaleHeartbeatJob: Job? = null

    /** Debounces the kick paths that start a fresh connect (no session to kick). */
    private val kickDebouncer = KickDebouncer(nowMs)

    /** When each device's cold-start scan want was registered, for [planKick]'s
     *  scan-age rule; cleared when the want resolves or is cancelled. */
    @Volatile private var de1ScanSinceMs: Long? = null
    @Volatile private var scaleScanSinceMs: Long? = null

    /** Screen unlocked (USER_PRESENT) while the app is visible: reconnect now.
     *  onStart covers most returns; this catches unlocking onto a visible app. */
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT && isAppForeground()) {
                kickReconnect(ReconnectTrigger.FOREGROUND)
            }
        }
    }

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
            if (on) kickReconnect(ReconnectTrigger.BLUETOOTH_ON)
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
        // USER_PRESENT is a protected system broadcast — EXPORTED is required
        // to receive it (no app can forge it).
        ContextCompat.registerReceiver(
            app, userPresentReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // The lurk tier follows companion presence: idle while an associated
        // device is reported away, a pending (autoConnect) connect otherwise.
        companion?.let { c ->
            ble.lurkPolicy = { c.lurkPolicy(_state.value.rememberedDe1Address, transport.supportsPendingConnect) }
            scale.lurkPolicy = { c.lurkPolicy(_state.value.rememberedScaleAddress, transport.supportsPendingConnect) }
        }
        scope.launch {
            presenceEvents.collect { e ->
                runCatching { onPresence(e.address, e.present) }
                    .onFailure { appendLog("Presence handling failed: ${it.message}") }
            }
        }
        // "Reconnecting" for the UI: remembered + not user-ended + not READY +
        // a session between/during re-attempts (or a cold-start scan).
        scope.launch {
            combine(ble.state, ble.reconnectPhaseFlow) { s, p -> s to p }.collect { (s, p) ->
                if (s != De1BleManager.State.SCANNING) de1ScanSinceMs = null
                val r = showsReconnecting(_state.value.rememberedDe1Address != null, ble.isUserDisconnected, p, s.toLinkState())
                _state.update { it.copy(de1Reconnecting = r) }
            }
        }
        scope.launch {
            combine(scale.state, scale.reconnectPhaseFlow) { s, p -> s to p }.collect { (s, p) ->
                if (s != ScaleBleManager.State.SCANNING) scaleScanSinceMs = null
                val r = showsReconnecting(_state.value.rememberedScaleAddress != null, scale.isUserDisconnected, p, s.toLinkState())
                _state.update { it.copy(scaleReconnecting = r) }
            }
        }
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
                                onDeviceRemembered(CompanionDevice.DE1, addr)
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
                                val newDevice = _state.value.rememberedScaleAddress != addr
                                _state.update { it.copy(
                                    rememberedScaleAddress = addr,
                                    rememberedScaleName = name,
                                ) }
                                persistPrefs()
                                if (newDevice) onDeviceRemembered(CompanionDevice.SCALE, addr)
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
        runCatching { app.unregisterReceiver(userPresentReceiver) }
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
        scanForDe1()
        // The manager's state flow is collected in [start]; markScanning() above
        // already pushed the new state, so no manual reflection is needed here.
    }

    /** Hand the DE1 hunt to the shared scanner (no event-log reset). */
    private fun scanForDe1() {
        ble.markScanning()
        de1ScanSinceMs = nowMs()
        bleScanner.scanFor(SCAN_LABEL_DE1, De1BleManager::isDe1Name) { device, _ ->
            ble.connect(device)
        }
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
        scaleScanSinceMs = nowMs()
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
            if (de1Address != null && ble.state.value == De1BleManager.State.IDLE) {
                timeline?.begin(De1BleManager.TIMELINE_DEVICE, ReconnectTrigger.LAUNCH)
                timeline?.mark(De1BleManager.TIMELINE_DEVICE, ReconnectPhase.SCAN)
                connect()
            }
            if (scaleAddress != null && scale.state.value == ScaleBleManager.State.IDLE) {
                timeline?.begin(ScaleBleManager.TIMELINE_DEVICE, ReconnectTrigger.LAUNCH)
                timeline?.mark(ScaleBleManager.TIMELINE_DEVICE, ReconnectPhase.SCAN)
                connectScale()
            }
        }
    }

    /**
     * Reconnect every remembered device NOW — the app came back to the
     * foreground, the screen was unlocked onto it, Bluetooth came back on, or
     * the user tapped "Retry now". Per device ([planKick]):
     *  - a live session between attempts has its backoff / lurk wait
     *    interrupted and its ladder reset to the fast burst (the session's own
     *    [ReconnectKicker] refuses a second concurrent attempt, ignores READY,
     *    and debounces);
     *  - with no session (a cold-start scan parked for a while, or nothing),
     *    a scan-free DIRECT connect by the remembered address — never
     *    throttled, unlike our unfiltered scan with the screen off (reaprime
     *    #107) — falling back to the name scan where the transport can't mint
     *    address handles (LAN proxy) or the scale's name was never saved;
     *  - a device the user disconnected, READY, or mid-handshake: nothing.
     *
     * Why the DE1's session kick reconnects scan-first (its [De1BleManager]
     * reconnect path) while a fresh connect goes direct: a live session's
     * handle may be stale after a DE1 power cycle (the #65 GATT-133 storm),
     * and its short address-filtered scan both refreshes it and, in the
     * foreground, finds an advertising DE1 within a second or two.
     */
    fun kickReconnect(trigger: ReconnectTrigger) {
        runCatching { kickDe1(trigger) }.onFailure { appendLog("DE1 reconnect kick failed: ${it.message}") }
        runCatching { kickScale(trigger) }.onFailure { appendLog("Scale reconnect kick failed: ${it.message}") }
    }

    /**
     * A companion presence report. "Appeared" reconnects that device NOW
     * (trigger `presence`) through the same planner as a foreground kick —
     * a pending background connect is withdrawn for the fast path; with no
     * session, a direct connect by address. "Disappeared" changes nothing
     * immediately: the device's lurk policy now reads IDLE, so its next lurk
     * round idles instead of scanning.
     */
    fun onPresence(address: String, present: Boolean) {
        val st = _state.value
        val target = presenceTarget(address, st.rememberedDe1Address, st.rememberedScaleAddress)
        val userDisconnected = when (target) {
            CompanionDevice.DE1 -> ble.isUserDisconnected
            CompanionDevice.SCALE -> scale.isUserDisconnected
            null -> false
        }
        when (presenceAction(target, present, userDisconnected)) {
            PresenceAction.KICK -> {
                appendLog("${if (target == CompanionDevice.DE1) "DE1" else "Scale"} is nearby — reconnecting")
                if (target == CompanionDevice.DE1) kickDe1(ReconnectTrigger.PRESENCE) else kickScale(ReconnectTrigger.PRESENCE)
            }
            PresenceAction.IDLE ->
                appendLog("${if (target == CompanionDevice.DE1) "DE1" else "Scale"} went out of range — waiting for it")
            PresenceAction.IGNORE -> Unit
        }
    }

    /** "Retry now" on one device's reconnecting status. */
    fun retryNow(de1: Boolean) {
        if (de1) kickDe1(ReconnectTrigger.USER_RETRY) else kickScale(ReconnectTrigger.USER_RETRY)
    }

    private fun kickDe1(trigger: ReconnectTrigger) {
        val st = _state.value
        val remembered = st.rememberedDe1Address ?: return
        val action = planKick(
            remembered = true,
            userDisconnected = ble.isUserDisconnected,
            phase = ble.reconnectPhase,
            link = ble.state.value.toLinkState(),
            scanAgeMs = de1ScanSinceMs?.let { nowMs() - it },
        )
        when (action) {
            KickAction.NONE -> Unit
            KickAction.KICK_SESSION -> {
                val r = ble.kickReconnect(trigger)
                if (r == ReconnectKicker.Result.KICKED) appendLog("DE1: reconnecting now (${trigger.label})")
            }
            KickAction.DIRECT_CONNECT -> {
                if (!kickDebouncer.tryAcquire(De1BleManager.TIMELINE_DEVICE)) return
                appendLog("DE1: connecting now (${trigger.label})")
                timeline?.begin(De1BleManager.TIMELINE_DEVICE, trigger)
                val direct = transport.resolveByAddress(remembered, "DE1")
                if (direct != null) {
                    // Drop any parked cold-start scan want first: a scan that
                    // heals later must not deliver a match that fires a second,
                    // competing ble.connect() against this direct connect.
                    bleScanner.cancel(SCAN_LABEL_DE1)
                    de1ScanSinceMs = null
                    ble.markScanning()
                    ble.connect(direct)
                } else {
                    timeline?.mark(De1BleManager.TIMELINE_DEVICE, ReconnectPhase.SCAN)
                    scanForDe1()
                }
            }
        }
    }

    private fun kickScale(trigger: ReconnectTrigger) {
        val st = _state.value
        if (st.rememberedScaleAddress == null) return
        val action = planKick(
            remembered = true,
            userDisconnected = scale.isUserDisconnected,
            phase = scale.reconnectPhase,
            link = scale.state.value.toLinkState(),
            scanAgeMs = scaleScanSinceMs?.let { nowMs() - it },
        )
        when (action) {
            KickAction.NONE -> Unit
            KickAction.KICK_SESSION -> {
                val r = scale.kickReconnect(trigger)
                if (r == ReconnectKicker.Result.KICKED) appendLog("Scale: reconnecting now (${trigger.label})")
            }
            KickAction.DIRECT_CONNECT -> {
                if (!kickDebouncer.tryAcquire(ScaleBleManager.TIMELINE_DEVICE)) return
                appendLog("Scale: connecting now (${trigger.label})")
                timeline?.begin(ScaleBleManager.TIMELINE_DEVICE, trigger)
                directOrScanScale()
            }
        }
    }

    /**
     * One best-effort scale (re)connect kick — direct by the remembered
     * address when possible (scan-free, throttle-proof), else the name scan.
     * A no-op while the scale is already connected or mid-handshake. Used by
     * the BT-recovery path above and by shot start (issue #29): a forgotten
     * scale can still arrive mid-shot, where the #15 fix arms SAW late.
     */
    fun kickScaleReconnect() {
        val s = scale.state.value
        val stale = s == ScaleBleManager.State.IDLE || s == ScaleBleManager.State.DISCONNECTED || s == ScaleBleManager.State.SCANNING
        if (!stale) return
        // A live session between attempts: interrupt its wait instead of
        // replacing it (replacing used to race its teardown).
        if (scale.reconnectPhase != ReconnectKicker.Phase.IDLE) {
            scale.kickReconnect(ReconnectTrigger.USER_RETRY)
            return
        }
        directOrScanScale()
    }

    /** Direct connect by the remembered address when possible, else the scan. */
    private fun directOrScanScale() {
        val st = _state.value
        runCatching {
            val name = st.rememberedScaleName
            val direct = if (st.rememberedScaleAddress != null && name != null) {
                transport.resolveByAddress(st.rememberedScaleAddress, name)
            } else {
                null
            }
            if (direct != null && name != null) {
                bleScanner.cancel(SCAN_LABEL_SCALE)
                scaleScanSinceMs = null
                scale.connect(direct, name)
            } else {
                timeline?.mark(ScaleBleManager.TIMELINE_DEVICE, ReconnectPhase.SCAN)
                connectScale()
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
