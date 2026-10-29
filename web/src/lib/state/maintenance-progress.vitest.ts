import { describe, expect, it } from 'vitest';

import { MachineState, MaintenanceFailure, MaintenancePhase } from '#lib/core/crema-core.js';
import {
	applyEvent,
	INITIAL_SNAPSHOT,
	isMaintenanceTerminal,
	maintenanceFailedMessage,
	maintenanceProgressText,
	maintenanceTitle,
	type MaintenanceView
} from './ui-state.svelte';

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

	it('clears on Finished, Cancelled and Failed', () => {
		const running = applyEvent(INITIAL_SNAPSHOT, progress({ step_index: 1 }));
		for (const phase of [
			MaintenancePhase.Finished,
			MaintenancePhase.Cancelled,
			MaintenancePhase.Failed
		]) {
			expect(applyEvent(running, progress({ phase })).maintenance).toBeNull();
		}
	});

	it('a cold cycle whose profile never loaded clears the held row (V3 #4)', () => {
		const held = applyEvent(
			INITIAL_SNAPSHOT,
			progress({ phase: MaintenancePhase.WaitingForPreheat, cold_workaround: true })
		);
		const failed = applyEvent(held, progress({ phase: MaintenancePhase.Failed, cold_workaround: true }));
		expect(failed.maintenance).toBeNull();
		expect(failed.eventLog[0]?.text).toContain('Failed');
		expect(isMaintenanceTerminal(MaintenancePhase.Failed)).toBe(true);
		expect(maintenanceFailedMessage(MachineState.Descale, null)).toMatch(/^Descale didn't start/);
		expect(maintenanceFailedMessage(MachineState.Clean, null)).toMatch(/^Clean cycle didn't start/);
	});

	it('words the notice by why the cycle never started (C1 P3-1)', () => {
		// The core sends `null` for an absent reason; an older core sends none.
		for (const absent of [null, undefined, MaintenanceFailure.ColdProfileNotLoaded]) {
			expect(maintenanceFailedMessage(MachineState.Descale, absent)).toContain(
				'cold-start profile older DE1 firmware needs could not be loaded'
			);
		}
		const replaced = maintenanceFailedMessage(
			MachineState.AirPurge,
			MaintenanceFailure.ProfileReplaced
		);
		expect(replaced).toBe(
			"Air purge didn't start: another profile was loaded before the cycle could start. Start it again when you're ready."
		);
		expect(replaced).not.toContain('could not be loaded');
	});

	it('names the cycle once for the progress row and the notice (C3 #11)', () => {
		expect(maintenanceTitle(MachineState.Descale)).toBe('Descale');
		expect(maintenanceTitle(MachineState.Clean)).toBe('Clean cycle');
		expect(maintenanceTitle(MachineState.AirPurge)).toBe('Air purge');
		expect(maintenanceFailedMessage(MachineState.AirPurge, null)).toMatch(/^Air purge /);
	});

	it('reads the progress row like Android maintenanceRunText', () => {
		const view = (over: Partial<MaintenanceView>) => progress(over).content;
		expect(
			maintenanceProgressText(
				view({ step_index: 4, progress: 0.42, seconds_remaining: 420, cycle: 1 })
			)
		).toBe('42% · Step 4 of 5 · 7 min 0 s left');
		expect(
			maintenanceProgressText(
				view({ step_index: 2, progress: 0.1, seconds_remaining: 650, cycle: 2 })
			)
		).toBe('10% · Step 2 of 5 · 10 min 50 s left · pass 2');
		expect(maintenanceProgressText(view({ phase: MaintenancePhase.Requested }))).toBe('Starting…');
		expect(maintenanceProgressText(view({ state: MachineState.Clean, step_count: 0 }))).toBe(
			'Running…'
		);
		expect(
			maintenanceProgressText(view({ phase: MaintenancePhase.WaitingForPreheat, cold_workaround: true }))
		).toBe('Loading the cold-start profile and waiting for heating to stop (older firmware workaround)…');
	});

	it('logs phase changes, not every countdown second', () => {
		const a = applyEvent(INITIAL_SNAPSHOT, progress({ step_index: 1, seconds_remaining: 700 }));
		const b = applyEvent(a, progress({ step_index: 1, seconds_remaining: 699 }));
		expect(b.eventLog.length).toBe(a.eventLog.length);
	});
});
