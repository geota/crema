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

import { readJson, writeJson } from '$lib/utils/storage';

/** localStorage key for the bundle of app preferences ({@link Settings}). */
const SETTINGS_KEY = 'crema.settings.v1';

/** Light/dark theme. The design ships dark; dark stays the default. */
export type ThemePref = 'dark' | 'light';

/** Unit choices, one per measured quantity. */
export type WeightUnit = 'g' | 'oz';
export type TempUnit = 'C' | 'F';
export type VolumeUnit = 'ml' | 'floz';
export type PressureUnit = 'bar' | 'psi';

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

	// ── Brew defaults ────────────────────────────────────────────────────
	/** Default dose for new profiles, grams. */
	defaultDoseG: number;
	/** Default yield-to-dose ratio (the `x` in `1:x`). */
	defaultRatio: number;
	/** Default group temperature, °C. */
	defaultBrewTempC: number;
	/** Default pre-infusion time, seconds. */
	defaultPreinfusionS: number;
	/** Stop the shot at target yield. */
	stopOnWeight: boolean;
	/** Zero the scale when a shot starts. */
	autoTare: boolean;
	/** Run a short flush after steaming. */
	autoPurgeAfterSteam: boolean;
	/** Flush the group before each shot. */
	groupFlushBeforeShot: boolean;

	// ── Sound & feedback ─────────────────────────────────────────────────
	/** Soft tone when extraction begins. */
	shotStartTone: boolean;
	/** Tone when stop-on-weight triggers. */
	shotEndTone: boolean;
	/** Chime for maintenance reminders. */
	maintenanceReminders: boolean;
	/** Cue volume, 0–100 %. */
	volumePercent: number;

	// ── Machine ──────────────────────────────────────────────────────────
	/** Reconnect the last-used DE1 on launch. App preference. */
	autoConnectOnLaunch: boolean;
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

	// ── Sharing (Visualizer) ─────────────────────────────────────────────
	/** Auto-upload finished shots to Visualizer. */
	visualizerAutoUpload: boolean;
	/** Default privacy for uploaded shots. */
	visualizerPrivacy: SharingPrivacy;
	/** Send the profile JSON alongside each shot. */
	visualizerIncludeProfile: boolean;
	/** Send tasting notes alongside each shot. */
	visualizerIncludeNotes: boolean;

	// ── Advanced ─────────────────────────────────────────────────────────
	/** Show the flow curve on the live chart. */
	showFlowCurve: boolean;
	/** Show the estimated puck-resistance trace. */
	showPuckResistance: boolean;
	/** Smooth the pressure curve. */
	smoothPressure: boolean;
	/** Show a debug / event-log panel (BLE diagnostics). */
	showDebugPanel: boolean;
	/**
	 * Which format the History page's Download (per-shot) + Export (bulk)
	 * actions emit:
	 *
	 * - `'v2'` (default) — community v2 `.shot.json` — portable across
	 *   reaprime / Visualizer / de1app, pre-decoded telemetry,
	 *   user-readable.
	 * - `'jsonl'` — Crema's raw BLE capture (`.jsonl`) — bit-exact
	 *   replay, retains every byte for full-fidelity round-trip but
	 *   Crema-only. Choose this when sharing for debugging.
	 *
	 * Only the per-shot Download follows this setting today; the bulk
	 * Export remains v2 JSONL because raw-capture bulk export needs
	 * separate plumbing (per-shot IndexedDB capture lookups).
	 */
	shotExportFormat: 'v2' | 'jsonl';
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

	defaultDoseG: 18.0,
	defaultRatio: 2.0,
	defaultBrewTempC: 93.0,
	defaultPreinfusionS: 8,
	stopOnWeight: true,
	autoTare: true,
	autoPurgeAfterSteam: true,
	groupFlushBeforeShot: false,

	shotStartTone: true,
	shotEndTone: true,
	maintenanceReminders: true,
	volumePercent: 60,

	autoConnectOnLaunch: true,
	telemetryRateHz: 50,
	lineFrequencyHz: 0,
	suppressDe1Sleep: true,

	visualizerAutoUpload: true,
	visualizerPrivacy: 'unlisted',
	visualizerIncludeProfile: true,
	visualizerIncludeNotes: false,

	showFlowCurve: true,
	showPuckResistance: false,
	smoothPressure: true,
	showDebugPanel: false,
	shotExportFormat: 'v2'
};

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
	current = $state<Settings>({
		...DEFAULT_SETTINGS,
		...readJson<Partial<Settings>>(SETTINGS_KEY, {})
	});

	constructor() {
		// Paint the persisted theme straight away — the store is created the
		// first time any page reads it, which on `/settings` is at module init.
		applyTheme(this.current.theme);
	}

	/** Persist the preference bundle to localStorage. */
	private persist(): void {
		writeJson(SETTINGS_KEY, this.current);
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
