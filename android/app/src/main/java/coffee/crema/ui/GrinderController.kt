package coffee.crema.ui

import android.os.SystemClock
import coffee.crema.ble.BleScanner
import coffee.crema.ble.BleTransport
import coffee.crema.ble.GrinderBleManager
import coffee.crema.ble.KickAction
import coffee.crema.ble.ReconnectKicker
import coffee.crema.ble.ReconnectTrigger
import coffee.crema.ble.planKick
import coffee.crema.ble.toLinkState
import coffee.crema.core.Event
import coffee.crema.core.Grinder
import coffee.crema.core.RunKind
import coffee.crema.core.deviceScanFilters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The connected grinder (SPEC grinder-ble G2b, Android): its connection
 * lifecycle on top of [GrinderBleManager] — the shared scan by name, the
 * remembered grinder and its auto-connect, the reconnect kicks, the DE1
 * firmware-flash pause — and what the core says about it, folded into
 * [MainUiState.connectedGrinder] and [LiveTelemetry.grinderReading].
 *
 * Connecting is the user's: Crema connects only a grinder the user picked
 * (Devices → Grinder → Connect) or one it remembers from that. A remote never
 * connects a grinder (the hub owns the link, §6.2).
 *
 * Logging: one short line per event, never the event itself (they carry the
 * serial) and never a reading (5 Hz).
 */
class GrinderController internal constructor(
    private val scope: CoroutineScope,
    private val lane: CoreLane,
    private val manager: GrinderBleManager,
    private val bleScanner: BleScanner,
    private val transport: BleTransport,
    private val uiState: () -> MainUiState,
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    private val updateLive: ((LiveTelemetry) -> LiveTelemetry) -> Unit,
    private val appendLog: (String) -> Unit,
    private val notify: (String) -> Unit,
    private val persistPrefs: () -> Unit,
    /** Add a grinder record from the catalogue entry [presetId]; null when refused. */
    private val addFromCatalogue: (presetId: String) -> Grinder?,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    /** How long a scan hunts before "Can't see your Motto 80" (§2.7: 10 s). */
    private val scanTimeoutMs: Long = SCAN_TIMEOUT_MS,
    /** Tell the core which grinder record the connected grinder is linked to
     *  (null = none): `CremaBridge.setGrinderLink`, its output delivered. */
    private val setCoreLink: (String?) -> Unit = {},
    /** Follow mode asks to set Brew's grind (core `FollowGrind`): the same
     *  path as a typed grind, so it is persisted and mirrored. */
    private val applyFollowGrind: (String) -> Unit = {},
    /** "Pause following" to the core: `CremaBridge.setGrinderFollowPaused`, its output delivered. */
    private val setCorePaused: (Boolean) -> Unit = {},
    /** What the screens show (on a remote, the hub's): Brew's grind scale
     *  for the announce reads it, like every machine or session field. */
    private val effectiveState: () -> MainUiState = uiState,
    /** Wall clock, for "last seen 12:03" on a stale follow. */
    private val wallMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ScanFilter(
        val name_prefixes: List<String> = emptyList(),
        val service_uuids: List<String> = emptyList(),
    )

    private val filters: ScanFilter by lazy {
        runCatching { json.decodeFromString(ScanFilter.serializer(), deviceScanFilters("Grinder")) }
            .getOrDefault(ScanFilter())
    }

    /** The grinder services: never relayed to mirrors ([coffee.crema.ble.proxy.RelayHub]). */
    val serviceUuids: Set<java.util.UUID> by lazy {
        filters.service_uuids.mapNotNull { runCatching { java.util.UUID.fromString(it) }.getOrNull() }.toSet()
    }

    private val flashPause = ScaleFlashPause(object : ScaleFlashPause.Port {
        override fun scaleWanted(): Boolean {
            // On a remote the grinder is never wanted, whatever is
            // remembered: a flash must not bring it back.
            if (!allowed() || manager.isUserDisconnected) return false
            val s = manager.state.value
            return uiState().rememberedGrinderAddress != null ||
                (s != GrinderBleManager.State.IDLE && s != GrinderBleManager.State.DISCONNECTED) ||
                manager.reconnectPhase != ReconnectKicker.Phase.IDLE
        }

        override fun standDown() {
            bleScanner.cancel(SCAN_LABEL)
            scanTimeout?.cancel()
            manager.heldForFirmwareFlash = true
            val s = manager.state.value
            if ((s == GrinderBleManager.State.IDLE || s == GrinderBleManager.State.DISCONNECTED) &&
                manager.reconnectPhase == ReconnectKicker.Phase.IDLE
            ) {
                return
            }
            manager.disconnect()
        }

        override fun bringBack() {
            if (uiState().rememberedGrinderAddress != null) directOrScan() else connect()
        }

        override fun log(line: String) = appendLog(line)
    }, label = "Grinder", quietWhenUnwanted = true)

    private var scanTimeout: Job? = null
    @Volatile private var readyAtMs: Long? = null
    /** It gave a reading since the user's last connect (lane-owned). */
    @Volatile private var everReady = false
    /** `NoFirstReading`s in a row since the last reading (lane-owned). */
    @Volatile private var silentReconnects = 0
    /** Why the controller stopped the link for good, until the next connect:
     *  late states from the stopped session mustn't hide it. */
    @Volatile private var stoppedAs: GrinderLinkStatus? = null
    @Volatile private var scanSinceMs: Long? = null
    private val kickDebouncer = coffee.crema.ble.KickDebouncer(nowMs)

    /** The link last handed to the core (an unset sentinel until the first). */
    @Volatile private var coreLink: Any? = UNSET

    /**
     * The grinder record the core follows and stamps shots for: the link,
     * while it belongs to the connected or remembered grinder (a remote
     * never has one). Handed over only when it changes.
     */
    internal fun syncCoreLink() {
        val st = uiState()
        val address = manager.connectedAddress ?: st.rememberedGrinderAddress
        val id = st.grinderLinkedId?.takeIf {
            allowed() && address != null && address.equals(st.grinderLinkedAddress, ignoreCase = true)
        }
        if (coreLink == id) return
        coreLink = id
        runCatching { setCoreLink(id) }.onFailure { appendLog("Grinder link not handed to the core: ${it.message}") }
    }

    init {
        lane.on<Event.GrinderFollowChanged> { e ->
            val f = e.content.follow
            updateUi { s ->
                val was = s.grinderFollow.status
                s.copy(
                    grinderFollow = f,
                    // "Last seen 12:03" while the linked grinder is away.
                    grinderFollowStaleSince = when {
                        f.status != coffee.crema.core.FollowStatus.Stale -> null
                        was == coffee.crema.core.FollowStatus.Stale -> s.grinderFollowStaleSince
                        else -> wallMs()
                    },
                )
            }
        }
        lane.on<Event.FollowGrind> { e ->
            val grind = e.content.grind
            appendLog("Grind $grind from the grinder")
            scope.launch {
                applyFollowGrind(grind)
                if (e.content.announce) {
                    val scale = effectiveState().activeGrindScale()
                    val shown = runCatching { coffee.crema.beans.formatGrind(grind, scale) }.getOrDefault(grind)
                    notify("Grind $shown from your ${grinderModelName(uiState().connectedGrinder.model)}")
                }
            }
        }
        lane.on<Event.GrinderReady> { e ->
            val info = e.content.info
            everReady = true
            appendLog("Grinder ready: ${grinderModelName(info.model)}" + (info.firmware?.let { ", firmware $it" } ?: ""))
            updateUi { s ->
                s.copy(connectedGrinder = s.connectedGrinder.copy(
                    status = GrinderLinkStatus.LIVE,
                    model = info.model,
                    firmware = info.firmware,
                    serialTail = serialTail(info.serial),
                    message = null,
                ))
            }
            scope.launch { resolveLink(info.model) }
        }
        lane.on<Event.GrinderReading> { e ->
            silentReconnects = 0
            updateLive { it.copy(grinderReading = e.content.reading) }
            if (uiState().connectedGrinder.status != GrinderLinkStatus.LIVE) {
                updateUi { s -> s.copy(connectedGrinder = s.connectedGrinder.copy(status = GrinderLinkStatus.LIVE, message = null)) }
            }
        }
        lane.on<Event.GrinderInfoChanged> { e ->
            val info = e.content.info
            info.firmware?.let { appendLog("Grinder firmware $it") }
            updateUi { s ->
                s.copy(connectedGrinder = s.connectedGrinder.copy(
                    firmware = info.firmware,
                    serialTail = serialTail(info.serial),
                ))
            }
        }
        lane.on<Event.GrinderLinkDead> { e ->
            val reason = e.content.reason
            appendLog("Grinder link dead: ${reason.string}")
            updateLive { it.copy(grinderReading = null) }
            val noFirst = reason == coffee.crema.core.GrinderLinkDeadReason.NoFirstReading
            if (noFirst && everReady) silentReconnects += 1
            // As web #551: a first connect that never answers stops; one that
            // answered before stops after NO_DATA_GIVE_UP silent reconnects.
            val stop = noFirst && (!everReady || silentReconnects >= NO_DATA_GIVE_UP)
            val noData = noFirst && everReady && stop
            updateUi { s ->
                s.copy(connectedGrinder = s.connectedGrinder.copy(
                    status = if (noData) GrinderLinkStatus.NO_DATA else GrinderLinkStatus.NO_ANSWER,
                    message = if (noData) GrinderMessages.NO_DATA else GrinderMessages.noAnswer(reason, s.connectedGrinder.earlyDrops),
                ))
            }
            when {
                stop -> {
                    stoppedAs = if (noData) GrinderLinkStatus.NO_DATA else GrinderLinkStatus.NO_ANSWER
                    if (noData) appendLog("Grinder not sending data after $NO_DATA_GIVE_UP reconnects — stopped reconnecting")
                    scope.launch { manager.giveUp(if (noData) "not sending data" else "no answer") }
                }
                manager.state.value == GrinderBleManager.State.READY ->
                    scope.launch { manager.presumeDead(reason.string) }
            }
        }
        lane.on<Event.GrinderUnsupported> { e ->
            appendLog("Grinder firmware ${e.content.firmware} is older than ${e.content.minimum}")
            updateLive { it.copy(grinderReading = null) }
            updateUi { s ->
                s.copy(connectedGrinder = s.connectedGrinder.copy(
                    status = GrinderLinkStatus.UPDATE_FIRMWARE,
                    firmware = e.content.firmware,
                    message = GrinderMessages.updateFirmware(e.content.minimum),
                ))
            }
            scope.launch { manager.giveUp("firmware too old") }
        }
        lane.on<Event.GrindCompleted> { e ->
            val g = e.content.session
            val what = if (g.kind == RunKind.Grind) "Grind" else "Purge"
            appendLog(
                "$what: %.1f s".format(g.duration_ms.toLong() / 1000.0) +
                    (g.setting?.let { " at ${it.value}" } ?: "") +
                    (g.grind_rpm?.let { ", $it rpm" } ?: ""),
            )
            if (g.kind == RunKind.Grind) {
                updateUi { s -> s.copy(connectedGrinder = s.connectedGrinder.copy(lastGrind = g)) }
            }
        }
    }

    /** Collect the manager's state into the card. Called from the VM's `init`. */
    fun start() {
        scope.launch {
            combine(manager.state, manager.reconnectPhaseFlow) { s, p -> s to p }.collect { (s, p) ->
                runCatching { onManagerState(s, p) }.onFailure { appendLog("Grinder state handling failed: ${it.message}") }
            }
        }
    }

    private fun onManagerState(s: GrinderBleManager.State, phase: ReconnectKicker.Phase) {
        if (s != GrinderBleManager.State.SCANNING) scanSinceMs = null
        val now = nowMs()
        val wasReadyAt = readyAtMs
        val early = wasReadyAt != null && s != GrinderBleManager.State.READY && now - wasReadyAt < EARLY_DROP_MS &&
            !manager.isUserDisconnected
        if (s == GrinderBleManager.State.READY) readyAtMs = now else if (wasReadyAt != null) readyAtMs = null
        if (s == GrinderBleManager.State.READY) remember()
        updateUi { st ->
            val cur = st.connectedGrinder
            val reconnecting = phase != ReconnectKicker.Phase.IDLE && !manager.isUserDisconnected
            val stopped = stoppedAs
            val status = if (stopped != null && manager.isUserDisconnected) {
                stopped
            } else if (cur.status in TERMINAL) {
                // The session ended for good (old firmware, not a grinder): a
                // late state from it mustn't hide that; only a new connect clears it.
                cur.status
            } else when (s) {
                GrinderBleManager.State.SCANNING -> GrinderLinkStatus.SCANNING
                GrinderBleManager.State.CONNECTING,
                GrinderBleManager.State.DISCOVERING,
                GrinderBleManager.State.SUBSCRIBING ->
                    if (reconnecting && cur.status != GrinderLinkStatus.OFF) GrinderLinkStatus.RECONNECTING else GrinderLinkStatus.CONNECTING
                GrinderBleManager.State.READY ->
                    if (cur.status == GrinderLinkStatus.LIVE) GrinderLinkStatus.LIVE else GrinderLinkStatus.WAITING
                GrinderBleManager.State.IDLE, GrinderBleManager.State.DISCONNECTED -> when {
                    reconnecting -> GrinderLinkStatus.RECONNECTING
                    cur.status in STICKY -> cur.status
                    else -> GrinderLinkStatus.OFF
                }
            }
            val drops = cur.earlyDrops + if (early) 1 else 0
            st.copy(
                connectedGrinderAddress = manager.connectedAddress,
                connectedGrinder = cur.copy(
                    status = status,
                    earlyDrops = drops,
                    message = if (early && drops >= 2) GrinderMessages.withRestartHint(cur.message ?: "The grinder dropped right after connecting.", drops) else cur.message,
                ),
            )
        }
        if (s == GrinderBleManager.State.DISCONNECTED || s == GrinderBleManager.State.IDLE) {
            updateLive { it.copy(grinderReading = null) }
        }
    }

    /** Connecting auto-remembers the grinder, like the scale (Auto-connect on). */
    private fun remember() {
        syncCoreLink()
        val addr = manager.connectedAddress ?: return
        val name = manager.connectedName
        manager.autoReconnectEnabled = true
        val st = uiState()
        if (st.rememberedGrinderAddress == addr && st.rememberedGrinderName == name) return
        updateUi { it.copy(rememberedGrinderAddress = addr, rememberedGrinderName = name) }
        persistPrefs()
    }

    /** §6.1: which owned record this grinder is. Main thread. */
    private fun resolveLink(model: String) {
        val st = uiState()
        val address = manager.connectedAddress ?: return
        when (val plan = planGrinderLink(model, address, st.grinderLinkedAddress, st.grinderLinkedId, st.grinders)) {
            is GrinderLinkPlan.Keep -> updateUi { s -> s.copy(connectedGrinder = s.connectedGrinder.copy(linkChoices = emptyList(), offerAdd = false)) }
            is GrinderLinkPlan.LinkNew -> {
                link(plan.id)
                notify("Linked to your ${grinderModelName(model)}")
            }
            is GrinderLinkPlan.Choose -> updateUi { s ->
                s.copy(connectedGrinder = s.connectedGrinder.copy(
                    linkChoices = plan.ids,
                    offerAdd = false,
                    message = if (plan.notYours) GrinderMessages.NOT_YOUR_LINKED else s.connectedGrinder.message,
                ))
            }
            GrinderLinkPlan.OfferAdd -> updateUi { s -> s.copy(connectedGrinder = s.connectedGrinder.copy(linkChoices = emptyList(), offerAdd = true)) }
        }
    }

    /** Link the connected grinder to record [id] (null unlinks). Persisted. */
    fun link(id: String?) {
        val address = manager.connectedAddress ?: uiState().rememberedGrinderAddress
        updateUi { s ->
            s.copy(
                grinderLinkedAddress = if (id == null) null else address,
                grinderLinkedId = id,
                connectedGrinder = s.connectedGrinder.copy(
                    linkChoices = emptyList(),
                    offerAdd = id == null && s.connectedGrinder.model != null,
                    message = if (s.connectedGrinder.message == GrinderMessages.NOT_YOUR_LINKED) null else s.connectedGrinder.message,
                ),
            )
        }
        persistPrefs()
        syncCoreLink()
    }

    /** "Add Motto 80 to your grinders" (§6.1 rule 4): add it from the catalogue and link it. */
    fun addAndLink() {
        val model = uiState().connectedGrinder.model ?: return
        val added = addFromCatalogue(model) ?: return
        link(added.id)
        notify("Added ${added.name} and linked it")
    }

    // ── Connect / disconnect ──────────────────────────────────────────────────

    private fun allowed(): Boolean {
        val st = uiState()
        return st.proxyRole != "secondary"
    }

    /** Scan for and connect to a grinder (Devices → Grinder → Connect). */
    fun connect() {
        if (!allowed()) return
        freshSession()
        if (flashPause.holdConnect()) return
        val prefixes = filters.name_prefixes
        if (prefixes.isEmpty()) {
            appendLog("No grinder scan filters from the core")
            return
        }
        updateUi { s -> s.copy(connectedGrinder = s.connectedGrinder.copy(message = null, status = GrinderLinkStatus.SCANNING)) }
        manager.markScanning()
        scanSinceMs = nowMs()
        scanTimeout?.cancel()
        scanTimeout = scope.launch {
            delay(scanTimeoutMs)
            if (manager.state.value == GrinderBleManager.State.SCANNING) {
                bleScanner.cancel(SCAN_LABEL)
                manager.markNotFound()
                updateUi { s ->
                    s.copy(connectedGrinder = s.connectedGrinder.copy(
                        status = GrinderLinkStatus.NOT_FOUND,
                        message = GrinderMessages.notFound(grinderModelName(GRINDER_MODEL_FALLBACK)),
                    ))
                }
            }
        }
        bleScanner.scanFor(SCAN_LABEL, { name -> prefixes.any { name.startsWith(it, ignoreCase = true) } }) { device, name ->
            scanTimeout?.cancel()
            manager.connect(device, name)
        }
    }

    /** Devices → Grinder → Disconnect: drop the link, keep the remembered grinder. */
    fun disconnect() {
        bleScanner.cancel(SCAN_LABEL)
        scanTimeout?.cancel()
        manager.disconnect()
        updateLive { it.copy(grinderReading = null) }
        updateUi { s -> s.copy(connectedGrinder = ConnectedGrinderUi(lastGrind = s.connectedGrinder.lastGrind)) }
    }

    /** Devices → Grinder → Forget: disconnect and stop auto-connecting. The link
     *  to the grinder record is kept, so connecting it again keeps it. */
    fun forget() {
        disconnect()
        manager.autoReconnectEnabled = false
        updateUi { it.copy(rememberedGrinderAddress = null, rememberedGrinderName = null) }
        persistPrefs()
        syncCoreLink()
    }

    /** At launch (prefs loaded): connect the remembered grinder, if there is one. */
    fun hydrate() {
        syncCoreLink()
        val st = uiState()
        manager.autoReconnectEnabled = st.rememberedGrinderAddress != null
        if (!allowed() || st.rememberedGrinderAddress == null) return
        if (manager.state.value == GrinderBleManager.State.IDLE ||
            manager.state.value == GrinderBleManager.State.DISCONNECTED
        ) {
            directOrScan()
        }
    }

    /** "Retry now", foreground, Bluetooth back on: reconnect the remembered grinder now. */
    fun kickReconnect(trigger: ReconnectTrigger) {
        if (!allowed() || uiState().rememberedGrinderAddress == null || flashPause.paused) return
        val action = planKick(
            remembered = true,
            userDisconnected = manager.isUserDisconnected,
            phase = manager.reconnectPhase,
            link = manager.state.value.toLinkState(),
            scanAgeMs = scanSinceMs?.let { nowMs() - it },
        )
        when (action) {
            KickAction.NONE -> Unit
            KickAction.KICK_SESSION -> {
                if (manager.kickReconnect(trigger) == ReconnectKicker.Result.KICKED) appendLog("Grinder: reconnecting now (${trigger.label})")
            }
            KickAction.DIRECT_CONNECT -> {
                if (!kickDebouncer.tryAcquire(SCAN_LABEL)) return
                appendLog("Grinder: connecting now (${trigger.label})")
                directOrScan()
            }
        }
    }

    /** Whether [address] is the connected or the remembered grinder: the hub
     *  never relays it to remotes. */
    fun isGrinderAddress(address: String): Boolean =
        address.equals(manager.connectedAddress, ignoreCase = true) ||
            address.equals(uiState().rememberedGrinderAddress, ignoreCase = true)

    /** A DE1 firmware flash started or ended: pause the grinder link for it. */
    fun onFirmwareFlash(active: Boolean) {
        if (!active) manager.heldForFirmwareFlash = false
        flashPause.onFlashActive(active)
    }

    /** A connect the user (or the launch) asked for starts the silent-reconnect count over. */
    private fun freshSession() {
        stoppedAs = null
        lane.post {
            everReady = false
            silentReconnects = 0
        }
    }

    /** "Reconnect" after the grinder stopped sending data, or any time it isn't linked. */
    fun reconnect() {
        if (uiState().rememberedGrinderAddress != null) directOrScan() else connect()
    }

    private fun directOrScan() {
        if (!allowed()) return
        if (flashPause.holdConnect()) return
        freshSession()
        val st = uiState()
        val addr = st.rememberedGrinderAddress
        val name = st.rememberedGrinderName
        val direct = if (addr != null && name != null) transport.resolveByAddress(addr, name) else null
        if (direct != null && name != null) {
            bleScanner.cancel(SCAN_LABEL)
            updateUi { s -> s.copy(connectedGrinder = s.connectedGrinder.copy(message = null, status = GrinderLinkStatus.CONNECTING)) }
            manager.connect(direct, name)
        } else {
            connect()
        }
    }

    /** VM teardown. */
    fun close() {
        bleScanner.cancel(SCAN_LABEL)
        manager.disconnect()
        manager.close()
    }

    /** "Pause following" for this session, or resume (owner decision 3). */
    fun pauseFollowing(paused: Boolean) {
        runCatching { setCorePaused(paused) }.onFailure { appendLog("Follow pause not handed to the core: ${it.message}") }
    }

    companion object {
        private val UNSET = Any()

        const val SCAN_LABEL = "Grinder"
        const val SCAN_TIMEOUT_MS = 10_000L

        /** The model the "can't see" message names before any grinder is identified. */
        const val GRINDER_MODEL_FALLBACK = "bookoo-motto-80"

        /** Statuses that end a session; cleared only by the user's next connect. */
        private val TERMINAL = setOf(GrinderLinkStatus.UPDATE_FIRMWARE, GrinderLinkStatus.MISMATCH, GrinderLinkStatus.NO_DATA)

        /** Statuses a drop leaves on the card until the next connect. */
        private val STICKY = setOf(
            GrinderLinkStatus.NOT_FOUND, GrinderLinkStatus.NO_ANSWER, GrinderLinkStatus.NO_DATA,
            GrinderLinkStatus.UPDATE_FIRMWARE, GrinderLinkStatus.MISMATCH,
        )
    }

    /** The manager identified the grinder: its model and the dial's unit. */
    internal fun onIdentified(model: String, capabilitiesJson: String?) {
        val unit = capabilitiesJson?.let {
            runCatching { json.decodeFromString(coffee.crema.core.GrinderCapabilities.serializer(), it).setting?.unit }.getOrNull()
        }
        updateUi { s -> s.copy(connectedGrinder = s.connectedGrinder.copy(model = model, unit = unit)) }
    }

    /** The manager's mismatch callback. */
    internal fun onMismatch() {
        updateUi { s ->
            s.copy(connectedGrinder = s.connectedGrinder.copy(status = GrinderLinkStatus.MISMATCH, message = GrinderMessages.MISMATCH))
        }
    }
}
