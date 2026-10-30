/**
 * The hot-water dose in the settings store (hot-water modes spec §2.6,
 * AC-HW21): the never-changed migration, run once at start-up (never from a
 * render), the downgrade marker, Restore defaults, and a backup restore that
 * pushes the dose to the core through the one settings push.
 */

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import {
	SettingsStore,
	applyCommonToSettings,
	getSettingsStore,
	settingsToCommon
} from './store.svelte.js';
import { buildConnectSweepSettings } from '#lib/state/connect-sweep.js';
import { restoreBackup } from '#lib/backup/index.js';
import { HotWaterMode, type SteamHotWaterTargets } from '#lib/core/crema-core.js';

const SETTINGS_KEY = 'crema.settings.v1';
const MARKER_KEY = 'crema.hotWater';

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	localStorage.clear();
});

/** A store loaded from a stored `common` block (none = no record) and marker. */
function storeWith(common?: Record<string, unknown>, marker?: Record<string, unknown>): SettingsStore {
	if (common) localStorage.setItem(SETTINGS_KEY, JSON.stringify({ common }));
	if (marker) localStorage.setItem(MARKER_KEY, JSON.stringify(marker));
	return new SettingsStore();
}

function storedCommon(): Record<string, unknown> {
	return (JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}') as { common: Record<string, unknown> }).common;
}

describe('hot water never-changed migration', () => {
	it('no settings record → ratio 1:3', () => {
		const s = storeWith();
		s.migrateHotWater();
		expect(s.current.qcHotWaterMode).toBe(HotWaterMode.Ratio);
		expect(s.hotWaterDose()).toMatchObject({ mode: HotWaterMode.Ratio, ratio: 3, volumeMl: 150 });
	});

	it('a record from before the hot-water fields → ratio 1:3', () => {
		const s = storeWith({ qcHotWaterTempC: 80, qcSteamTempC: 150 });
		s.migrateHotWater();
		expect(s.hotWaterDose().mode).toBe(HotWaterMode.Ratio);
	});

	it('150 ml and no mode → ratio 1:3; 200 ml and no mode → volume 200', () => {
		const a = storeWith({ qcHotWaterVolumeMl: 150 });
		a.migrateHotWater();
		expect(a.hotWaterDose()).toMatchObject({ mode: HotWaterMode.Ratio, ratio: 3 });
		localStorage.clear();
		const b = storeWith({ qcHotWaterVolumeMl: 200 });
		b.migrateHotWater();
		expect(b.hotWaterDose()).toMatchObject({ mode: HotWaterMode.Volume, volumeMl: 200 });
	});

	it('the result is persisted once: a second load keeps a later volume-mode 150 ml', () => {
		const s = storeWith({ qcHotWaterVolumeMl: 150 });
		s.migrateHotWater();
		expect(storedCommon().qcHotWaterMode).toBe(HotWaterMode.Ratio);
		s.setHotWaterDose({ ...s.hotWaterDose(), mode: HotWaterMode.Volume, volumeMl: 150 });
		const reloaded = new SettingsStore();
		reloaded.migrateHotWater();
		expect(reloaded.hotWaterDose()).toMatchObject({ mode: HotWaterMode.Volume, volumeMl: 150 });
	});

	it('Restore defaults → ratio 1:3, stored, and the bundle is pushed', () => {
		const s = storeWith({ qcHotWaterVolumeMl: 200, qcHotWaterMode: 'volume' });
		const pushed = vi.fn();
		s.onReplaced = pushed;
		s.reset();
		expect(s.current.qcHotWaterMode).toBe(HotWaterMode.Ratio);
		expect(s.hotWaterDose()).toMatchObject({ mode: HotWaterMode.Ratio, ratio: 3, timeS: 30, volumeMl: 150 });
		expect(pushed).toHaveBeenCalledOnce();
	});

	it('a backup round-trip keeps every hot-water field', () => {
		const s = storeWith();
		s.setHotWaterDose({ mode: HotWaterMode.Time, timeS: 45, timeNoLimit: true, volumeMl: 180, ratio: 2.5 });
		const back = applyCommonToSettings(settingsToCommon(s.current), s.current);
		expect(back.qcHotWaterMode).toBe(HotWaterMode.Time);
		expect(back.qcHotWaterTimeS).toBe(45);
		expect(back.qcHotWaterTimeNoLimit).toBe(true);
		expect(back.qcHotWaterVolumeMl).toBe(180);
		expect(back.qcHotWaterRatio).toBe(2.5);
	});
});

describe('migration at start-up, not render', () => {
	it('reading the dose writes nothing', () => {
		const s = storeWith({ qcHotWaterVolumeMl: 150 });
		const before = localStorage.getItem(SETTINGS_KEY);
		expect(s.hotWaterDose().mode).toBe(HotWaterMode.Ratio);
		expect(localStorage.getItem(SETTINGS_KEY)).toBe(before);
		expect(s.current.qcHotWaterMode).toBeNull();
	});

	it('the connect sweep sends ratio 1:3 for a never-changed record, before any card renders', () => {
		const s = storeWith({ qcHotWaterVolumeMl: 150 });
		const snap = buildConnectSweepSettings(s.current, 0, null);
		expect(snap.hotWater).toMatchObject({ mode: HotWaterMode.Ratio, ratio: 3 });
	});

	it('the migration persists once and is then a no-op', () => {
		const s = storeWith({ qcHotWaterVolumeMl: 150 });
		s.migrateHotWater();
		const once = localStorage.getItem(SETTINGS_KEY);
		s.migrateHotWater();
		expect(localStorage.getItem(SETTINGS_KEY)).toBe(once);
	});

	it('createCremaApp migrates before the first settings push', async () => {
		// The start-up path, not a render: the migration sits in
		// `createCremaApp` right after `loadCore()`, ahead of `pushSettings()`.
		const source = (await import('#lib/state/app.svelte.ts?raw')).default as string;
		const start = source.indexOf('export async function createCremaApp');
		const migrate = source.indexOf('getSettingsStore().migrateHotWater()', start);
		const push = source.indexOf('app.pushSettings()', start);
		expect(start).toBeGreaterThan(-1);
		expect(migrate).toBeGreaterThan(start);
		expect(migrate).toBeLessThan(push);
	});
});

describe('downgrade marker', () => {
	it('a blob without a mode plus a marker of volume 150 stays volume 150', () => {
		const s = storeWith({ qcHotWaterVolumeMl: 150 }, { mode: 'volume', volumeMl: 150 });
		expect(s.hotWaterDose()).toMatchObject({ mode: HotWaterMode.Volume, volumeMl: 150 });
		s.migrateHotWater();
		expect(storedCommon().qcHotWaterMode).toBe(HotWaterMode.Volume);
	});

	it('a stored mode wins over the marker', () => {
		const s = storeWith({ qcHotWaterVolumeMl: 150, qcHotWaterMode: 'ratio' }, { mode: 'volume', volumeMl: 150 });
		expect(s.hotWaterDose().mode).toBe(HotWaterMode.Ratio);
	});

	it('every dose save rewrites the marker', () => {
		const s = storeWith();
		s.setHotWaterDose({ ...s.hotWaterDose(), mode: HotWaterMode.Time, timeS: 60 });
		expect(JSON.parse(localStorage.getItem(MARKER_KEY) ?? '{}')).toMatchObject({ mode: 'time', timeS: 60 });
	});

	it("a restored backup without a mode follows its own fields, not this device's marker", () => {
		const s = storeWith({ qcHotWaterVolumeMl: 150 }, { mode: 'volume', volumeMl: 150 });
		s.replaceAll(applyCommonToSettings({ qcHotWaterVolumeMl: 150 } as never, s.current));
		expect(s.current.qcHotWaterMode).toBe(HotWaterMode.Ratio);
	});
});

describe('restore pushes hot water', () => {
	afterEach(() => {
		getSettingsStore().onReplaced = null;
	});

	it('a restore hands the core the backup dose through the one push', async () => {
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const sent: SteamHotWaterTargets[] = [];
		const core = new Proxy(
			{},
			{
				get: (_t, key) => {
					if (key === 'setSteamHotwaterTargets')
						return async (t: SteamHotWaterTargets) => {
							sent.push(t);
							return { events: [], commands: [] };
						};
					if (key === 'hotWaterTarget') return async () => null;
					return async () => ({ events: [], commands: [] });
				}
			}
		);
		const app = new CremaApp(core as never, null);
		getSettingsStore().onReplaced = () => app.pushSettings();
		const common = {
			qcHotWaterMode: 'time',
			qcHotWaterTimeS: 45,
			qcHotWaterVolumeMl: 180,
			qcHotWaterRatio: 2.5
		};
		const bundle = [
			JSON.stringify({ kind: 'crema-backup/v1', createdAt: 1 }),
			JSON.stringify({ kind: 'settings', _shell: 'web', common })
		].join('\n');
		restoreBackup(bundle, 'merge');
		await vi.waitFor(() => expect(sent.length).toBeGreaterThan(0));
		expect(sent.at(-1)?.hotWater).toMatchObject({ mode: HotWaterMode.Time, timeS: 45, volumeMl: 180, ratio: 2.5 });
	});
});
