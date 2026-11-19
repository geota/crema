import { describe, expect, it } from 'vitest';

import { MachineState, SubState } from '#lib/core/crema-core.js';
import { applyEvent, INITIAL_SNAPSHOT, substateLabel } from './ui-state.svelte';

/**
 * Substate 0x11 is the refill kit giving up on a refill (decaid `ac3e433d`),
 * not a refill in progress: the status line says so, and the raw name stays
 * for comparisons.
 */
describe('refill-stopped substate', () => {
	it('reads as "Refill stopped" in the status line', () => {
		const s = applyEvent(INITIAL_SNAPSHOT, {
			type: 'MachineStateChanged',
			content: { state: MachineState.Idle, substate: SubState.RefillStopped }
		});
		expect(s.machineState).toBe('Idle / Refill stopped');
		expect(s.machineSubstate).toBe(SubState.RefillStopped);
	});

	it('leaves every other substate name as it is', () => {
		expect(substateLabel(SubState.Heating)).toBe('Heating');
		expect(substateLabel(SubState.Ready)).toBe('Ready');
	});
});
