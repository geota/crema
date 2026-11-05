package coffee.crema.ui

import android.os.SystemClock
import android.util.Log
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.companion.CompanionCoordinator
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.IdleActivity
import coffee.crema.core.MachineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long after a DE1 connects the one-time "nearby" setup waits — long
 *  enough for the machine state to arrive (it only runs on an idle machine). */
private const val NEARBY_SETUP_DELAY_MS = 5_000L

/** How long a Sleep state report is still attributable to our own request
 *  (BLE write → firmware → notify, plus a GoingToSleep dwell). */
private const val APP_SLEEP_WINDOW_MS = 15_000L

/**
 * Presence (MainViewModel breakup step 5): whether someone is at the machine
 * and what follows from it — the screensaver and its coupling to machine
 * sleep, the core's idle-sleep timer, sleep-on-quit, the app's foreground
 * state, and the companion "nearby" setup that lets the keep-alive restart
 * from the background.
 *
 * Registers its core-event handlers on [lane] as it is built. The saver half
 * of `MachineStateChanged` is [onMachineState], called from the VM's handler
 * before the new state is shown.
 */
internal class PresenceController(
    private val lane: CoreLane,
    private val bridge: CremaBridge,
    private val scope: CoroutineScope,
    private val uiState: () -> MainUiState,
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    private val appendLog: (String) -> Unit,
    private val notify: (String) -> Unit,
    private val persistPrefs: () -> Unit,
    /** A firmware flash holds the DE1 (no saver, no nearby setup, its Sleep isn't idleness). */
    private val firmwareHolds: () -> Boolean,
    /** The DE1 link is READY. */
    private val de1Ready: () -> Boolean,
    /** Wake the DE1 (the saver's coupled wake). */
    private val wake: () -> Unit,
    /** Sleep-on-quit's machine write, on the process scope. */
    private val quitSleepWrite: () -> Deferred<Boolean>,
    private val companion: CompanionCoordinator,
    private val connectionState: () -> ConnectionController.State,
    private val connectTo: (String) -> Unit,
    private val disconnect: () -> Unit,
    /** The keep-alive's CDM background-start exemption. */
    private val setBackgroundStartAllowed: (Boolean) -> Unit,
) {
    /** The idle-sleep timer's feed (see [IdleSleepRunner]): every input goes to
     *  the core with `elapsedRealtime`, and the output (the Sleep write, a
     *  throttled UserPresent poke on activity, events) routes like any other. */
    private val idleSleep by lazy {
        IdleSleepRunner(
            scope = scope,
            clock = { SystemClock.elapsedRealtime() },
            feed = { input, now ->
                lane.route("Idle sleep") { bridge.idleSleep(idleSleepInputJson(input), now.toULong()) }
            },
        )
    }

    /** Monotonic ms of the idle timer's last Sleep send (0 = none) — so
     *  sleep-on-quit never doubles it ([quitSleepAllowed]). */
    @Volatile
    private var lastIdleSleepAtMs: Long = 0L


    /** Monotonic ms of the last user interaction (the screensaver's idle
     *  clock) — bumped by the activity's root touch interceptor and by machine
     *  transitions into active modes. */
    @Volatile
    private var lastInteractionAtMs: Long = SystemClock.elapsedRealtime()

    /** Whether the activity is started (visible). */
    @Volatile
    var appInForeground = true

    /** Monotonic ms of the last sleep the app itself asked for (the Brew power
     *  button, the phone menu, a mirrored peer), or 0 once consumed. */
    @Volatile
    private var appSleepRequestedAtMs: Long = 0L

    /** This instance's sleep-on-quit registration ([installQuitSleep]). */
    private var quitSleepRegistration: Any? = null

    /** The pending "nearby" setup after a DE1 connects (see [setUpNearby]). */
    private var nearbySetupJob: kotlinx.coroutines.Job? = null

    /** The address of a DE1 link the setup paused, to reconnect afterwards. */
    private var nearbyPausedAddress: String? = null

    init {
        lane.on<Event.IdleSleepRequested> { event ->
            lastIdleSleepAtMs = SystemClock.elapsedRealtime()
            appendLog(
                "Idle for ${uiState().idleSleepMin} min — putting the DE1 to sleep" +
                    if (event.content.attempt > 1u) " (retry)" else "",
            )
        }
        lane.on<Event.IdleSleepBlocked> { event ->
            appendLog(
                "Idle sleep due but waiting: ${event.content.reason}" +
                    (event.content.state?.let { " (${it.string})" } ?: ""),
            )
        }
        lane.on<Event.IdleSleepUnconfirmed> { appendLog("Idle sleep: the DE1 never reported Sleep — timer re-armed") }
    }

    // ── The idle-sleep timer ─────────────────────────────────────────────────

    /** The DE1 link came up or went. */
    fun linkIdleSleep(up: Boolean) = idleSleep.link(up)

    /** "Sleep after" changed (minutes, 0 = never): the timer restarts from now. */
    fun configureIdleSleep(minutes: Int) = idleSleep.configure(minutes)

    /** Activity for the idle-sleep timer (throttled, see [IdleSleepRunner.activity]). */
    fun idleActivity(activity: IdleActivity) = idleSleep.activity(activity)

    /** A screen / route change — activity for the idle-sleep timer. */
    fun noteNavigation() = idleSleep.activity(IdleActivity.Navigation)

    /** Bump the saver's idle clock and tell the idle-sleep timer (throttled to
     *  one a second, see [IdleSleepRunner.activity]). Called on every touch
     *  (MainActivity's root interceptor, Initial pass, never consumes). */
    fun noteUserInteraction() {
        lastInteractionAtMs = SystemClock.elapsedRealtime()
        idleSleep.activity(IdleActivity.Touch)
    }

    /** Back in the foreground: user presence. The saver's idle clock is bumped
     *  (so it doesn't fire seconds after a long absence) and the idle-sleep
     *  timer restarts. */
    fun noteForegroundReturn() {
        lastInteractionAtMs = SystemClock.elapsedRealtime()
        idleSleep.activity(IdleActivity.Foreground)
    }

    // ── Sleep & screensaver ───────────────────────────────────────────────────

    /** A sleep the app asked for is deliberate, not idle: mark it so the
     *  Sleep state report that follows doesn't raise the screensaver (see
     *  [appAskedForSleep]). Both ends of a mirrored session (the secondary
     *  that tapped, the primary that writes) mark it. */
    fun markAppSleepRequest() {
        appSleepRequestedAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * A machine state report, before it is shown ([prevState] is the state on
     * screen until now; [mode] = a steam / hot-water / flush mode).
     * Called from the VM's `MachineStateChanged` handler, on the core lane.
     */
    fun onMachineState(prevState: MachineState?, state: MachineState, mode: Boolean) {
        // Screensaver ↔ machine-sleep coupling, both directions
        // (de1app gui.tcl:60-64; Decenza main.qml:3019-3027):
        //  • a LIVE *external* transition into Sleep/GoingToSleep (GHC
        //    tap or the machine's own timer) shows the saver — but not
        //    a sleep the app asked for ([appAskedForSleep]: the power
        //    button is a deliberate act, not an idle one, and raising
        //    the saver over it made manual sleep near-unusable — the
        //    next tap woke the machine straight back up), and not the
        //    first state report after connect (Decenza's startup grace:
        //    connecting to an already-asleep DE1 must not hijack the UI);
        //  • the machine waking EXTERNALLY while the saver is up
        //    dismisses it (no wake() needed — it's already awake);
        //  • entering any active mode counts as user presence.
        val asleep = state.isAsleep()
        val wasAsleep = prevState.isAsleep()
        // The flash's own Sleep is not the user walking away (audit L8).
        if (!firmwareHolds() && shouldRaiseSaver(prevState, state, ::appAskedForSleep)) {
            updateUi { it.copy(saverVisible = true) }
        } else if (!asleep && wasAsleep && uiState().saverVisible) {
            updateUi { it.copy(saverVisible = false) }
            lastInteractionAtMs = SystemClock.elapsedRealtime()
        }
        // The saver's idle clock only — the core's idle-sleep timer sees
        // every machine state itself.
        if (mode || state == MachineState.Espresso) lastInteractionAtMs = SystemClock.elapsedRealtime()
    }

    /** True while a Sleep state report can still be attributed to our own
     *  request. Deliberately putting the machine to sleep from the app must
     *  not raise the saver — only an *external* sleep (a GHC tap, the DE1's
     *  own sleep timer) does. Time-boxed rather than a sticky flag so a write
     *  that never lands can't swallow a later external sleep. Consuming it at
     *  the transition keeps the coupled sleep in [showSaver] a no-op (the
     *  saver is already up by then). */
    private fun appAskedForSleep(): Boolean {
        if (appSleepRequestedAtMs == 0L) return false
        val fresh = SystemClock.elapsedRealtime() - appSleepRequestedAtMs < APP_SLEEP_WINDOW_MS
        appSleepRequestedAtMs = 0L
        return fresh
    }

    /** The idle checker: every 30 s, show the saver once idle passes the
     *  threshold — never during a shot / steam / cleaning / an upload
     *  (de1app + Decenza's operation guards; a blocked trigger simply
     *  re-arms). Screen only: machine sleep is the idle-sleep timer's, which
     *  starts ticking here too. */
    fun startScreensaverClock() {
        idleSleep.start()
        scope.launch {
            while (true) {
                delay(30_000)
                val s = uiState()
                val afterMin = s.screensaverAfterMin
                if (afterMin <= 0 || s.saverVisible) continue
                val idleMs = SystemClock.elapsedRealtime() - lastInteractionAtMs
                if (idleMs < afterMin * 60_000L) continue
                if (machineBusyForSaver(s)) continue
                showSaver()
            }
        }
    }

    /** Machine states / app work that must never be interrupted by the saver. */
    private fun machineBusyForSaver(s: MainUiState): Boolean =
        firmwareHolds() || s.shotInProgress || s.profileUploading || s.guidedBrew.live ||
            s.machineStateName in setOf(
                MachineState.Espresso, MachineState.Steam, MachineState.HotWater,
                MachineState.HotWaterRinse, MachineState.Descale, MachineState.Clean,
            )

    /** Show the saver. Display only: the machine sleeps on the idle-sleep
     *  timer ("Sleep after"), whatever the saver does. */
    private fun showSaver() {
        updateUi { it.copy(saverVisible = true) }
    }

    /** Tap-to-wake: dismiss the saver, re-arm the idle clock, and — when
     *  [MainUiState.wakeMachineWithSaver] is on — wake the DE1 if we (or the
     *  GHC) put it to sleep, so one tap does both, like de1app's saver button
     *  and Decenza's ScreensaverPage.wake(). With it off the tap only clears
     *  the overlay: you can use the app (history, profiles) without heating
     *  the machine, and the power button wakes it when you mean to. */
    fun dismissSaver() {
        noteUserInteraction()
        if (!uiState().saverVisible) return
        updateUi { it.copy(saverVisible = false) }
        if (uiState().wakeMachineWithSaver &&
            de1Ready() &&
            uiState().machineStateName == MachineState.Sleep
        ) {
            wake()
        }
    }

    /** Install sleep-on-quit, once, from the VM's `init`; [clearQuitSleep]
     *  releases it in the VM's deferred teardown. */
    fun installQuitSleep() {
        // Sleep-on-quit (see [QuitSleep]): swiping the task from recents is
        // the one "I'm done" signal mere backgrounding never sends. Fired
        // from [QuitSleepService.onTaskRemoved]; every gate re-checks at
        // fire time — never mid-shot (idle only), and never from a
        // secondary (its sleep() would relay to the machine the primary is
        // still using).
        quitSleepRegistration = QuitSleep.install {
            val s = uiState()
            // "Not busy", not "exactly Idle": a machine that is heating,
            // running a calibration or simply hasn't reported a state yet is
            // still safe to sleep, and requiring MachineState.Idle silently
            // skipped those quits. The exclusions are the ones that must never
            // be cut off mid-way — the same set the screensaver refuses to
            // interrupt — plus an already-asleep machine (nothing to do).
            // ...and never a sleep the firmware would latch in Refill and
            // honour right after the next refill (firmware < 1357).
            val sleepable = !machineBusyForSaver(s) &&
                s.machineStateName != MachineState.Sleep &&
                s.machineStateName != MachineState.GoingToSleep &&
                !sleepLatchesInRefill(s)
            val primary = s.proxyRole != "secondary"
            // The idle timer may have sent Sleep moments ago (not yet
            // confirmed): don't send a second one.
            val notDoubled = quitSleepAllowed(lastIdleSleepAtMs, SystemClock.elapsedRealtime(), APP_SLEEP_WINDOW_MS)
            // Logcat, not appendLog: the in-app event log dies with the task.
            if (s.sleepOnQuit && sleepable && primary && notDoubled) {
                Log.i("QuitSleep", "quit while idle — putting the DE1 to sleep")
                markAppSleepRequest()
                quitSleepWrite()
            } else {
                Log.i(
                    "QuitSleep",
                    "quit — sleep skipped (on=${s.sleepOnQuit} state=${s.machineStateName} role=${s.proxyRole} idleSleepPending=${!notDoubled})",
                )
                null
            }
        }
    }

    /** Release this instance's sleep-on-quit registration. */
    fun clearQuitSleep() = QuitSleep.clear(quitSleepRegistration)

    // ── Companion-device presence (Android CDM) ─────────────────────────────

    /** The stored association id for a remembered address (see [coffee.crema.settings.COMPANION_ID_UNKNOWN]). */
    fun companionIdFor(address: String?): Int? {
        if (address == null || !companion.isAssociated(address)) return null
        return companion.associationId(address) ?: coffee.crema.settings.COMPANION_ID_UNKNOWN
    }

    /** Re-read the associations, restart presence observation, mirror into the UI. */
    fun refreshCompanion() {
        val cs = connectionState()
        val before = companion.associated.value
        runCatching { companion.refresh(listOf(cs.rememberedDe1Address, cs.rememberedScaleAddress)) }
        val associatedAny = companion.associated.value.isNotEmpty()
        updateUi {
            it.copy(
                companionSupported = companion.isSupported,
                de1Companion = companion.isAssociated(cs.rememberedDe1Address),
                scaleCompanion = companion.isAssociated(cs.rememberedScaleAddress),
            )
        }
        // The CDM background-FGS-start exemption applies once an association exists.
        setBackgroundStartAllowed(associatedAny)
        if (companion.associated.value != before) persistPrefs()
    }

    /** Try the one-time "nearby" setup a few seconds after a DE1 connects. */
    fun scheduleNearbySetup() {
        if (!companion.isSupported) return
        nearbySetupJob?.cancel()
        nearbySetupJob = scope.launch {
            kotlinx.coroutines.delay(NEARBY_SETUP_DELAY_MS)
            setUpNearby(explicit = false)
        }
    }

    /**
     * Part of "Keep connected in the background": set up Android's "nearby"
     * association for the remembered DE1 once, so the keep-alive service can
     * restart from the background (after a power cut) and Crema hears when the
     * DE1 is back in range. Runs when the setting is turned on ([explicit]) and
     * a few seconds after a DE1 connects while it's on, until it's set up or
     * declined (an explicit turn-on tries again after a decline).
     *
     * Android finds the DE1 by its advertisement, which a connected DE1
     * doesn't send — the old "Set up" button searched forever for that reason —
     * so a live link is paused while the system's confirm is up and resumed
     * after. Only on an idle machine, on the primary device, and never while a
     * firmware flash holds the DE1.
     */
    fun setUpNearby(explicit: Boolean) {
        if (!companion.isSupported) return
        val s = uiState()
        if (!s.keepConnectedScreenOff || s.proxyRole == "secondary" || s.companionRequest != null) return
        val address = connectionState().rememberedDe1Address ?: return
        if (companion.isAssociated(address)) return
        if (!explicit && coffee.crema.ble.companion.normalizeAddress(address) in s.companionDeclined) return
        if (firmwareHolds()) return
        if (s.bleState == De1BleManager.State.READY) {
            val idle = s.machineStateName == MachineState.Idle || s.machineStateName == MachineState.Sleep
            if (!idle) return
            appendLog("Nearby: pausing the DE1 link so Android can find it")
            nearbyPausedAddress = address
            disconnect()
        }
        notify("Android will ask to confirm the DE1 once, so Crema can reconnect in the background")
        updateUi { it.copy(companionRequest = coffee.crema.ble.companion.CompanionDevice.DE1) }
    }

    /** Reconnect the DE1 link the setup paused, if it paused one. */
    private fun resumeAfterNearby() {
        val address = nearbyPausedAddress ?: return
        nearbyPausedAddress = null
        connectTo(address)
    }

    /** The remembered address to associate for [device], or null. */
    fun companionTargetAddress(device: coffee.crema.ble.companion.CompanionDevice): String? {
        val cs = connectionState()
        return when (device) {
            coffee.crema.ble.companion.CompanionDevice.DE1 -> cs.rememberedDe1Address
            coffee.crema.ble.companion.CompanionDevice.SCALE -> cs.rememberedScaleAddress
        }
    }

    /** The system dialog created the association. */
    fun onCompanionAssociated(device: coffee.crema.ble.companion.CompanionDevice, address: String, id: Int?) {
        val observing = companion.onAssociated(address, id)
        updateUi { it.copy(companionRequest = null) }
        refreshCompanion()
        resumeAfterNearby()
        val name = if (device == coffee.crema.ble.companion.CompanionDevice.DE1) "the DE1" else "the scale"
        notify(
            if (observing) "Crema can now reconnect to $name in the background"
            else "Associated $name, but Android wouldn't watch for it — reconnecting as before",
        )
    }

    /** The association didn't happen (cancelled, not found, unsupported):
     *  reconnect a paused link, and don't ask again on connect (turning
     *  "Keep connected in the background" on again retries). */
    fun onCompanionSetupEnded(reason: String?) {
        val address = connectionState().rememberedDe1Address?.let { coffee.crema.ble.companion.normalizeAddress(it) }
        updateUi {
            it.copy(
                companionRequest = null,
                companionDeclined = if (address == null) it.companionDeclined else (it.companionDeclined + address).distinct(),
            )
        }
        persistPrefs()
        resumeAfterNearby()
        reason?.let { appendLog("Companion setup: $it") }
    }

    /** Settings → "Remove": stop watching for the device and drop the association. */
    fun removeCompanion(device: coffee.crema.ble.companion.CompanionDevice) {
        companionTargetAddress(device)?.let { companion.remove(it) }
        refreshCompanion()
    }
}
