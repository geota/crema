/**
 * `common-mapping.vitest` — the flat web `Settings` ↔ shared core `CommonSettings`
 * mapping that backs the cross-shell backup + the nested persisted store shape.
 * Pins the field renames, the `showVolume`↔`dispensedVolume` chart-key alias, the
 * `themeMode:"system"` coercion, and that web-only platform extras survive.
 */

import { describe, it, expect } from 'vitest';
import {
	settingsToCommon,
	applyCommonToSettings,
	settingsPlatformExtras,
	DEFAULT_SETTINGS,
	brewCueSoundOn,
	brewCueHapticsOn,
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
		expect(back.qcSteamTempC).toBe(150);
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

	it('coerces an Android-only themeMode "system" to dark', () => {
		const back = applyCommonToSettings(
			{ ...settingsToCommon(DEFAULT_SETTINGS), themeMode: 'system' },
			DEFAULT_SETTINGS
		);
		expect(back.theme).toBe('dark');
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
