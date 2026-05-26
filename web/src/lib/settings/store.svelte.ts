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
	/** Run a short flush after steaming. */
	autoPurgeAfterSteam: boolean;
	/** Flush the group before each shot. */
	groupFlushBeforeShot: boolean;
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
	 * Global maximum shot duration, seconds — a safety guardrail when
	 * neither SAW nor SAV is configured. `0` means "no max." Default
	 * 60 s (legacy `espresso_max_time` default). Pushed to the core via
	 * `setMaxShotDuration`. Settings-only because neither de1app nor
	 * reaprime carry this per-profile.
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

	// ── Sharing (Visualizer) ─────────────────────────────────────────────
	/** Auto-upload finished shots to Visualizer. */
	visualizerAutoUpload: boolean;
	/** Default privacy for uploaded shots. */
	visualizerPrivacy: SharingPrivacy;
	/** Send the profile JSON alongside each shot. */
	visualizerIncludeProfile: boolean;
	/** Send tasting notes alongside each shot. */
	visualizerIncludeNotes: boolean;

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
	/**
	 * Which format the History page's Download (per-shot) + Export
	 * (bulk) actions emit:
	 *
	 * - `'community'` (default) — community v2 `.shot.json` — portable
	 *   across reaprime / Visualizer / de1app, pre-decoded telemetry,
	 *   user-readable.
	 * - `'replay'` — Crema's raw BLE capture (`.jsonl`) — every wire
	 *   byte preserved, bit-exact playback through Crema's Replay
	 *   tool. Crema-only; choose when sharing for debugging / bug
	 *   reports.
	 *
	 * Only the per-shot Download follows this setting today; the bulk
	 * Export remains community v2 JSONL because raw-capture bulk
	 * export needs separate plumbing (per-shot IndexedDB capture
	 * lookups).
	 */
	shotExportFormat: 'community' | 'replay';
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
	autoPurgeAfterSteam: true,
	groupFlushBeforeShot: false,
	autoTareOnShotStart: true,
	stopOnWeight: true,
	maxShotDurationS: 60,

	telemetryRateHz: 50,
	lineFrequencyHz: 0,
	suppressDe1Sleep: true,

	grinderModel: '',

	// preserved for future visualizer.coffee upload queue — not currently read anywhere
	visualizerAutoUpload: true,
	visualizerPrivacy: 'unlisted',
	visualizerIncludeProfile: true,
	visualizerIncludeNotes: false,

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
	showDebugPanel: false,
	shotExportFormat: 'community'
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
	current = $state.raw<Settings>({
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
