/**
 * The Hot water card (hot-water modes spec §2.7, AC-HW20 / AC-HW21): the
 * readout copy and its short form, the mode in the label with its Time /
 * Volume / Ratio menu setting the persisted dose, the ratio chips, and the
 * secondary line following the core's target — on a profile change with
 * nothing connected, without a settings write.
 */

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import WaterStepper from './WaterStepper.svelte';
import { BrewParamState } from './brew-params.svelte';
import {
	hotWaterReadout,
	hotWaterRestingAmount,
	hotWaterRunning,
	hotWaterSub,
	hotWaterTargetSeconds
} from '#lib/brew/hot-water.js';
import {
	HotWaterBasis,
	HotWaterMode,
	type HotWaterDose,
	type HotWaterTarget
} from '#lib/core/crema-core.js';
import { getSettingsStore } from '#lib/settings/store.svelte.js';
import { getCremaUiState, refreshStopTargetsProjection } from '#lib/state/ui-state.svelte.js';
import { resolveHotWaterJson } from '#lib/wasm/de1_wasm.js';

beforeAll(async () => {
	await initTestWasm();
});

const ML = { weightUnit: 'g', volumeUnit: 'ml' } as const;

function dose(over: Partial<HotWaterDose>): HotWaterDose {
	return { mode: HotWaterMode.Ratio, timeS: 30, timeNoLimit: false, volumeMl: 150, ratio: 3, ...over };
}

/** The core's target for a dose and a context (the shipped firmware). */
function resolve(d: HotWaterDose, targetYieldG?: number, scaleOperational = false): HotWaterTarget {
	return JSON.parse(
		resolveHotWaterJson(JSON.stringify(d), JSON.stringify({ targetYieldG, scaleOperational }))
	) as HotWaterTarget;
}

describe('hot-water readout copy', () => {
	it('ratio, no target, capped and floored', () => {
		expect(hotWaterReadout(resolve(dose({}), 50), ML, 3)).toBe('1:3 → 150 ml for a 50 g shot');
		expect(hotWaterReadout(resolve(dose({}), undefined), ML, 3)).toBe('No yield target · 150 ml');
		expect(hotWaterReadout(resolve(dose({}), 100), ML, 3)).toBe(
			'1:3 → 250 ml (capped; 1:3 of 100 g is 300 ml)'
		);
		expect(hotWaterReadout(resolve(dose({ ratio: 0.5 }), 18), ML, 0.5)).toBe(
			'1:0.5 → 20 ml (raised to the 20 ml minimum)'
		);
		expect(hotWaterReadout(resolve(dose({ ratio: 2.5 }), 36), ML, 2.5)).toBe(
			'1:2.5 → 90 ml for a 36 g shot'
		);
	});

	it('volume: by weight with an operational scale, nothing without', () => {
		const volume = dose({ mode: HotWaterMode.Volume, volumeMl: 200 });
		expect(hotWaterReadout(resolve(volume, 36, true), ML, 3)).toBe('Stops by weight');
		expect(hotWaterReadout(resolve(volume, 36, false), ML, 3)).toBe('');
	});

	it('time: the stop, a large pour, the fallback firmware and No limit', () => {
		expect(hotWaterReadout(resolve(dose({ mode: HotWaterMode.Time, timeS: 30 })), ML, 3)).toBe('Stops at 30 s');
		expect(hotWaterReadout(resolve(dose({ mode: HotWaterMode.Time, timeS: 200 })), ML, 3)).toBe(
			'Stops at 200 s — a large pour, also from the group-head button'
		);
		const time = (over: Partial<HotWaterTarget>): HotWaterTarget => ({
			mode: HotWaterMode.Time,
			timeS: 20,
			basis: HotWaterBasis.NotApplicable,
			wireVolumeMl: 0,
			wireTimeoutS: 20,
			...over
		});
		// Volume 0 doesn't stop the DE1's volume stop: a 255 ml backstop.
		expect(hotWaterReadout(time({ wireVolumeMl: 255 }), ML, 3)).toBe('Stops at 20 s, or about 255 ml');
		expect(hotWaterReadout(time({ timeS: undefined, wireTimeoutS: 0 }), ML, 3)).toBe(
			'Pours until you stop it, also from the group-head button'
		);
	});

	it('the short form under the digits', () => {
		expect(hotWaterSub(resolve(dose({}), 36), ML)).toEqual({ short: '→ 108 ml', warn: false });
		expect(hotWaterSub(resolve(dose({}), 100), ML)).toEqual({ short: 'max 250 ml', warn: true });
		expect(hotWaterSub(resolve(dose({ ratio: 0.5 }), 18), ML)).toEqual({ short: 'min 20 ml', warn: true });
		expect(hotWaterSub(resolve(dose({}), undefined), ML)).toEqual({ short: '150 ml', warn: true });
		const volume = dose({ mode: HotWaterMode.Volume, volumeMl: 200 });
		expect(hotWaterSub(resolve(volume, 36, true), ML)).toEqual({ short: 'by weight', warn: false });
		expect(hotWaterSub(resolve(volume, 36, false), ML)).toBeNull();
		expect(hotWaterSub(resolve(dose({ mode: HotWaterMode.Time, timeS: 30 })), ML)).toBeNull();
		expect(hotWaterSub(resolve(dose({ mode: HotWaterMode.Time, timeS: 200 })), ML)).toEqual({
			short: 'large pour',
			warn: true
		});
		const time = (over: Partial<HotWaterTarget>): HotWaterTarget => ({
			mode: HotWaterMode.Time,
			timeS: 20,
			basis: HotWaterBasis.NotApplicable,
			wireVolumeMl: 0,
			wireTimeoutS: 20,
			...over
		});
		expect(hotWaterSub(time({ wireVolumeMl: 255 }), ML)).toEqual({ short: '≤ 255 ml', warn: false });
		expect(hotWaterSub(time({ timeS: undefined, wireTimeoutS: 0 }), ML)).toEqual({
			short: 'until stopped',
			warn: true
		});
	});

	it('the chip amount and the running counter', () => {
		const ratio = resolve(dose({}), 50);
		const timed = resolve(dose({ mode: HotWaterMode.Time, timeS: 30 }));
		expect(hotWaterRestingAmount(ratio, ML)).toBe('150 ml');
		expect(hotWaterRestingAmount(timed, ML)).toBe('30 s');
		expect(hotWaterRunning(ratio, 12.34, ML)).toBe('12.3 s · 150 ml');
		expect(hotWaterRunning(timed, 12.34, ML)).toBe('12.3 / 30.0 s');
		expect(hotWaterTargetSeconds(timed)).toBe(30);
		expect(hotWaterTargetSeconds(ratio)).toBe(0);
		expect(hotWaterRestingAmount(ratio, { weightUnit: 'oz', volumeUnit: 'floz' })).toBe('5.1 fl oz');
	});
});

describe('the Hot water card', () => {
	let component: ReturnType<typeof mount> | null = null;
	const push = vi.fn(async () => undefined);

	beforeEach(() => {
		localStorage.clear();
		const s = getSettingsStore();
		s.setHotWaterDose(dose({}));
		push.mockClear();
	});

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function render(): HTMLElement {
		const target = document.createElement('div');
		document.body.appendChild(target);
		const params = new BrewParamState(() => ({ dose: 18, yield: 36, brewTemp: 93, preinf: 8 }));
		component = mount(WithAppContext, {
			target,
			props: { app: { pushSteamHotwater: push } as never, component: WaterStepper, props: { params } }
		});
		flushSync();
		return target;
	}

	/** The label's second option, naming the current mode. */
	const modeOption = (root: HTMLElement) => root.querySelectorAll<HTMLButtonElement>('.qsplit-opt')[1];

	/** Open the mode menu (the option is already active) and pick `name`. */
	function pick(root: HTMLElement, name: string): void {
		modeOption(root).click();
		flushSync();
		const item = [...root.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]')].find((b) =>
			b.textContent?.includes(name)
		);
		item!.click();
		flushSync();
	}

	it('the label names the mode, and its menu sets the persisted mode and pushes it', () => {
		const root = render();
		expect(modeOption(root).textContent?.trim()).toBe('ratio');
		expect(modeOption(root).getAttribute('aria-haspopup')).toBe('menu');
		expect(modeOption(root).getAttribute('aria-expanded')).toBe('false');
		modeOption(root).click();
		flushSync();
		const items = [...root.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]')];
		expect(items.map((i) => i.querySelector('.qhw-menu-name')?.textContent)).toEqual(['Time', 'Volume', 'Ratio']);
		expect(items.map((i) => i.getAttribute('aria-checked'))).toEqual(['false', 'false', 'true']);
		expect(modeOption(root).getAttribute('aria-expanded')).toBe('true');
		items[1].click();
		flushSync();
		expect(root.querySelector('[role="menu"]')).toBeNull();
		expect(getSettingsStore().hotWaterDose().mode).toBe(HotWaterMode.Volume);
		expect(modeOption(root).textContent?.trim()).toBe('ml');
		expect(push).toHaveBeenCalledOnce();
		// Each mode keeps its value: back to ratio is still 1:3.
		pick(root, 'Ratio');
		expect(getSettingsStore().hotWaterDose()).toMatchObject({ mode: HotWaterMode.Ratio, ratio: 3, volumeMl: 150 });
		// Picking the current mode closes the menu and writes nothing.
		push.mockClear();
		pick(root, 'Ratio');
		expect(push).not.toHaveBeenCalled();
	});

	it('Temp shows the mode without a menu; the first tap on it is the Amount view', () => {
		const root = render();
		root.querySelectorAll<HTMLButtonElement>('.qsplit-opt')[0].click();
		flushSync();
		expect(modeOption(root).textContent?.trim()).toBe('ratio');
		expect(modeOption(root).getAttribute('aria-haspopup')).toBeNull();
		modeOption(root).click();
		flushSync();
		expect(root.querySelector('[role="menu"]')).toBeNull();
		expect(root.querySelector('[aria-label="Hot water ratio presets"]')).not.toBeNull();
	});

	it('ratio chips read as ratios at every width (no unit span to hide)', () => {
		const root = render();
		const chips = [...root.querySelectorAll('.qchipr[aria-label="Hot water ratio presets"] .qchip')];
		expect(chips.map((c) => c.textContent?.trim())).toEqual(['1:2', '1:2.5', '1:3', '1:4', '1:5']);
		expect(chips[0].querySelector('.qchip-unit')).toBeNull();
	});

	it('ratio follows profile', async () => {
		// The real core, nothing connected: a profile's target yield moves the
		// ratio's amount, and the readout follows the core's target.
		const { loadCore } = await import('#lib/core/index.js');
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const core = await loadCore();
		core.reset();
		const app = new CremaApp(core, null);
		await app.pushSteamHotwater();
		// No profile load writes the settings: snapshot before the first.
		const stored = localStorage.getItem('crema.settings.v1');
		await app.applyProfileTargetWeight(36);
		const root = render();
		expect(root.querySelector('.qhw-sub-short')?.textContent).toBe('→ 108 ml');
		expect(root.querySelector('.qhw-sub-long')?.textContent).toBe('1:3 → 108 ml for a 36 g shot');
		expect(root.querySelector('.qhw-sub')?.getAttribute('title')).toBe('1:3 → 108 ml for a 36 g shot');
		await app.applyProfileTargetWeight(50);
		flushSync();
		expect(root.querySelector('.qhw-sub-long')?.textContent).toBe('1:3 → 150 ml for a 50 g shot');
		expect(localStorage.getItem('crema.settings.v1')).toBe(stored);
	});

	it('offline readout re-reads the target', async () => {
		// The profile store's select path: set the target weight on the core,
		// then re-read — no DE1 or scale, so no event carries the target.
		const { loadCore } = await import('#lib/core/index.js');
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const core = await loadCore();
		core.reset();
		const app = new CremaApp(core, null);
		await app.pushSteamHotwater();
		await core.setProfileTargetWeight(50);
		await refreshStopTargetsProjection(core);
		expect(getCremaUiState().current.hotWaterTarget?.volumeMl).toBe(150);
	});
});
