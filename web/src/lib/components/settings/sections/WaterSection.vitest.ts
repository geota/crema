/**
 * F16a: the Run-now buttons follow the core's rule — a maintenance cycle only
 * with the DE1 in Idle or Sleep (`maintenanceRequestAllowed`, the rule
 * `request_machine_state` enforces).
 */

import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import { INITIAL_SNAPSHOT } from '#lib/state/index.js';
import { MachineState } from '#lib/core/crema-core.js';
import type { CremaApp } from '#lib/state/index.js';
import WaterSection from './WaterSection.svelte';

describe('WaterSection Run-now gate', () => {
	let component: ReturnType<typeof mount> | null = null;

	beforeAll(async () => {
		await initTestWasm();
	});

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function runButtons(machineStateName: MachineState | null): boolean[] {
		const app = {
			state: { current: { ...INITIAL_SNAPSHOT, de1State: 'ready', machineStateName } }
		} as unknown as CremaApp;
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(WaterSection, { target, props: { app } });
		flushSync();
		const buttons = ([...target.querySelectorAll('button.st-maint-action')] as HTMLButtonElement[]).filter(
			(b) => b.textContent?.trim() === 'Run'
		);
		const enabled = buttons.map((b) => !b.disabled);
		unmount(component);
		component = null;
		document.body.innerHTML = '';
		return enabled;
	}

	it('enables Run in Idle and Sleep, and nowhere else', () => {
		expect(runButtons(MachineState.Idle).every(Boolean)).toBe(true);
		expect(runButtons(MachineState.Sleep).every(Boolean)).toBe(true);
		for (const state of [MachineState.Espresso, MachineState.Steam, MachineState.Descale, null]) {
			const enabled = runButtons(state);
			expect(enabled.length, String(state)).toBeGreaterThan(0);
			expect(enabled.some(Boolean), String(state)).toBe(false);
		}
	});
});
