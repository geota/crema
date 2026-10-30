/**
 * F15: every whole-bundle settings write reaches the core. A backup restore
 * and Restore defaults replace the settings bundle; the store's `onReplaced`
 * hook (registered by `createCremaApp`, mirrored here) runs
 * `CremaApp.pushSettings`, which hands the core every settings-driven input in
 * one `applyCommonSettings` call plus the mains line frequency. Before F15 a
 * restore left the core on the old values until a reload.
 */

import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getSettingsStore } from './index.js';
import { restoreBackup } from '#lib/backup/index.js';
import { HotWaterMode, type CommonSettings, type SteamHotWaterTargets } from '#lib/core/crema-core.js';

beforeAll(async () => {
	await initTestWasm();
});

/** A CremaApp over a core that records the settings it is handed. */
async function makeApp() {
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const pushed: CommonSettings[] = [];
	const lineHz: number[] = [];
	const steamHotwater: SteamHotWaterTargets[] = [];
	const core = new Proxy(
		{},
		{
			get: (_t, key) => {
				if (key === 'applyCommonSettings')
					return async (json: string) => void pushed.push(JSON.parse(json) as CommonSettings);
				if (key === 'setLineFrequencyOverride') return async (hz: number) => void lineHz.push(hz);
				if (key === 'setSteamHotwaterTargets')
					return async (t: SteamHotWaterTargets) => {
						steamHotwater.push(t);
						return { events: [], commands: [] };
					};
				if (key === 'hotWaterTarget') return async () => null;
				return async () => ({});
			}
		}
	);
	const app = new CremaApp(core as never, null);
	getSettingsStore().onReplaced = () => app.pushSettings();
	return {
		app,
		last: () => pushed.at(-1)!,
		lastHz: () => lineHz.at(-1),
		lastDose: () => steamHotwater.at(-1)?.hotWater,
		pushed
	};
}

beforeEach(() => {
	const store = getSettingsStore();
	store.onReplaced = null;
	// Reset keeps the weight unit and line frequency, so clear those too.
	store.reset();
	store.set('weightUnit', 'g');
	store.set('lineFrequencyHz', 0);
	localStorage.clear();
});
afterEach(() => {
	getSettingsStore().onReplaced = null;
});

describe('settings pushes (F15)', () => {
	it('restore pushes the restored settings to the core', async () => {
		const h = await makeApp();
		const common = {
			autoTare: false,
			stopOnWeight: false,
			volumeStopWithScale: true,
			maxShotDurationS: 30,
			weightUnit: 'oz',
			waterWarnMl: 200,
			qcHotWaterMode: HotWaterMode.Volume,
			qcHotWaterVolumeMl: 200
		};
		const bundle = [
			JSON.stringify({ kind: 'crema-backup/v1', createdAt: 1 }),
			JSON.stringify({ kind: 'settings', _shell: 'web', common, lineFrequencyHz: 50 })
		].join('\n');
		restoreBackup(bundle, 'merge');
		expect(h.last()).toMatchObject(common);
		expect(h.lastHz()).toBe(50);
		// The steam / hot-water packet's push goes with it, the dose included.
		await Promise.resolve();
		expect(h.lastDose()).toMatchObject({ mode: HotWaterMode.Volume, volumeMl: 200 });
	});

	it('reset pushes the defaults to the core, keeping the weight unit and line frequency', async () => {
		const h = await makeApp();
		const store = getSettingsStore();
		store.set('autoTareOnShotStart', false);
		store.set('maxShotDurationS', 30);
		store.set('waterWarnMl', 200);
		store.set('weightUnit', 'oz');
		store.set('lineFrequencyHz', 60);
		const before = h.pushed.length;
		store.reset();
		expect(h.pushed.length).toBe(before + 1);
		expect(h.last()).toMatchObject({ autoTare: true, maxShotDurationS: 0, weightUnit: 'oz' });
		expect(h.last().waterWarnMl ?? null).toBeNull();
		expect(h.lastHz()).toBe(60);
	});

	it('pushSettings hands over the current bundle', async () => {
		const h = await makeApp();
		getSettingsStore().set('stopOnWeight', false);
		h.app.pushSettings();
		expect(h.last().stopOnWeight).toBe(false);
		expect(h.lastHz()).toBe(0);
	});
});
