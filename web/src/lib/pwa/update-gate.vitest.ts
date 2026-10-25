import { describe, expect, it } from 'vitest';
import { MachineState } from '#lib/core/crema-core.js';
import { canReloadNow, type ReloadInputs } from './update-gate.js';

const parked: ReloadInputs = {
	connected: true,
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
		expect(canReloadNow({ ...parked, connected: false, machineStateName: null })).toBe(true);
	});

	it('refuses while the DE1 is doing anything', () => {
		for (const s of [MachineState.Espresso, MachineState.Steam, MachineState.HotWater, MachineState.HotWaterRinse, MachineState.Descale, MachineState.Busy]) {
			expect(canReloadNow({ ...parked, machineStateName: s })).toBe(false);
		}
	});

	it('refuses while connected but the state is still unknown', () => {
		expect(canReloadNow({ ...parked, machineStateName: null })).toBe(false);
	});

	it('refuses during a shot, a guided brew or a firmware update even when the state looks parked', () => {
		expect(canReloadNow({ ...parked, shotInProgress: true })).toBe(false);
		expect(canReloadNow({ ...parked, connected: false, guidedBrewLive: true })).toBe(false);
		expect(canReloadNow({ ...parked, firmwareBusy: true })).toBe(false);
	});
});
