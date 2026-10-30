/**
 * Words for the core's machine-request events (F16): a request refused by the
 * Run-now gate, and one waiting out the profile-download guard.
 */

import type { MachineRequestRefusal, MachineState } from '#lib/core/crema-core.js';

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
