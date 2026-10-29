/**
 * `#lib/pwa/update-gate.js` — when it is safe to swap in a new build.
 *
 * Applying a service-worker update reloads the page, which drops the DE1 and
 * scale links and discards anything in flight. So the offer waits until nothing
 * that a reload would interrupt is running. Pure, for the tests; the shell
 * feeds it from the UI snapshot and the stores ({@link ReloadInputs}).
 */

import type { MachineState } from '#lib/core/crema-core.js';
import type { BleConnectionState } from '#lib/ble/connection-state.js';

/**
 * The DE1 link as the gate sees it: `up` (ready), `transient` (connecting,
 * subscribing during a (re)seed, or reconnecting after a drop — the machine
 * may be mid-steam or mid-descale behind a blipping link, and its state is
 * unknown), or `down` (no DE1, or a link that ended).
 */
export type ReloadLink = 'up' | 'transient' | 'down';

/** Map the DE1 connection state onto {@link ReloadLink}. */
export function reloadLinkOf(state: BleConnectionState | null | undefined): ReloadLink {
	switch (state) {
		case 'ready':
			return 'up';
		case 'connecting':
		case 'subscribing':
		case 'reconnecting':
			return 'transient';
		default:
			return 'down';
	}
}

/** What the shell knows about work a reload would interrupt. */
export interface ReloadInputs {
	/** The DE1 link ({@link reloadLinkOf} of `de1State`). */
	readonly link: ReloadLink;
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
	// A link that is coming back is not "no machine": wait until it settles.
	if (inputs.link === 'transient') return false;
	if (inputs.link === 'down') return true;
	return inputs.machineStateName !== null && PARKED_STATES.has(inputs.machineStateName);
}

/** The slice of the UI snapshot {@link reloadInputsOf} reads. */
export interface ReloadSnapshot {
	readonly de1State?: BleConnectionState | null;
	readonly machineStateName?: MachineState | null;
	readonly shotInProgress?: boolean;
}

/**
 * Assemble {@link ReloadInputs} from the shell's live state — one place for
 * both reload offers (the service-worker update and the engine-reset notice),
 * so they gate on the same things.
 */
export function reloadInputsOf(
	snapshot: ReloadSnapshot | null | undefined,
	guidedBrewPhase: string,
	firmwareRunning: boolean
): ReloadInputs {
	return {
		link: reloadLinkOf(snapshot?.de1State),
		machineStateName: snapshot?.machineStateName ?? null,
		shotInProgress: snapshot?.shotInProgress ?? false,
		guidedBrewLive:
			guidedBrewPhase === 'armed' || guidedBrewPhase === 'running' || guidedBrewPhase === 'paused',
		firmwareBusy: firmwareRunning
	};
}
