import { describe, expect, it } from 'vitest';

import { MachineState, MaintenancePhase } from '$lib/core/crema-core';
import { applyEvent, INITIAL_SNAPSHOT, type MaintenanceView } from './ui-state.svelte';

function progress(over: Partial<MaintenanceView>) {
	return {
		type: 'MaintenanceProgress' as const,
		content: {
			state: MachineState.Descale,
			phase: MaintenancePhase.Running,
			step_index: 0,
			step_count: 5,
			progress: 0,
			seconds_remaining: 0,
			cycle: 0,
			cold_workaround: false,
			...over
		}
	};
}

/**
 * The core follows a maintenance cycle and reports it as
 * `MaintenanceProgress`; the snapshot keeps the live reading for Settings →
 * Water and drops it once the cycle ends.
 */
describe('maintenance progress fold', () => {
	it('holds the live reading while the cycle runs', () => {
		const s = applyEvent(
			INITIAL_SNAPSHOT,
			progress({ step_index: 4, progress: 0.42, seconds_remaining: 420, cycle: 1 })
		);
		expect(s.maintenance?.step_index).toBe(4);
		expect(s.maintenance?.seconds_remaining).toBe(420);
	});

	it('shows a cold request held for preheat', () => {
		const s = applyEvent(
			INITIAL_SNAPSHOT,
			progress({ phase: MaintenancePhase.WaitingForPreheat, cold_workaround: true })
		);
		expect(s.maintenance?.phase).toBe(MaintenancePhase.WaitingForPreheat);
		expect(s.eventLog[0]?.text).toContain('cold-start workaround');
	});

	it('clears on Finished and Cancelled', () => {
		const running = applyEvent(INITIAL_SNAPSHOT, progress({ step_index: 1 }));
		for (const phase of [MaintenancePhase.Finished, MaintenancePhase.Cancelled]) {
			expect(applyEvent(running, progress({ phase })).maintenance).toBeNull();
		}
	});

	it('logs phase changes, not every countdown second', () => {
		const a = applyEvent(INITIAL_SNAPSHOT, progress({ step_index: 1, seconds_remaining: 700 }));
		const b = applyEvent(a, progress({ step_index: 1, seconds_remaining: 699 }));
		expect(b.eventLog.length).toBe(a.eventLog.length);
	});
});
