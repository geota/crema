/**
 * The Brew screen's phone branch. It may rearrange the screen, not change what
 * reaches the machine: the chips and Coffee / Stop run the wide layout's
 * handlers, under the same enablement rules.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { MachineState } from '#lib/core/crema-core.js';
import { getProfileStore } from '#lib/profiles/index.js';
import { getCremaUiState } from '#lib/state/ui-state.svelte.js';
import type { CremaApp, UiSnapshot } from '#lib/state/index.js';
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import { phoneBarConfig } from '#lib/shell/phone-bar.svelte.js';
import BrewDashboard from './BrewDashboard.svelte';

vi.mock('#lib/shell/phone.svelte.js', () => ({ phoneLayout: { current: true } }));

describe('BrewDashboard at phone width', () => {
	let component: ReturnType<typeof mount> | null = null;

	beforeEach(async () => {
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		profiles.setActive(profiles.all[0].id);
	});

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	async function render(patch: Partial<UiSnapshot> = {}, withApp = true) {
		const ui = { ...getCremaUiState().current, ...patch } as UiSnapshot;
		const requestMachineState = vi.fn();
		const startShot = vi.fn();
		const stopShot = vi.fn();
		const app = withApp
			? ({
					brewOverridesFor: () => ({}),
					setBrewOverrides: vi.fn(),
					state: getCremaUiState(),
					requestMachineState,
					setBrewParamsSnapshot: vi.fn(),
					startShot,
					stopShot
				} as unknown as CremaApp)
			: null;
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(WithAppContext, {
			target,
			props: { app, component: BrewDashboard, props: { ui } }
		});
		flushSync();
		await tick();
		return { target, requestMachineState, startShot, stopShot };
	}
	const chip = (root: Element, label: string): HTMLButtonElement =>
		root.querySelector<HTMLButtonElement>(`.mc-chip[aria-label$=" ${label}"]`)!;
	const ready = { de1State: 'ready', machineStateName: MachineState.Idle } as Partial<UiSnapshot>;

	it('renders Android\'s three chips, with the label Water', async () => {
		const { target } = await render(ready);
		expect([...target.querySelectorAll('.mc-chip')].map((c) => c.querySelector('span span')?.textContent)).toEqual([
			'Steam',
			'Water',
			'Flush'
		]);
		expect(target.querySelector('.crema-dash')).toBeNull();
		expect(chip(target, 'Flush').textContent).toContain('s purge');
	});

	it('the chips request the same machine states as the wide layout', async () => {
		const { target, requestMachineState } = await render(ready);
		chip(target, 'Steam').click();
		chip(target, 'Water').click();
		chip(target, 'Flush').click();
		expect(requestMachineState.mock.calls.map((c) => c[0])).toEqual([
			MachineState.Steam,
			MachineState.HotWater,
			MachineState.HotWaterRinse
		]);
	});

	it('an active chip cancels back to Idle and is marked active', async () => {
		const { target, requestMachineState } = await render({ ...ready, machineStateName: MachineState.Steam });
		const steam = chip(target, 'Steam');
		expect(steam.getAttribute('aria-label')).toBe('Cancel Steam');
		expect(steam.classList.contains('is-active')).toBe(true);
		steam.click();
		expect(requestMachineState).toHaveBeenCalledWith(MachineState.Idle);
	});

	it('chips and Coffee are disabled until the DE1 is ready', async () => {
		const { target, startShot } = await render({ de1State: 'disconnected' } as Partial<UiSnapshot>);
		expect(chip(target, 'Steam').disabled).toBe(true);
		const coffee = target.querySelector<HTMLButtonElement>('.crema-bigbtn')!;
		expect(coffee.disabled).toBe(true);
		coffee.click();
		expect(startShot).not.toHaveBeenCalled();
	});

	it('a cold heater marks the chip but leaves it pressable', async () => {
		const { target, requestMachineState } = await render(ready);
		const steam = chip(target, 'Steam');
		expect(steam.classList.contains('is-cold')).toBe(true);
		expect(steam.disabled).toBe(false);
		steam.click();
		expect(requestMachineState).toHaveBeenCalledWith(MachineState.Steam);
	});

	it('Coffee starts a shot, and Stop stops it', async () => {
		const idle = await render(ready);
		idle.target.querySelector<HTMLButtonElement>('.crema-bigbtn')!.click();
		await vi.waitFor(() => expect(idle.startShot).toHaveBeenCalledOnce());
		unmount(component!);
		component = null;

		const running = await render({
			...ready,
			machineStateName: MachineState.Espresso,
			shotInProgress: true
		});
		const stop = running.target.querySelector<HTMLButtonElement>('.crema-bigbtn')!;
		expect(stop.textContent).toContain('Stop');
		stop.click();
		await vi.waitFor(() => expect(running.stopShot).toHaveBeenCalledOnce());
	});

	it('shows a dash on the chips before the app has loaded', async () => {
		const { target } = await render({}, false);
		for (const label of ['Steam', 'Water', 'Flush']) {
			expect(chip(target, label).querySelector('.mc-chip-sub')?.textContent).toBe('—');
		}
	});

	it('swaps the Ready card for the live timer while a shot runs', async () => {
		const idle = await render(ready);
		expect(idle.target.textContent).toContain('Ready to brew');
		expect(idle.target.textContent).not.toContain('Extraction');
		unmount(component!);
		component = null;

		const running = await render({
			...ready,
			machineStateName: MachineState.Espresso,
			shotInProgress: true,
			shotElapsed: 12_300
		});
		expect(running.target.querySelector('.ptimer-clock')?.textContent).toBe('00:12.3');
		expect(running.target.textContent).not.toContain('Ready to brew');
	});

	it('claims the top bar with Quick Controls and the overflow around the shared actions', async () => {
		await render(ready);
		expect(phoneBarConfig()?.actions?.map((a) => (typeof a === 'string' ? a : a.id))).toEqual([
			'quick',
			'devices',
			'settings',
			'more'
		]);
	});
});
