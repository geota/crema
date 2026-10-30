/**
 * `#lib/settings/store` — the app-preferences store.
 *
 * Backs the `/settings` page. Crema's web shell is a static, client-only PWA —
 * there is no server, and (per the project's framing) there is no Crema
 * account either — so `localStorage` is the right home for user preferences.
 * This store mirrors `lib/profiles` and `lib/history`: a Svelte 5 `$state`
 * class, the shared `#lib/utils/storage.js` helpers, a single versioned key.
 *
 * What lives here are **app preferences only** — units, theme, sound/haptic
 * cues, brew defaults, display density, advanced toggles. None of it is a
 * machine setting: writing to the DE1 needs the BLE write path the shell does
 * not expose, and a scale setting belongs to the live `UiSnapshot`. The
 * Settings page wires those sections to their real owners; everything in this
 * store survives a reload because it is genuinely the app's own state.
 *
 * The store is a Svelte 5 `$state` class; obtain the singleton with
 * {@link getSettingsStore}. It loads synchronously from `localStorage`.
 */

import {
	defaultBrewCueHaptics,
	defaultBrewCueSound,
	defaultCommonSettingsJson,
	defaultIdleSleepMin,
	initialHotWaterDose,
	resetSettingsJson,
	sanitizeCommonSettingsJson
} from '#lib/wasm/de1_wasm.js';
import { isJsonObject, readJson, writeJson } from '#lib/utils/storage.js';
import { readSyncConfig } from '#lib/visualizer/sync-config.js';
import type {
	CommonSettings,
	HotWaterDose,
	HotWaterMode,
	SettingsReset,
	StoredHotWater
} from '#lib/core/crema-core.js';

/** localStorage key for the bundle of app preferences ({@link Settings}). */
const SETTINGS_KEY = 'crema.settings.v1';

/**
 * localStorage key for the device-local copy of the hot-water dose: the
 * downgrade marker. An older build ignores the unknown hot-water keys and
 * drops them on its next save; this copy, which it never rewrites, keeps a
 * deliberate volume-mode 150 ml from turning into ratio 1:3 when the user
 * upgrades again. Read only when the settings carry no mode.
 */
const HOT_WATER_MARKER_KEY = 'crema.hotWater';

/**
 * The core's out-of-box brew defaults (`{doseG, ratio, brewTempC, preinfusionS}`),
 * parsed once on first read and memoized. {@link DEFAULT_SETTINGS} surfaces these
 * through getters so the seed values live in the Rust core, not hardcoded here.
 *
 * Lazy — *never* at module scope: the wasm export is only callable once
 * `loadCore()` has run (the rule `#lib/profiles/bounds.ts` follows). The getters
 * fire when the settings store is first constructed at render time, long after
 * boot. See `de1_domain::default_brew_defaults_json`.
 */
/**
 * Light/dark theme. The design ships dark; dark stays the default. `system`
 * follows the device's `prefers-color-scheme` (a kitchen tablet that dims to
 * dark at night), the same value the Android shell stores (report 07 #19).
 */
export type ThemePref = 'dark' | 'light' | 'system';

/** The concrete theme for a preference, given whether the OS prefers light. */
export function resolveTheme(pref: ThemePref, prefersLight: boolean): 'dark' | 'light' {
	if (pref === 'system') return prefersLight ? 'light' : 'dark';
	return pref;
}

/** Unit choices, one per measured quantity. */
export type WeightUnit = 'g' | 'oz';
export type TempUnit = 'C' | 'F';
export type VolumeUnit = 'ml' | 'floz';
export type PressureUnit = 'bar' | 'psi';
export type WaterLevelUnit = 'ml' | 'percent';

/** Display density — card padding / control sizing. */
export type Density = 'compact' | 'comfortable' | 'spacious';

/** Visualizer default privacy for uploaded shots. */
export type SharingPrivacy = 'public' | 'unlisted' | 'private';

/**
 * The full, persisted preference bundle. A flat record so a partial update is
 * a trivial spread, and a new field added in a later version defaults cleanly
 * via {@link DEFAULT_SETTINGS}.
 */
/** The persisted spellings of the Tablet charging modes (Decenza
 * `BatteryManager` Off / On / Night). */
export type UsbChargingModePref = 'smart' | 'smartHigh' | 'alwaysOn';

/** Read a persisted / backed-up mode; unset or unknown is `'alwaysOn'`. */
export function parseUsbChargingMode(raw: string | null | undefined): UsbChargingModePref {
	return raw === 'smart' || raw === 'smartHigh' ? raw : 'alwaysOn';
}

export interface Settings {
	// ── Display & units ──────────────────────────────────────────────────
	/** Light/dark theme — drives `data-theme` on `<html>`. */
	theme: ThemePref;
	/** Card padding / stepper sizing. App preference (no live surface yet). */
	density: Density;
	/** Show the calm pour animation after a long idle. App preference. */
	screensaver: boolean;
	/** Weight display unit. */
	weightUnit: WeightUnit;
	/** Temperature display unit. */
	tempUnit: TempUnit;
	/** Volume display unit. */
	volumeUnit: VolumeUnit;
	/** Pressure display unit. */
	pressureUnit: PressureUnit;
	/** Water-tank readout style — ml or percent of a typical full fill. */
	waterLevelUnit: WaterLevelUnit;
	/**
	 * Low-water warning threshold, canonical ml (geota/crema#33 follow-up).
	 * `null` = unset → the 110 ml (~10%) default; `0` disables. The
	 * Settings row dials it in {@link waterLevelUnit}.
	 */
	waterWarnMl: number | null;
	/**
	 * The machine's own refill threshold, RAW sensor mm — written to the DE1's
	 * `WaterLevels` `StartFillLevel` at connect (geota/crema#47). Below it the
	 * DE1 itself blinks for water and refuses to pour, whatever the app shows,
	 * so a stale high value left in the firmware makes the machine demand a
	 * refill on a half-full tank. `null` = unset → core's 5 mm default, which
	 * is de1app's and Decenza's default too.
	 */
	waterRefillPointMm: number | null;

	// ── Brew defaults ────────────────────────────────────────────────────
	/** Default dose for new profiles, grams. */
	defaultDoseG: number;
	/** Default yield-to-dose ratio (the `x` in `1:x`). */
	defaultRatio: number;
	/** Default group temperature, °C. */
	defaultBrewTempC: number;
	/** Default pre-infusion time, seconds. */
	defaultPreinfusionS: number;
	/**
	 * Run a short group flush after steaming (app-level; distinct from
	 * the DE1 firmware's own steam-wand purge — see `steamTwoTapStop`).
	 * Default off, matching core and Android.
	 */
	autoPurgeAfterSteam: boolean;
	/** Flush the group before each shot. */
	groupFlushBeforeShot: boolean;
	/**
	 * Steam eco mode — when on, the DE1 runs a lower-flow, lower-temp
	 * steam profile that's gentler on small milk jugs and easier to
	 * texture. Default off (full-power steam). Pushed to the core via
	 * `enableSteamEcoMode`.
	 */
	steamEcoMode: boolean;
	/**
	 * Auto-tare the connected scale on shot start. Mirrors the legacy
	 * de1app's always-on tare behaviour (and reaprime's), exposed here
	 * as a user preference so dose-cup-mass pre-tare workflows can opt
	 * out. Pushed to the core via `setAutoTare`; consulted on
	 * `ShotEvent::Started` regardless of who initiated the shot.
	 */
	autoTareOnShotStart: boolean;
	/**
	 * Enable stop-at-weight. When off, SAW never arms even if a target
	 * weight is configured (on the active profile or the QC dial).
	 * Pushed to the core via `setStopOnWeight`.
	 */
	stopOnWeight: boolean;
	/**
	 * Opt-in: arm the profile's max-volume stop even while a scale is
	 * connected. Default off — volume is a no-scale fallback, never a
	 * competitor to stop-at-weight (reference-app consensus; the DE1's
	 * own tail volume stop is never uploaded either way). Pushed to the
	 * core via `setVolumeStopWithScale`.
	 */
	volumeStopWithScale: boolean;
	/**
	 * Opt-in: refuse to start a shot when stop-at-weight would arm (a
	 * weight target resolves) but no scale is connected — the de1app
	 * `start_espresso_only_if_scale_connected` / reaprime `blockOnNoScale`
	 * pattern, both also default-off. Off = warn non-blockingly instead.
	 */
	requireScale: boolean;
	/**
	 * Global maximum shot duration, seconds — a safety guardrail when
	 * neither SAW nor SAV is configured. `0` means "no max", the default —
	 * the profile dictates shot length (geota/crema#32). Pushed to the
	 * core via `setMaxShotDuration`. Settings-only because neither de1app
	 * nor reaprime carry this per-profile.
	 */
	maxShotDurationS: number;

	// ── Machine ──────────────────────────────────────────────────────────
	/** Chart telemetry sample rate, Hz. App preference (display only). */
	telemetryRateHz: number;
	/**
	 * AC mains frequency override for the DE1's flow → volume integrator.
	 * `0` (default) means **auto-detect** from the live `sample_time`
	 * stream; `50` / `60` pins the value. Sending the pin to the core
	 * makes the integrator use it instead of the auto-detector's lock.
	 */
	lineFrequencyHz: 0 | 50 | 60;
	/**
	 * "Sleep after": idle minutes before Crema puts the DE1 to sleep —
	 * one of the core's `idleSleepChoices()` (Never / 15 / 30 / 60 / 90 /
	 * 120), `0` = Never, default 30 (decaid `sleepTimeoutMinutes`; de1app
	 * `screen_saver_delay` and Decenza `autoSleepMinutes` are the same
	 * setting, default 60). The core's idle-sleep timer owns the decision
	 * (`state/idle-sleep.ts`); cross-shell as `CommonSettings.idleSleepMin`.
	 * Replaces the retired `suppressDe1Sleep` heartbeat, whose premise — a
	 * firmware sleep timer taking over when the pokes stop — doesn't hold on
	 * a stock DE1.
	 */
	idleSleepMin: number;
	/**
	 * DE1 firmware two-tap steam stop (MMR `SteamTwoTapStop`): first stop
	 * tap ends steam without the wand auto-purge; a second tap purges.
	 * Written to the machine on change + re-seeded on connect
	 * (geota/crema#34).
	 */
	steamTwoTapStop: boolean;
	/**
	 * Fan-on temperature threshold, °C (MMR `FanThreshold`, 0..=60 —
	 * de1app/Decenza's range; default 55 splits their 60 and reaprime's
	 * 50). Re-seeded on every connect — the DE1 boots with a low firmware
	 * default and de1app rewrote it each connect, so without this the fan
	 * runs near-constantly under Crema (geota/crema#31).
	 */
	fanThresholdC: number;
	/**
	 * Tablet charging from the DE1's USB port (Decenza `BatteryManager`
	 * modes): `'alwaysOn'` never switches the port off (the default, as
	 * decaid), `'smart'` keeps the tablet between 55 % and 65 %,
	 * `'smartHigh'` between 90 % and 95 %. Asserted on connect and every
	 * minute after (the core decides from the tablet battery).
	 */
	usbChargingMode: UsbChargingModePref;

	// ── Equipment ────────────────────────────────────────────────────────
	/**
	 * Free-text grinder model — used as the equipment-level default on
	 * shot uploads to Visualizer (`grinder_model` on `ShotUpdateRequest`).
	 * Empty string means "no default", and the upload omits the field
	 * unless the shot itself carries an override.
	 *
	 * Unlike `bean.grinderSetting` (which is the per-bag click setting),
	 * this is a property of the user's gear — e.g. "Niche Zero",
	 * "Eureka Mignon Specialita". Free text because there's no canonical
	 * grinder catalogue. Captured into a shot's `grinderModel` snapshot
	 * at completion; a shot may override it in the shot-detail panel.
	 */
	grinderModel: string;
	/**
	 * Default barista name, stamped on each new shot and brew log at
	 * completion (`metadata.barista`). Empty = none. Editable per shot.
	 */
	baristaName: string;

	/**
	 * Hold a Screen Wake Lock while a shot is pulling so the display can't
	 * sleep mid-extraction. Consumed by the shell layout's effect via
	 * `#lib/shell/wake-lock.js`; a no-op on browsers without the API.
	 */
	keepScreenOnBrew: boolean;

	/**
	 * Guided-brew step cues as sound (issue #10) — short WebAudio blips
	 * at approach / boundary / step-change. `null` = never touched, read
	 * via {@link brewCueSoundOn} as OFF (the kitchen-safe default; the
	 * step card's visual cues are always on). Only an explicit choice is
	 * persisted, so an untouched user follows the default.
	 */
	brewCueSound: boolean | null;
	/** Guided-brew step cues as vibration, where the device supports it.
	 *  `null` = never touched, read via {@link brewCueHapticsOn} as ON. */
	brewCueHaptics: boolean | null;

	// ── Quick-Controls steam / hot-water / flush (issue 14) ──────────────────
	// Persisted Quick Sheet values for the machine's steam / hot-water / flush
	// params. Unlike most of this store these *are* machine settings — but they
	// stick across reloads (so the Quick Sheet seeds from the user's last
	// choice, not a hardcoded default) and take priority, and on change they're
	// read-modify-written to the DE1 (steam temp/time + hot-water temp/vol share
	// one cuuid_0B packet; steam flow + flush temp/time are separate MMR writes).
	/** Steam duration, seconds. */
	qcSteamTimeS: number;
	/** Steam flow rate, ml/s. */
	qcSteamFlowMlS: number;
	/** Steam boiler target temperature, °C. */
	qcSteamTempC: number;
	/** Hot-water temperature, °C. */
	qcHotWaterTempC: number;
	/** Hot-water volume-mode value, ml. */
	qcHotWaterVolumeMl: number;
	/**
	 * The hot-water dose mode, and the time / No limit / ratio values (the
	 * volume is `qcHotWaterVolumeMl`). `null` in a record from before the
	 * modes, until the start-up migration stores the core's answer: read the
	 * dose through `SettingsStore.hotWaterDose()`, never these fields.
	 */
	qcHotWaterMode: HotWaterMode | null;
	/** Hot-water time-mode seconds (see `qcHotWaterMode`). */
	qcHotWaterTimeS: number | null;
	/** Hot-water time mode pours until stopped (see `qcHotWaterMode`). */
	qcHotWaterTimeNoLimit: boolean | null;
	/** Hot-water ratio: water per gram of shot (see `qcHotWaterMode`). */
	qcHotWaterRatio: number | null;
	/** Group-flush duration, seconds. */
	qcFlushTimeS: number;
	/** Group-flush target temperature, °C. */
	qcFlushTempC: number;

	// ── Live-chart channel toggles ───────────────────────────────────────
	// The eight per-line on/off flags for the Brew dashboard's chart. The
	// four "primary" channels default on; the four "secondary" channels
	// (paired siblings — resistance pairs with pressure, volume with flow,
	// mix temp with head temp, weight flow with weight) default off so the
	// chart stays clean until the user opts in. The Quick Sheet's "Chart
	// channels" section is the live UI; each pair shares the channel
	// card's slot so both numbers are always visible regardless of which
	// lines are plotted.
	/** Plot the pressure line. */
	showPressure: boolean;
	/** Plot the puck-resistance line (paired with pressure). */
	showResistance: boolean;
	/** Plot the flow line. */
	showFlow: boolean;
	/** Plot the dispensed-volume line (paired with flow). */
	showVolume: boolean;
	/** Plot the group-head temperature line. */
	showHeadTemp: boolean;
	/** Plot the mix (group water) temperature line (paired with head). */
	showMixTemp: boolean;
	/** Plot the scale-weight line. */
	showWeight: boolean;
	/** Plot the mass-flow rate line in g/s (paired with weight). */
	showWeightFlow: boolean;

	// ── Webhooks ─────────────────────────────────────────────────────────
	// Outbound POSTs to a user-supplied URL when chosen events fire. The
	// shell is a static, client-only PWA — there is no server hop, so the
	// fetch goes straight from the browser to the user's endpoint. Subject
	// to CORS on the receiving side. No retries, no auth — MVP.
	/** Master switch for the webhook feature. */
	webhookEnabled: boolean;
	/** Destination URL — `https://…` (or `http://localhost…` for dev). */
	webhookUrl: string;
	/** Per-event toggles — independent of each other. */
	webhookEvents: {
		/** Fire when a shot finishes. */
		shotCompleted: boolean;
		/** Fire when the DE1 enters an `Error*` substate. */
		machineError: boolean;
		/** Fire when a profile upload completes successfully. */
		profileUploaded: boolean;
		/** Fire when a scale connects (reaches `ready`). */
		scaleConnected: boolean;
		/** Fire when the DE1 connects (reaches `ready`). */
		de1Connected: boolean;
	};

	// ── Advanced ─────────────────────────────────────────────────────────
	/** Smooth the pressure curve. */
	smoothPressure: boolean;
	/** Show a debug / event-log panel (BLE diagnostics). */
	showDebugPanel: boolean;
}

/** The web-only settings' defaults — every field that isn't in the core's
 *  `CommonSettings` (see `PLATFORM_KEYS`). */
const PLATFORM_DEFAULTS = {
	density: 'comfortable',
	screensaver: true,
	telemetryRateHz: 50,
	lineFrequencyHz: 0,
	webhookEnabled: false,
	webhookUrl: '',
	webhookEvents: {
		shotCompleted: true,
		machineError: true,
		profileUploaded: false,
		scaleConnected: false,
		de1Connected: false
	},
	smoothPressure: true
} as const satisfies Pick<Settings, (typeof PLATFORM_KEYS)[number]>;

/** The web `Settings` fields that come from the core's `CommonSettings`. */
const COMMON_KEYS = [
	'theme',
	'weightUnit',
	'tempUnit',
	'volumeUnit',
	'pressureUnit',
	'waterLevelUnit',
	'waterWarnMl',
	'waterRefillPointMm',
	'defaultDoseG',
	'defaultRatio',
	'defaultBrewTempC',
	'defaultPreinfusionS',
	'autoPurgeAfterSteam',
	'groupFlushBeforeShot',
	'steamEcoMode',
	'autoTareOnShotStart',
	'stopOnWeight',
	'volumeStopWithScale',
	'requireScale',
	'maxShotDurationS',
	'idleSleepMin',
	'steamTwoTapStop',
	'fanThresholdC',
	'usbChargingMode',
	'grinderModel',
	'baristaName',
	'keepScreenOnBrew',
	'brewCueSound',
	'brewCueHaptics',
	'qcSteamTimeS',
	'qcSteamFlowMlS',
	'qcSteamTempC',
	'qcHotWaterTempC',
	'qcHotWaterVolumeMl',
	'qcHotWaterMode',
	'qcHotWaterTimeS',
	'qcHotWaterTimeNoLimit',
	'qcHotWaterRatio',
	'qcFlushTimeS',
	'qcFlushTempC',
	'showPressure',
	'showResistance',
	'showFlow',
	'showVolume',
	'showHeadTemp',
	'showMixTemp',
	'showWeight',
	'showWeightFlow',
	'showDebugPanel'
] as const satisfies ReadonlyArray<keyof Settings>;

let coreDefaults: Settings | null = null;
/** The core's default bundle (`default_common_settings`) mapped onto the web
 *  shape, over the platform defaults. Parsed once, on first read (after the
 *  wasm has loaded). An unset idle timer reads as the core default. */
function defaultsFromCore(): Settings {
	if (!coreDefaults) {
		const base = { ...PLATFORM_DEFAULTS, idleSleepMin: defaultIdleSleepMin() } as unknown as Settings;
		coreDefaults = applyCommonToSettings(JSON.parse(defaultCommonSettingsJson()) as CommonSettings, base);
	}
	return coreDefaults;
}

/**
 * The default preference bundle — every field's out-of-box value. The
 * `CommonSettings` half reads the core's default bundle (one source for both
 * shells); only the web-only extras are literals here. The common fields are
 * getters, so spreading the object (`{ ...DEFAULT_SETTINGS }`) copies values.
 */
export const DEFAULT_SETTINGS: Settings = { ...PLATFORM_DEFAULTS } as unknown as Settings;
for (const key of COMMON_KEYS) {
	Object.defineProperty(DEFAULT_SETTINGS, key, {
		enumerable: true,
		get: () => defaultsFromCore()[key]
	});
}

// ── Cross-shell common-settings mapping ──────────────────────────────────────
//
// The web `Settings` shape is flat and web-named; the backup (and the persisted
// store) carry the portable subset as the shared core `CommonSettings`, whose
// field names + chart-channel vocabulary are Android's. These two helpers map
// between them; the web-only extras (below) ride alongside, untouched.

/** Web `show*` boolean ↔ canonical chart-channel key. Note `showVolume` maps to
 *  the canonical `dispensedVolume` (Android's key), not `volume`. */
const CHART_FLAG_TO_KEY: ReadonlyArray<readonly [keyof Settings, string]> = [
	['showPressure', 'pressure'],
	['showResistance', 'resistance'],
	['showFlow', 'flow'],
	['showVolume', 'dispensedVolume'],
	['showHeadTemp', 'headTemp'],
	['showMixTemp', 'mixTemp'],
	['showWeight', 'weight'],
	['showWeightFlow', 'weightFlow']
];

/** The web-only settings that are NOT part of the cross-shell `CommonSettings` —
 *  persisted alongside `common` and `_shell:'web'`-tagged in a backup. */
const PLATFORM_KEYS = [
	'density',
	'screensaver',
	'telemetryRateHz',
	'lineFrequencyHz',
	'webhookEnabled',
	'webhookUrl',
	'webhookEvents',
	'smoothPressure'
] as const satisfies ReadonlyArray<keyof Settings>;

/** The web-only settings (the non-`CommonSettings` extras) as a plain record —
 *  for the persisted nested shape + the backup's `_shell:'web'` platform blob. */
export function settingsPlatformExtras(s: Settings): Record<string, unknown> {
	const out: Record<string, unknown> = {};
	for (const k of PLATFORM_KEYS) out[k] = s[k];
	return out;
}

/** Project the flat `Settings` onto the shared cross-shell {@link CommonSettings}. */
export function settingsToCommon(s: Settings): CommonSettings {
	return {
		themeMode: s.theme,
		maxShotDurationS: s.maxShotDurationS,
		autoTare: s.autoTareOnShotStart,
		stopOnWeight: s.stopOnWeight,
		volumeStopWithScale: s.volumeStopWithScale,
		requireScale: s.requireScale,
		steamEco: s.steamEcoMode,
		steamTwoTap: s.steamTwoTapStop,
		fanThresholdC: s.fanThresholdC,
		usbChargingMode: s.usbChargingMode,
		preFlush: s.groupFlushBeforeShot,
		steamPurge: s.autoPurgeAfterSteam,
		weightUnit: s.weightUnit,
		tempUnit: s.tempUnit,
		pressureUnit: s.pressureUnit,
		volumeUnit: s.volumeUnit,
		waterLevelUnit: s.waterLevelUnit,
		waterWarnMl: s.waterWarnMl ?? undefined,
		waterRefillPointMm: s.waterRefillPointMm ?? undefined,
		chartChannels: CHART_FLAG_TO_KEY.filter(([flag]) => s[flag]).map(([, key]) => key),
		keepScreenOnBrew: s.keepScreenOnBrew,
		// Only an explicit choice travels — unset stays unset.
		brewCueSound: s.brewCueSound ?? undefined,
		brewCueHaptics: s.brewCueHaptics ?? undefined,
		showDebugPanel: s.showDebugPanel,
		defaultDoseG: s.defaultDoseG,
		defaultRatio: s.defaultRatio,
		defaultBrewTempC: s.defaultBrewTempC,
		defaultPreinfuseS: s.defaultPreinfusionS,
		grinderModel: s.grinderModel,
		baristaName: s.baristaName.trim() || undefined,
		// Retired (presence follows activity; the app owns sleep) — still
		// written because older versions require the field in a backup.
		suppressDe1Sleep: true,
		idleSleepMin: s.idleSleepMin,
		qcSteamTimeS: s.qcSteamTimeS,
		qcSteamFlowMlS: s.qcSteamFlowMlS,
		qcSteamTempC: s.qcSteamTempC,
		qcHotWaterTempC: s.qcHotWaterTempC,
		qcHotWaterVolumeMl: s.qcHotWaterVolumeMl,
		qcHotWaterMode: s.qcHotWaterMode ?? undefined,
		qcHotWaterTimeS: s.qcHotWaterTimeS ?? undefined,
		qcHotWaterTimeNoLimit: s.qcHotWaterTimeNoLimit ?? undefined,
		qcHotWaterRatio: s.qcHotWaterRatio ?? undefined,
		qcFlushTimeS: s.qcFlushTimeS,
		qcFlushTempC: s.qcFlushTempC
	};
}

/** A {@link CommonSettings} after the core's sanitize: the "unset reads as"
 *  fields are always present. */
type SanitizedCommon = CommonSettings &
	Required<
		Pick<
			CommonSettings,
			| 'volumeStopWithScale'
			| 'requireScale'
			| 'steamTwoTap'
			| 'fanThresholdC'
			| 'waterLevelUnit'
			| 'usbChargingMode'
		>
	>;

/** Read any settings blob through the core's `sanitize_common_settings`. */
export function sanitizeCommon(blob: unknown): SanitizedCommon {
	return JSON.parse(sanitizeCommonSettingsJson(JSON.stringify(blob ?? null))) as SanitizedCommon;
}

/** Overlay a restored {@link CommonSettings} onto a flat `Settings`, preserving
 *  the web-only platform fields. `themeMode` keeps `light` / `system`; anything
 *  else reads as the default `dark`. */
export function applyCommonToSettings(cIn: CommonSettings, s: Settings): Settings {
	// The core's sanitize (`sanitize_common_settings`): a partial blob (an older
	// backup predating a field) fills from the default bundle, a wrong-typed
	// field falls back on its own, and bad values are repaired. It also
	// resolves the "unset reads as" fields, so none are defaulted here.
	const c = sanitizeCommon(cIn);
	const on = new Set(c.chartChannels);
	return {
		...s,
		theme: c.themeMode === 'light' || c.themeMode === 'system' ? c.themeMode : 'dark',
		maxShotDurationS: c.maxShotDurationS,
		autoTareOnShotStart: c.autoTare,
		stopOnWeight: c.stopOnWeight,
		volumeStopWithScale: c.volumeStopWithScale,
		steamEcoMode: c.steamEco,
		requireScale: c.requireScale,
		steamTwoTapStop: c.steamTwoTap,
		fanThresholdC: c.fanThresholdC,
		usbChargingMode: parseUsbChargingMode(c.usbChargingMode),
		groupFlushBeforeShot: c.preFlush,
		autoPurgeAfterSteam: c.steamPurge,
		weightUnit: c.weightUnit as WeightUnit,
		tempUnit: c.tempUnit as TempUnit,
		pressureUnit: c.pressureUnit as PressureUnit,
		volumeUnit: c.volumeUnit as VolumeUnit,
		waterLevelUnit: c.waterLevelUnit as WaterLevelUnit,
		waterWarnMl: c.waterWarnMl ?? null,
		waterRefillPointMm: c.waterRefillPointMm ?? null,
		showPressure: on.has('pressure'),
		showResistance: on.has('resistance'),
		showFlow: on.has('flow'),
		showVolume: on.has('dispensedVolume'),
		showHeadTemp: on.has('headTemp'),
		showMixTemp: on.has('mixTemp'),
		showWeight: on.has('weight'),
		showWeightFlow: on.has('weightFlow'),
		keepScreenOnBrew: c.keepScreenOnBrew,
		brewCueSound: c.brewCueSound ?? null,
		brewCueHaptics: c.brewCueHaptics ?? null,
		showDebugPanel: c.showDebugPanel,
		defaultDoseG: c.defaultDoseG,
		defaultRatio: c.defaultRatio,
		defaultBrewTempC: c.defaultBrewTempC,
		defaultPreinfusionS: c.defaultPreinfuseS,
		grinderModel: c.grinderModel,
		baristaName: c.baristaName ?? '',
		// A blob from before the idle timer carries none: keep this device's.
		idleSleepMin: c.idleSleepMin ?? s.idleSleepMin,
		qcSteamTimeS: c.qcSteamTimeS,
		qcSteamFlowMlS: c.qcSteamFlowMlS,
		qcSteamTempC: c.qcSteamTempC,
		qcHotWaterTempC: c.qcHotWaterTempC,
		qcHotWaterVolumeMl: c.qcHotWaterVolumeMl,
		qcHotWaterMode: c.qcHotWaterMode ?? null,
		qcHotWaterTimeS: c.qcHotWaterTimeS ?? null,
		qcHotWaterTimeNoLimit: c.qcHotWaterTimeNoLimit ?? null,
		qcHotWaterRatio: c.qcHotWaterRatio ?? null,
		qcFlushTimeS: c.qcFlushTimeS,
		qcFlushTempC: c.qcFlushTempC
	};
}

/** The stored hot-water fields of `s`, for the core's migration rule. */
function storedHotWater(s: Settings): StoredHotWater {
	return {
		mode: s.qcHotWaterMode ?? undefined,
		volumeMl: s.qcHotWaterVolumeMl,
		timeS: s.qcHotWaterTimeS ?? undefined,
		timeNoLimit: s.qcHotWaterTimeNoLimit ?? undefined,
		ratio: s.qcHotWaterRatio ?? undefined
	};
}

/** The downgrade marker (see {@link HOT_WATER_MARKER_KEY}), or `null`. */
function readHotWaterMarker(): StoredHotWater | null {
	return readJson<StoredHotWater | null>(HOT_WATER_MARKER_KEY, null, {
		what: 'hot-water marker',
		valid: isJsonObject
	});
}

/**
 * The hot-water dose `s` holds — the core's rule (`initial_hot_water_dose`):
 * a stored mode wins; with none, the `marker` (the device-local copy) when
 * given; else never-changed (no volume, or the old 150 ml default) gives
 * ratio 1:3 and any other volume gives volume mode. Pure: nothing is written.
 */
export function hotWaterDoseOf(s: Settings, marker: StoredHotWater | null = null): HotWaterDose {
	const markerJson = s.qcHotWaterMode == null && marker ? JSON.stringify(marker) : undefined;
	return JSON.parse(initialHotWaterDose(JSON.stringify(storedHotWater(s)), markerJson)) as HotWaterDose;
}

/**
 * The hot-water dose `s` holds, reading the device-local marker when `s`
 * carries no mode — what {@link SettingsStore.hotWaterDose} returns, for
 * readers that hold a `Settings` (the connect sweep).
 */
export function settingsHotWaterDose(s: Settings): HotWaterDose {
	return hotWaterDoseOf(s, s.qcHotWaterMode == null ? readHotWaterMarker() : null);
}

/** `s` holding `dose` (its mode stored). */
function withHotWaterDose(s: Settings, dose: HotWaterDose): Settings {
	return {
		...s,
		qcHotWaterMode: dose.mode,
		qcHotWaterTimeS: dose.timeS,
		qcHotWaterTimeNoLimit: dose.timeNoLimit,
		qcHotWaterVolumeMl: dose.volumeMl,
		qcHotWaterRatio: dose.ratio
	};
}

/**
 * Effective guided-brew cue sound — the explicit choice, else the core's
 * never-chosen default (`DEFAULT_BREW_CUE_SOUND`, off). Call after
 * `loadCore()` (the cue surfaces only render once it has run).
 */
export function brewCueSoundOn(s: Pick<Settings, 'brewCueSound'>): boolean {
	return s.brewCueSound ?? defaultBrewCueSound();
}

/** Effective guided-brew cue haptics — the explicit choice, else the core's
 *  never-chosen default (`DEFAULT_BREW_CUE_HAPTICS`, on). */
export function brewCueHapticsOn(s: Pick<Settings, 'brewCueHaptics'>): boolean {
	return s.brewCueHaptics ?? defaultBrewCueHaptics();
}

/**
 * Load the flat `Settings` from localStorage. The persisted shape is now
 * `{ common: CommonSettings, <platform extras> }` — "storage IS the shared
 * shape". A pre-unification FLAT blob (no `common` key) is read as-is and
 * rewritten nested on the next persist.
 */
function loadSettings(): Settings {
	const stored = readJson<Record<string, unknown> | null>(SETTINGS_KEY, null, {
		what: 'settings',
		valid: isJsonObject
	});
	if (stored && typeof stored === 'object' && stored.common) {
		const platform: Partial<Settings> = {};
		for (const k of PLATFORM_KEYS) {
			if (k in stored) (platform as Record<string, unknown>)[k] = stored[k];
		}
		return applyCommonToSettings(stored.common as CommonSettings, {
			...DEFAULT_SETTINGS,
			...platform
		});
	}
	// Pre-unification FLAT blob (no `common`). The next persist() rewrites this key
	// into the nested shape, which DROPS the legacy Visualizer upload options that
	// used to live here (`visualizerAutoUpload` / `visualizerPrivacy` / …). The
	// Visualizer-prefs migration reads them straight from this same localStorage key
	// — so trigger it now (idempotent), before the rewrite, so a user's upload prefs
	// can't be lost to a settings write that happens before the first sync read.
	if (stored && typeof stored === 'object' && 'visualizerAutoUpload' in stored) {
		readSyncConfig();
	}
	return { ...DEFAULT_SETTINGS, ...((stored as Partial<Settings> | null) ?? {}) };
}

/**
 * Apply the persisted theme to the document — sets (or, for the default dark,
 * leaves) `data-theme` on `<html>`. Called once at module load so a reload
 * paints the right theme before the first frame, and again on every change.
 */
const PREFERS_LIGHT = '(prefers-color-scheme: light)';

function prefersLight(): boolean {
	return typeof matchMedia === 'function' && matchMedia(PREFERS_LIGHT).matches;
}

/**
 * The browser chrome colour (`<meta name="theme-color">`, the Android / PWA
 * status bar) per resolved theme. `static/theme-boot.js` applies the same
 * values before first paint (final cohort review 07 N11).
 */
export const THEME_COLOR = { dark: '#1e1b16', light: '#faf7f1' } as const;

function applyTheme(theme: ThemePref): void {
	if (typeof document === 'undefined') return;
	const resolved = resolveTheme(theme, prefersLight());
	document.documentElement.setAttribute('data-theme', resolved);
	document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLOR[resolved]);
}

/**
 * The reactive app-preferences store. One instance per app —
 * {@link getSettingsStore}.
 */
export class SettingsStore {
	/**
	 * The current preferences — reactive; assigning a field re-renders readers.
	 * Loaded from localStorage, with {@link DEFAULT_SETTINGS} filling any field
	 * a stored older bundle is missing (forward-compatible merge).
	 */
	current = $state.raw<Settings>(loadSettings());

	constructor() {
		// Paint the persisted theme straight away — the store is created the
		// first time any page reads it, which on `/settings` is at module init.
		applyTheme(this.current.theme);
		// `system` follows the OS live: re-apply when the device flips between
		// light and dark (the store is an app-lifetime singleton).
		if (typeof matchMedia === 'function') {
			matchMedia(PREFERS_LIGHT).addEventListener('change', () => {
				if (this.current.theme === 'system') applyTheme('system');
			});
		}
	}

	/** Persist the preference bundle to localStorage in the nested cross-shell
	 *  shape: the shared `common` block + the web-only platform extras. */
	private persist(): void {
		writeJson(SETTINGS_KEY, {
			common: settingsToCommon(this.current),
			...settingsPlatformExtras(this.current)
		});
		// Every save of a stored dose rewrites the downgrade marker.
		if (this.current.qcHotWaterMode != null) {
			writeJson(HOT_WATER_MARKER_KEY, storedHotWater(this.current));
		}
	}

	/**
	 * The hot-water dose — the only way to read it (the card, the push, the
	 * connect sweep, the backup export). Before the start-up migration a
	 * record without a mode reads through the marker and the core's rule;
	 * nothing is written here, so this is safe from a render.
	 */
	hotWaterDose(): HotWaterDose {
		return settingsHotWaterDose(this.current);
	}

	/**
	 * Store the core's answer for a record without a hot-water mode, once:
	 * afterwards a stored mode always exists, so the never-changed rule never
	 * runs again (a later volume-mode 150 ml is a choice and is kept). Call at
	 * start-up right after the wasm core loads — never from a render, where
	 * the write would be a state mutation Svelte refuses.
	 */
	migrateHotWater(): void {
		if (this.current.qcHotWaterMode != null) return;
		this.current = withHotWaterDose(this.current, this.hotWaterDose());
		this.persist();
	}

	/** Store a new hot-water dose (mode and every mode's value) in one write. */
	setHotWaterDose(dose: HotWaterDose): void {
		this.current = withHotWaterDose(this.current, dose);
		this.persist();
	}

	/**
	 * Update one preference and persist. The theme key additionally re-applies
	 * `data-theme` on `<html>` so the switch is immediate and end-to-end.
	 */
	set<K extends keyof Settings>(key: K, value: Settings[K]): void {
		this.current = { ...this.current, [key]: value };
		if (key === 'theme') applyTheme(this.current.theme);
		this.persist();
	}

	/**
	 * Replace the whole preference bundle in a single write — for a backup restore
	 * that has already assembled the full target {@link Settings}. Avoids the
	 * per-key `set()` fan-out (one `persist()` instead of ~30 localStorage writes).
	 */
	replaceAll(next: Settings): void {
		// A backup with no hot-water mode goes through the core's rule — its
		// own fields, never this device's marker.
		this.current = next.qcHotWaterMode == null ? withHotWaterDose(next, hotWaterDoseOf(next)) : next;
		applyTheme(this.current.theme);
		this.persist();
		this.onReplaced?.();
	}

	/**
	 * Called after the whole bundle changes ({@link replaceAll}, {@link reset}):
	 * a backup restore, a held change applied, Restore defaults. The app
	 * registers its settings push here (`CremaApp.pushSettings`), so every
	 * bundle write reaches the core without each caller knowing the app.
	 */
	onReplaced: (() => void) | null = null;

	/**
	 * Restore every preference to its default and persist — the core's rule
	 * (`reset_settings`): the weight unit and the mains line frequency are
	 * facts about the user's setup and stay. Profiles and shot history live in
	 * their own stores and are untouched.
	 */
	reset(): void {
		const kept = JSON.parse(
			resetSettingsJson(JSON.stringify(settingsToCommon(this.current)), this.current.lineFrequencyHz)
		) as SettingsReset;
		const hz = kept.lineFrequencyHz;
		this.current = applyCommonToSettings(kept.common, {
			...DEFAULT_SETTINGS,
			lineFrequencyHz: hz === 50 || hz === 60 ? hz : 0
		});
		applyTheme(this.current.theme);
		this.persist();
		this.onReplaced?.();
	}
}

/** The process-wide singleton — one preference bundle shared by every route. */
let store: SettingsStore | undefined;

/** Get the shared {@link SettingsStore}, creating it on first call. */
export function getSettingsStore(): SettingsStore {
	if (!store) store = new SettingsStore();
	return store;
}
