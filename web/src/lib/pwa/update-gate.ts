/**
 * `#lib/pwa/update-gate.js` — when it is safe to swap in a new build.
 *
 * Applying a service-worker update reloads the page, which drops the DE1 and
 * scale links and discards anything in flight. So the offer waits until nothing
 * that a reload would interrupt is running. Pure, for the tests; the shell
 * feeds it from the UI snapshot and the stores ({@link ReloadInputs}).
 */

import type { MachineState } from '#lib/core/crema-core.js';

/** What the shell knows about work a reload would interrupt. */
export interface ReloadInputs {
	/** The DE1 link is up (`de1State === 'ready'`). */
	readonly connected: boolean;
	/** The last machine state the DE1 reported; `null` until the first read. */
	readonly machineStateName: MachineState | null;
	/** A shot is being pulled (`UiSnapshot.shotInProgress`). */
	readonly shotInProgress: boolean;
	/** A guided brew (pourover, AeroPress, …) is mid-session with a live timer. */
	readonly guidedBrewLive: boolean;
	/** A firmware update is being transferred or applied. */
	readonly firmwareBusy: boolean;
}

/**
 * Machine states in which a reload interrupts nothing: the DE1 is parked.
 * Everything else — espresso, steam, hot water, rinses, descale, calibration,
 * the transient `Busy` — is treated as busy, as is an unknown state while the
 * link is up (the state is still being read).
 */
const PARKED_STATES: ReadonlySet<string> = new Set(['Idle', 'Sleep', 'GoingToSleep', 'Refill']);

/** True when applying an update now would interrupt nothing. */
export function canReloadNow(inputs: ReloadInputs): boolean {
	if (inputs.shotInProgress || inputs.guidedBrewLive || inputs.firmwareBusy) return false;
	if (!inputs.connected) return true;
	return inputs.machineStateName !== null && PARKED_STATES.has(inputs.machineStateName);
}
