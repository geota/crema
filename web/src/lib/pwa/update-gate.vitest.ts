import { describe, expect, it } from 'vitest';
import { MachineState } from '#lib/core/crema-core.js';
import { canReloadNow, reloadLinkOf, type ReloadInputs } from './update-gate.js';

const parked: ReloadInputs = {
	link: 'up',
	machineStateName: MachineState.Idle,
	shotInProgress: false,
	guidedBrewLive: false,
	firmwareBusy: false
};

describe('canReloadNow', () => {
	it('allows a reload with the DE1 idle and nothing running', () => {
		expect(canReloadNow(parked)).toBe(true);
		expect(canReloadNow({ ...parked, machineStateName: MachineState.Sleep })).toBe(true);
	});

	it('allows a reload with no machine connected', () => {
		expect(canReloadNow({ ...parked, link: 'down', machineStateName: null })).toBe(true);
	});

	it('refuses while the DE1 is doing anything', () => {
		for (const s of [MachineState.Espresso, MachineState.Steam, MachineState.HotWater, MachineState.HotWaterRinse, MachineState.Descale, MachineState.Busy]) {
			expect(canReloadNow({ ...parked, machineStateName: s })).toBe(false);
		}
	});

	it('refuses while connected but the state is still unknown', () => {
		expect(canReloadNow({ ...parked, machineStateName: null })).toBe(false);
	});

	it('refuses while the link is coming back, whatever the last state was (cohort review 06 #11)', () => {
		expect(canReloadNow({ ...parked, link: 'transient', machineStateName: null })).toBe(false);
		expect(canReloadNow({ ...parked, link: 'transient' })).toBe(false);
	});

	it('maps reconnecting, subscribing and connecting to a transient link', () => {
		expect(reloadLinkOf('ready')).toBe('up');
		for (const s of ['reconnecting', 'subscribing', 'connecting'] as const) {
			expect(reloadLinkOf(s)).toBe('transient');
		}
		for (const s of ['idle', 'disconnected', 'failed', null, undefined] as const) {
			expect(reloadLinkOf(s)).toBe('down');
		}
	});

	it('refuses during a shot, a guided brew or a firmware update even when the state looks parked', () => {
		expect(canReloadNow({ ...parked, shotInProgress: true })).toBe(false);
		expect(canReloadNow({ ...parked, link: 'down', guidedBrewLive: true })).toBe(false);
		expect(canReloadNow({ ...parked, firmwareBusy: true })).toBe(false);
	});
});
