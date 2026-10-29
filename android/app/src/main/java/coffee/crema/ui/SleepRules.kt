package coffee.crema.ui

import coffee.crema.core.MmrRegister
import coffee.crema.core.MachineState

/** Sleep and its GoingToSleep run-up both count as "the machine is down". */
internal fun MachineState?.isAsleep(): Boolean =
    this == MachineState.Sleep || this == MachineState.GoingToSleep

/**
 * Does this machine-state report raise the screensaver?
 *
 * Only an *external* sleep does — a GHC tap, or the DE1's own sleep timer.
 * Three things must not:
 *  • a sleep the app itself asked for ([appAskedForSleep], the power button):
 *    it's a deliberate act, not an idle one. Raising the saver over it made
 *    manual sleep near-unusable — the next tap dismissed the saver and woke
 *    the machine straight back up;
 *  • the first report after connect ([prev] == null): connecting to an
 *    already-asleep DE1 must not hijack the UI (Decenza's startup grace);
 *  • a report that only re-states a sleep we're already in.
 *
 * [appAskedForSleep] is a lambda, and called last, because it *consumes* the
 * marker: it may only be spent on a real awake→asleep transition, never on
 * the unrelated state reports that can land between the write and the sleep.
 * Top-level + `internal` so the decision is pure and unit-testable without a
 * DE1 (the BLE round-trip that drives it isn't).
 */
internal fun shouldRaiseSaver(
    prev: MachineState?,
    next: MachineState,
    appAskedForSleep: () -> Boolean,
): Boolean {
    if (prev == null || !next.isAsleep() || prev.isAsleep()) return false
    return !appAskedForSleep()
}

/**
 * Whether an AUTOMATIC sleep request (screensaver coupling, sleep-on-quit)
 * would be latched by the DE1 and fire right after the user refills the tank
 * instead of sleeping the machine now — firmware < 1357 (or unknown) in
 * Refill, or any Bengle in Refill (decaid 7a0b0c28 / f8cd9553). The rule
 * lives in core ([coffee.crema.core.sleepRequestLatchesInRefill]); [latches]
 * is injectable so the wiring is testable on the JVM without the native lib.
 */
internal fun sleepLatchesInRefill(s: MainUiState): Boolean =
    sleepLatchesInRefill(s.machineStateName, s.machineSubstate, s.de1MachineInfo)

/** [sleepLatchesInRefill] over the raw UI fields — the testable seam. */
internal fun sleepLatchesInRefill(
    state: MachineState?,
    substate: String?,
    machineInfo: Map<MmrRegister, UInt>,
    latches: (String, String, UInt?, UInt?) -> Boolean = { st, sub, fw, model ->
        coffee.crema.core.sleepRequestLatchesInRefill(st, sub, fw, model)
    },
): Boolean {
    if (state == null) return false
    return latches(
        state.string,
        substate ?: "",
        machineInfo[MmrRegister.FirmwareVersion],
        machineInfo[MmrRegister.MachineModel],
    )
}

/**
 * True when [new] differs from [old] (the last PUBLISHED state) only in the
 * high-rate live readouts while the screensaver is up in both — a change
 * nothing on screen can show. Used to keep [MainViewModel.ui] quiet behind
 * the saver. Pure + `internal` for unit tests.
 */
internal fun hiddenBySaver(old: MainUiState, new: MainUiState): Boolean =
    old.saverVisible && new.saverVisible && old.withoutLiveReadouts() == new.withoutLiveReadouts()

/** [this] with every telemetry-rate field blanked (DE1 samples, scale
 *  readings, water level, mode timers, the debug log). */
private fun MainUiState.withoutLiveReadouts(): MainUiState = copy(
    telemetry = null,
    pressure = null,
    flow = null,
    headTemp = null,
    mixTemp = null,
    steamTemp = null,
    dispensedVolume = null,
    resistance = null,
    resistanceWeight = null,
    shotElapsedMs = 0,
    shotTelemetry = emptyList(),
    scaleWeightG = null,
    scaleFlowGPerS = null,
    scaleTimerMs = null,
    waterLevelMm = null,
    modeElapsedMs = 0,
    eventLog = emptyList(),
)
