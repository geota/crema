/**
 * `common-mapping.vitest` — the flat web `Settings` ↔ shared core `CommonSettings`
 * mapping that backs the cross-shell backup + the nested persisted store shape.
 * Pins the field renames, the `showVolume`↔`dispensedVolume` chart-key alias, the
 * `themeMode:"system"` coercion, and that web-only platform extras survive.
 */

import { beforeAll, describe, it, expect } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { CommonSettings } from '#lib/core/crema-core.js';
import {
	settingsToCommon,
	applyCommonToSettings,
	settingsPlatformExtras,
	DEFAULT_SETTINGS,
	brewCueSoundOn,
	brewCueHapticsOn,
	resolveTheme,
	type Settings
} from './store.svelte';

describe('common-settings mapping', () => {
	it('round-trips every renamed common field', () => {
		const s: Settings = {
			...DEFAULT_SETTINGS,
			theme: 'light',
			autoTareOnShotStart: false,
			steamEcoMode: true,
			groupFlushBeforeShot: true,
			autoPurgeAfterSteam: true,
			defaultPreinfusionS: 11,
			maxShotDurationS: 42,
			grinderModel: 'Niche Zero',
			baristaName: 'Ana',
			qcSteamTempC: 150
		};
		const back = applyCommonToSettings(settingsToCommon(s), DEFAULT_SETTINGS);
		expect(back.theme).toBe('light');
		expect(back.autoTareOnShotStart).toBe(false);
		expect(back.steamEcoMode).toBe(true);
		expect(back.groupFlushBeforeShot).toBe(true);
		expect(back.autoPurgeAfterSteam).toBe(true);
		expect(back.defaultPreinfusionS).toBe(11);
		expect(back.maxShotDurationS).toBe(42);
		expect(back.grinderModel).toBe('Niche Zero');
		expect(back.baristaName).toBe('Ana');
		expect(back.qcSteamTempC).toBe(150);
		// No default barista travels as absent, and an older blob without
		// the field reads as empty.
		expect(settingsToCommon(DEFAULT_SETTINGS).baristaName).toBeUndefined();
		expect(applyCommonToSettings({ ...settingsToCommon(s), baristaName: undefined }, DEFAULT_SETTINGS).baristaName).toBe('');
	});

	it('maps showVolume to the canonical dispensedVolume key (and back)', () => {
		const s: Settings = {
			...DEFAULT_SETTINGS,
			showPressure: true,
			showFlow: false,
			showVolume: true,
			showWeight: true,
			showWeightFlow: false,
			showResistance: false,
			showHeadTemp: false,
			showMixTemp: false
		};
		const common = settingsToCommon(s);
		expect(common.chartChannels).toContain('dispensedVolume');
		expect(common.chartChannels).not.toContain('volume');
		expect(common.chartChannels).toEqual(['pressure', 'dispensedVolume', 'weight']);

		const back = applyCommonToSettings(common, DEFAULT_SETTINGS);
		expect(back.showVolume).toBe(true);
		expect(back.showFlow).toBe(false);
		expect(back.showPressure).toBe(true);
		expect(back.showWeight).toBe(true);
		expect(back.showWeightFlow).toBe(false);
	});

	it('keeps themeMode "system" (follow the device) and reads unknown values as dark', () => {
		const back = applyCommonToSettings(
			{ ...settingsToCommon(DEFAULT_SETTINGS), themeMode: 'system' },
			DEFAULT_SETTINGS
		);
		expect(back.theme).toBe('system');
		expect(settingsToCommon(back).themeMode).toBe('system');
		const odd = applyCommonToSettings(
			{ ...settingsToCommon(DEFAULT_SETTINGS), themeMode: 'sepia' },
			DEFAULT_SETTINGS
		);
		expect(odd.theme).toBe('dark');
	});

	it('resolves the system theme from the OS preference', () => {
		expect(resolveTheme('system', true)).toBe('light');
		expect(resolveTheme('system', false)).toBe('dark');
		expect(resolveTheme('light', false)).toBe('light');
		expect(resolveTheme('dark', true)).toBe('dark');
	});

	it('preserves the web-only platform extras through a common overlay', () => {
		const s: Settings = {
			...DEFAULT_SETTINGS,
			density: 'compact',
			webhookUrl: 'https://x.test',
			smoothPressure: false,
			telemetryRateHz: 30
		};
		const back = applyCommonToSettings(settingsToCommon(s), s);
		expect(settingsPlatformExtras(back)).toMatchObject({
			density: 'compact',
			webhookUrl: 'https://x.test',
			smoothPressure: false,
			telemetryRateHz: 30
		});
	});

	describe('guided-brew cue defaults (issue #10)', () => {
		// The never-chosen defaults come from the core.
		beforeAll(async () => {
			await initTestWasm();
		});

		it('untouched cues read sound off / haptics on and are not serialised', () => {
			expect(DEFAULT_SETTINGS.brewCueSound).toBeNull();
			expect(DEFAULT_SETTINGS.brewCueHaptics).toBeNull();
			expect(brewCueSoundOn(DEFAULT_SETTINGS)).toBe(false);
			expect(brewCueHapticsOn(DEFAULT_SETTINGS)).toBe(true);
			const json = JSON.stringify(settingsToCommon(DEFAULT_SETTINGS));
			expect(json).not.toContain('brewCueSound');
			expect(json).not.toContain('brewCueHaptics');
		});

		it('an older blob without the fields follows the defaults', () => {
			const common = settingsToCommon(DEFAULT_SETTINGS);
			delete common.brewCueSound;
			delete common.brewCueHaptics;
			const back = applyCommonToSettings(common, DEFAULT_SETTINGS);
			expect(back.brewCueSound).toBeNull();
			expect(brewCueSoundOn(back)).toBe(false);
			expect(brewCueHapticsOn(back)).toBe(true);
		});

		it('explicit choices win and round-trip', () => {
			const s: Settings = { ...DEFAULT_SETTINGS, brewCueSound: true, brewCueHaptics: false };
			const common = JSON.parse(JSON.stringify(settingsToCommon(s)));
			expect(common.brewCueSound).toBe(true);
			expect(common.brewCueHaptics).toBe(false);
			const back = applyCommonToSettings(common, DEFAULT_SETTINGS);
			expect(brewCueSoundOn(back)).toBe(true);
			expect(brewCueHapticsOn(back)).toBe(false);
		});
	});
});

describe('core-owned defaults, sanitize and reset (F15)', () => {
	beforeAll(async () => {
		await initTestWasm();
	});

	it('default settings come from the core bundle', async () => {
		const { defaultCommonSettingsJson, defaultIdleSleepMin } = await import('#lib/wasm/de1_wasm.js');
		const { sanitizeCommon } = await import('./store.svelte');
		const core = JSON.parse(defaultCommonSettingsJson()) as CommonSettings;
		expect(sanitizeCommon(settingsToCommon(DEFAULT_SETTINGS))).toEqual(
			sanitizeCommon({ ...core, idleSleepMin: defaultIdleSleepMin() })
		);
		// The resolved "unset reads as" values come from the core too.
		expect(DEFAULT_SETTINGS.fanThresholdC).toBe(core.fanThresholdC);
		expect(DEFAULT_SETTINGS.usbChargingMode).toBe(core.usbChargingMode);
		expect(DEFAULT_SETTINGS.qcSteamTempC).toBe(core.qcSteamTempC);
	});

	it('a restored settings line with a wrong-typed field restores the rest', () => {
		const common = {
			...settingsToCommon(DEFAULT_SETTINGS),
			autoTare: 'yes',
			tempUnit: 'F',
			fanThresholdC: 400,
			chartChannels: ['flow', 'bogus']
		} as unknown as CommonSettings;
		const s = applyCommonToSettings(common, DEFAULT_SETTINGS);
		expect(s.tempUnit).toBe('F');
		expect(s.autoTareOnShotStart).toBe(true);
		expect(s.fanThresholdC).toBe(60);
		expect([s.showFlow, s.showPressure]).toEqual([true, false]);
	});

	it('reset keeps weightUnit and lineFrequencyHz', async () => {
		const { getSettingsStore } = await import('./store.svelte');
		const store = getSettingsStore();
		store.onReplaced = null;
		store.set('theme', 'light');
		store.set('weightUnit', 'oz');
		store.set('lineFrequencyHz', 60);
		store.set('grinderModel', 'Niche');
		store.set('telemetryRateHz', 10);
		store.reset();
		expect(store.current.weightUnit).toBe('oz');
		expect(store.current.lineFrequencyHz).toBe(60);
		expect(store.current.theme).toBe('dark');
		expect(store.current.grinderModel).toBe('');
		expect(store.current.telemetryRateHz).toBe(DEFAULT_SETTINGS.telemetryRateHz);
		store.set('weightUnit', 'g');
		store.set('lineFrequencyHz', 0);
	});
});

describe('pre-unification flat settings go through the core sanitize too (F15)', () => {
	beforeAll(async () => {
		await initTestWasm();
	});

	it('an old flat settings file loads sanitized', async () => {
		const { SettingsStore } = await import('./store.svelte');
		localStorage.setItem(
			'crema.settings.v1',
			JSON.stringify({ theme: 'light', fanThresholdC: 400, weightUnit: 'stone', baristaName: 7 })
		);
		const s = new SettingsStore().current;
		expect(s.theme).toBe('light');
		expect(s.fanThresholdC).toBe(60);
		expect(s.weightUnit).toBe('g');
		expect(s.baristaName).toBe('');
		localStorage.clear();
	});

	it('an old flat web backup line restores sanitized', async () => {
		const { getSettingsStore } = await import('./store.svelte');
		const { restoreBackup } = await import('#lib/backup/index.js');
		const store = getSettingsStore();
		store.onReplaced = null;
		const bundle = [
			JSON.stringify({ kind: 'crema-backup/v1', createdAt: 1 }),
			JSON.stringify({ kind: 'settings', _shell: 'web', tempUnit: 'F', fanThresholdC: -5 })
		].join('\n');
		restoreBackup(bundle, 'merge');
		expect(store.current.tempUnit).toBe('F');
		expect(store.current.fanThresholdC).toBe(0);
		store.set('tempUnit', 'C');
		store.set('fanThresholdC', 55);
	});
});
