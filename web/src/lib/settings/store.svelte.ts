/**
 * `$lib/settings/store` — the app-preferences store.
 *
 * Backs the `/settings` page. Crema's web shell is a static, client-only PWA —
 * there is no server, and (per the project's framing) there is no Crema
 * account either — so `localStorage` is the right home for user preferences.
 * This store mirrors `lib/profiles` and `lib/history`: a Svelte 5 `$state`
 * class, the shared `$lib/utils/storage` helpers, a single versioned key.
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

import { defaultBrewDefaults } from '$lib/wasm/de1_wasm';
import { readJson, writeJson } from '$lib/utils/storage';
import { readSyncConfig } from '$lib/visualizer/sync-config';
import type { CommonSettings } from '$lib/core/crema-core';

/** localStorage key for the bundle of app preferences ({@link Settings}). */
const SETTINGS_KEY = 'crema.settings.v1';

/**
 * The core's out-of-box brew defaults (`{doseG, ratio, brewTempC, preinfusionS}`),
 * parsed once on first read and memoized. {@link DEFAULT_SETTINGS} surfaces these
 * through getters so the seed values live in the Rust core, not hardcoded here.
 *
 * Lazy — *never* at module scope: the wasm export is only callable once
 * `loadCore()` has run (the rule `$lib/profiles/bounds.ts` follows). The getters
 * fire when the settings store is first constructed at render time, long after
 * boot. See `de1_domain::default_brew_defaults_json`.
 */
interface RawBrewDefaults {
	doseG: number;
	ratio: number;
	brewTempC: number;
	preinfusionS: number;
}
let rawBrewDefaults: RawBrewDefaults | null = null;
const brewDefaults = (): RawBrewDefaults =>
	(rawBrewDefaults ??= JSON.parse(defaultBrewDefaults()) as RawBrewDefaults);

/** Light/dark theme. The design ships dark; dark stays the default. */
export type ThemePref = 'dark' | 'light';

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
	 * Keep the DE1 awake while Crema is open and the user is interacting.
	 * `true` (default): Crema sets `FeatureFlags.UserNotPresent = 0` on the
	 * DE1 and sends a `UserPresent` heartbeat on every user touch
	 * (debounced to once per minute). The DE1 will only sleep if Crema
	 * goes 30+ minutes without any interaction.
	 * `false`: Crema sets `FeatureFlags.UserNotPresent = 1` and stops the
	 * heartbeat; the DE1 follows its own internal sleep timer regardless
	 * of tablet activity. Useful for shared / café machines.
	 */
	suppressDe1Sleep: boolean;
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
	 * Hold a Screen Wake Lock while a shot is pulling so the display can't
	 * sleep mid-extraction. Consumed by the shell layout's effect via
	 * `$lib/shell/wake-lock`; a no-op on browsers without the API.
	 */
	keepScreenOnBrew: boolean;

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
	/** Hot-water volume, ml. */
	qcHotWaterVolumeMl: number;
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

/** The default preference bundle — every field's out-of-box value. */
export const DEFAULT_SETTINGS: Settings = {
	theme: 'dark',
	density: 'comfortable',
	screensaver: true,
	weightUnit: 'g',
	tempUnit: 'C',
	volumeUnit: 'ml',
	pressureUnit: 'bar',
	waterLevelUnit: 'ml',

	get defaultDoseG() {
		return brewDefaults().doseG;
	},
	get defaultRatio() {
		return brewDefaults().ratio;
	},
	get defaultBrewTempC() {
		return brewDefaults().brewTempC;
	},
	get defaultPreinfusionS() {
		return brewDefaults().preinfusionS;
	},
	autoPurgeAfterSteam: false,
	groupFlushBeforeShot: false,
	steamEcoMode: false,
	autoTareOnShotStart: true,
	stopOnWeight: true,
	volumeStopWithScale: false,
	requireScale: false,
	maxShotDurationS: 0,

	telemetryRateHz: 50,
	lineFrequencyHz: 0,
	suppressDe1Sleep: true,
	steamTwoTapStop: false,
	fanThresholdC: 55,

	grinderModel: '',

	keepScreenOnBrew: false,

	// Quick-Controls steam / hot-water / flush — match DEFAULT_BREW_PARAMS so a
	// fresh install seeds the Quick Sheet identically to before persistence.
	qcSteamTimeS: 12,
	qcSteamFlowMlS: 1.2,
	qcSteamTempC: 148,
	qcHotWaterTempC: 80,
	qcHotWaterVolumeMl: 150,
	qcFlushTimeS: 4,
	qcFlushTempC: 95,

	// Chart channels — only Pressure, Flow, and Weight default on; the rest
	// are opt-in via the Quick Sheet's "Chart" toggles.
	showPressure: true,
	showResistance: false,
	showFlow: true,
	showVolume: false,
	showHeadTemp: false,
	showMixTemp: false,
	showWeight: true,
	showWeightFlow: false,

	// Webhooks — off by default; user opts in from Advanced → Webhooks.
	webhookEnabled: false,
	webhookUrl: '',
	webhookEvents: {
		shotCompleted: true,
		machineError: true,
		profileUploaded: false,
		scaleConnected: false,
		de1Connected: false
	},

	smoothPressure: true,
	showDebugPanel: false
};

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
		preFlush: s.groupFlushBeforeShot,
		steamPurge: s.autoPurgeAfterSteam,
		weightUnit: s.weightUnit,
		tempUnit: s.tempUnit,
		pressureUnit: s.pressureUnit,
		volumeUnit: s.volumeUnit,
		waterLevelUnit: s.waterLevelUnit,
		chartChannels: CHART_FLAG_TO_KEY.filter(([flag]) => s[flag]).map(([, key]) => key),
		keepScreenOnBrew: s.keepScreenOnBrew,
		showDebugPanel: s.showDebugPanel,
		defaultDoseG: s.defaultDoseG,
		defaultRatio: s.defaultRatio,
		defaultBrewTempC: s.defaultBrewTempC,
		defaultPreinfuseS: s.defaultPreinfusionS,
		grinderModel: s.grinderModel,
		suppressDe1Sleep: s.suppressDe1Sleep,
		qcSteamTimeS: s.qcSteamTimeS,
		qcSteamFlowMlS: s.qcSteamFlowMlS,
		qcSteamTempC: s.qcSteamTempC,
		qcHotWaterTempC: s.qcHotWaterTempC,
		qcHotWaterVolumeMl: s.qcHotWaterVolumeMl,
		qcFlushTimeS: s.qcFlushTimeS,
		qcFlushTempC: s.qcFlushTempC
	};
}

/** Overlay a restored {@link CommonSettings} onto a flat `Settings`, preserving
 *  the web-only platform fields. `themeMode: "system"` (Android-only) coerces to
 *  `dark` since the web shell has just light/dark. */
export function applyCommonToSettings(cIn: CommonSettings, s: Settings): Settings {
	// Forward-compat: tolerate a partial blob (an older backup predating a field)
	// by filling any missing field from the canonical web defaults.
	const c: CommonSettings = { ...settingsToCommon(DEFAULT_SETTINGS), ...cIn };
	const on = new Set(c.chartChannels);
	return {
		...s,
		theme: c.themeMode === 'light' ? 'light' : 'dark',
		maxShotDurationS: c.maxShotDurationS,
		autoTareOnShotStart: c.autoTare,
		stopOnWeight: c.stopOnWeight,
		// Optional in the shared shape (additive-field tolerance): an older
		// backup / another shell omitting it reads as "off".
		volumeStopWithScale: c.volumeStopWithScale ?? false,
		steamEcoMode: c.steamEco,
		// Nullable additive fields: older blobs read as the canonical defaults.
		requireScale: c.requireScale ?? false,
		steamTwoTapStop: c.steamTwoTap ?? false,
		fanThresholdC: c.fanThresholdC ?? 55,
		groupFlushBeforeShot: c.preFlush,
		autoPurgeAfterSteam: c.steamPurge,
		weightUnit: c.weightUnit as WeightUnit,
		tempUnit: c.tempUnit as TempUnit,
		pressureUnit: c.pressureUnit as PressureUnit,
		volumeUnit: c.volumeUnit as VolumeUnit,
		waterLevelUnit: (c.waterLevelUnit ?? 'ml') as WaterLevelUnit,
		showPressure: on.has('pressure'),
		showResistance: on.has('resistance'),
		showFlow: on.has('flow'),
		showVolume: on.has('dispensedVolume'),
		showHeadTemp: on.has('headTemp'),
		showMixTemp: on.has('mixTemp'),
		showWeight: on.has('weight'),
		showWeightFlow: on.has('weightFlow'),
		keepScreenOnBrew: c.keepScreenOnBrew,
		showDebugPanel: c.showDebugPanel,
		defaultDoseG: c.defaultDoseG,
		defaultRatio: c.defaultRatio,
		defaultBrewTempC: c.defaultBrewTempC,
		defaultPreinfusionS: c.defaultPreinfuseS,
		grinderModel: c.grinderModel,
		suppressDe1Sleep: c.suppressDe1Sleep,
		qcSteamTimeS: c.qcSteamTimeS,
		qcSteamFlowMlS: c.qcSteamFlowMlS,
		qcSteamTempC: c.qcSteamTempC,
		qcHotWaterTempC: c.qcHotWaterTempC,
		qcHotWaterVolumeMl: c.qcHotWaterVolumeMl,
		qcFlushTimeS: c.qcFlushTimeS,
		qcFlushTempC: c.qcFlushTempC
	};
}

/**
 * Load the flat `Settings` from localStorage. The persisted shape is now
 * `{ common: CommonSettings, <platform extras> }` — "storage IS the shared
 * shape". A pre-unification FLAT blob (no `common` key) is read as-is and
 * rewritten nested on the next persist.
 */
function loadSettings(): Settings {
	const stored = readJson<Record<string, unknown> | null>(SETTINGS_KEY, null);
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
function applyTheme(theme: ThemePref): void {
	if (typeof document === 'undefined') return;
	document.documentElement.setAttribute('data-theme', theme);
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
	}

	/** Persist the preference bundle to localStorage in the nested cross-shell
	 *  shape: the shared `common` block + the web-only platform extras. */
	private persist(): void {
		writeJson(SETTINGS_KEY, {
			common: settingsToCommon(this.current),
			...settingsPlatformExtras(this.current)
		});
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
		this.current = next;
		applyTheme(this.current.theme);
		this.persist();
	}

	/**
	 * Restore every preference to its default and persist. Profiles and shot
	 * history live in their own stores and are untouched.
	 */
	reset(): void {
		this.current = { ...DEFAULT_SETTINGS };
		applyTheme(this.current.theme);
		this.persist();
	}
}

/** The process-wide singleton — one preference bundle shared by every route. */
let store: SettingsStore | undefined;

/** Get the shared {@link SettingsStore}, creating it on first call. */
export function getSettingsStore(): SettingsStore {
	if (!store) store = new SettingsStore();
	return store;
}
