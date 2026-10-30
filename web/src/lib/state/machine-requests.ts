/**
 * Words for the core's machine-request events (F16): a request refused by the
 * Run-now gate, one waiting out the profile-download guard, and one dropped
 * because the machine moved on or the profile took too long.
 */

import type {
	MachineRequestDrop,
	MachineRequestRefusal,
	MachineState
} from '#lib/core/crema-core.js';

/** One sentence the user can act on, for `MachineRequestRefused`. */
export function machineRequestRefusalText(state: MachineState, reason: MachineRequestRefusal): string {
	switch (reason.kind) {
		case 'MachineNotIdle':
			return `${state} needs the DE1 idle or asleep (it is in ${reason.details.state}).`;
		case 'MachineStateUnknown':
			return `Wait for the DE1 to report its state, then start ${state} again.`;
		default:
			return `${state} was not started.`;
	}
}

/** Shown while a request the user made waits for the profile to load. */
export const REQUEST_HELD_TEXT = 'Waiting for the profile to load…';

/** One sentence for `MachineRequestDropped`: why the request never went out. */
export function machineRequestDropText(state: MachineState, reason: MachineRequestDrop): string {
	switch (reason.kind) {
		case 'StateChanged':
			return reason.details.to === 'Espresso'
				? `${state} cancelled: a shot started`
				: `${state} cancelled: the machine moved on (it is in ${reason.details.to})`;
		case 'TookTooLong':
			return `${state} cancelled: the profile is still loading — try again`;
		default:
			return `${state} cancelled.`;
	}
}
