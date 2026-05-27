/**
 * `$lib/core` — the async facade over the `de1-wasm` `CremaBridge`.
 *
 * The Crema core is a sans-IO event processor: feed it raw BLE bytes and clock
 * ticks, get back a `CoreOutput` of observed `Event`s and `Command`s to
 * perform. `de1-wasm` exposes it as a wasm-bindgen `CremaBridge` whose
 * `CoreOutput`-producing methods return a JSON **string** (the "Option S"
 * encoding — every CoreOutput crosses as one JSON string). This module wraps that bridge so callers never see
 * the wasm or the JSON: every method parses the string into the typed
 * `CoreOutput` / `ScaleCapabilities` / `ScaleUuids` from `crema-core.ts`.
 *
 * ## Why every method is `async`
 *
 * The core runs on the main thread today — it is microsecond-scale work — so
 * each method resolves synchronously. They are nonetheless declared `async`
 * (return a `Promise`) on purpose: keeps the facade
 * swappable to a Web Worker later (a `postMessage` round-trip is inherently
 * async) with **zero caller changes**. `loadCore()` is genuinely async — it
 * dynamic-imports the wasm bundle and `await`s its `init()`.
 *
 * Mirrors the role of the Android shell's `CremaBridge` Kotlin binding.
 */

import type { CoreOutput, ScaleCapabilities, ScaleUuids } from './crema-core';
import type { CalTarget, MmrRegister, FirmwareUpdateStatus } from './crema-core';

/**
 * The web shell stores the user's weight pref as `'g' | 'oz'` (legacy
 * names baked into many components); the core's wire form is `'grams' |
 * 'ounces'` (the {@link import('./crema-core').WeightUnit} typeshare
 * enum). This is the one-line bridge converter — kept here so the rest
 * of the shell never has to think about the discrepancy.
 */
type ShellWeightUnit = 'g' | 'oz';
function weightUnitToWire(unit: ShellWeightUnit): 'grams' | 'ounces' {
	return unit === 'oz' ? 'ounces' : 'grams';
}

/**
 * Which quantity a profile step holds at its target — mirrors the core's
 * `Pump` enum (lowercase wire spelling per the community v2 JSON contract).
 */
export type ProfilePump = 'pressure' | 'flow';

/** How a profile step moves to its target — mirrors the core's `Transition`. */
export type ProfileTransition = 'fast' | 'smooth';

/**
 * Which temperature sensor a step regulates — mirrors `TempSensor`.
 * `'coffee'` = regulate the basket (head_temp); `'water'` = regulate
 * the water exiting the group (mix_temp). Lowercase wire spelling per
 * the community v2 contract.
 */
export type ProfileTempSensor = 'coffee' | 'water';

/** The metric an exit condition watches — mirrors `ExitMetric`. */
export type ProfileExitMetric = 'pressure' | 'flow';

/** The direction of an exit comparison — mirrors `Compare`. */
export type ProfileCompare = 'over' | 'under';

/**
 * What kind of beverage a profile produces — mirrors the core's
 * `BeverageType` enum. Lowercase wire spelling per the v2 contract.
 */
export type ProfileBeverageType =
	| 'espresso'
	| 'calibrate'
	| 'cleaning'
	| 'manual'
	| 'pourover';

/** An early-exit condition on a step — mirrors the core's `ExitCondition`. */
export interface ProfileExit {
	/** Which metric to watch. */
	metric: ProfileExitMetric;
	/** Exit above (`Over`) or below (`Under`) the threshold. */
	compare: ProfileCompare;
	/** The threshold value, bar or ml/s per `metric`. */
	threshold: number;
}

/** An advanced max-flow-or-pressure limiter — mirrors the core's `Limiter`. */
export interface ProfileLimiter {
	/** The limit value. */
	value: number;
	/** Tolerance band around the limit. */
	range: number;
}

/** One step of an espresso {@link Profile} — mirrors the core's `ProfileStep`. */
export interface ProfileStep {
	/** Human-readable step name. */
	name: string;
	/** Whether the step holds pressure or flow. */
	pump: ProfilePump;
	/** Target value — bar (pressure) or ml/s (flow), per `pump`. */
	target: number;
	/** Target temperature, °C. */
	temperature_c: number;
	/** Which temperature sensor the step regulates. */
	temp_sensor: ProfileTempSensor;
	/** How the step transitions to its target. */
	transition: ProfileTransition;
	/** Maximum step duration, seconds. */
	duration_seconds: number;
	/** Optional early-exit condition. */
	exit: ProfileExit | null;
	/** Per-step dispensed-volume limit, ml (0 = no limit). */
	volume_limit_ml: number;
	/** Optional advanced limiter. */
	limiter: ProfileLimiter | null;
	/**
	 * Optional per-step target weight, grams. `null` = no per-step
	 * target. Reaprime emits `null` explicitly for hash-stability;
	 * Crema preserves the Option<f32>.
	 */
	weight: number | null;
}

/**
 * An espresso profile — the JSON shape the wasm bridge's
 * `builtin_profiles_json()` produces (one element of its array). A faithful
 * mirror of the core's `de1_domain::Profile`; defined here rather than in the
 * typeshare-generated `crema-core.ts` because `Profile` is not a `#[typeshare]`
 * type — it only crosses the bridge as a JSON string.
 */
export interface Profile {
	/**
	 * Stable profile ID — a UUID v7 (RFC 9562) string. Built-ins ship
	 * with their ID baked into `core/de1-domain/profiles/builtin.json`;
	 * custom profiles get a fresh ID from {@link newProfileId}. Empty
	 * string ("") for profiles imported from the v2 community JSON
	 * format (which has no `id` field) — the shell mints a fresh ID
	 * for an imported profile before persisting it.
	 */
	id: string;
	/** Profile title. */
	title: string;
	/** Free-text notes. */
	notes: string;
	/** The ordered steps (1–32). */
	steps: ProfileStep[];
	/** How many leading steps count as preinfusion. */
	preinfuse_step_count: number;
	/** Minimum pressure for flow-priority steps, bar. */
	minimum_pressure: number;
	/** Maximum flow for pressure-priority steps, ml/s. */
	maximum_flow: number;
	/** Whole-shot dispensed-volume limit, ml (0 = no limit). */
	max_total_volume_ml: number;
	/**
	 * Desired final shot weight, grams (0 = no target). App-side metadata —
	 * the DE1 protocol has no target-weight field; it only round-trips
	 * through JSON. Mirrors the legacy `final_desired_shot_weight`.
	 */
	target_weight: number;
	/**
	 * Recommended dry coffee dose, grams (0 = unspecified). App-side metadata
	 * like `target_weight` — the DE1 protocol has no dose field; it only
	 * round-trips through JSON. Mirrors the legacy `profile_grinder_dose_weight`.
	 */
	dose: number;
	/** Profile author — free text, may be empty. */
	author: string;
	/** What kind of beverage the profile produces — defaults to `espresso`. */
	beverage_type: ProfileBeverageType;
	/** Target tank temperature, °C — `0` for "no override". */
	tank_temperature: number;
	/** Community v2 schema version — always `"2"` in this generation. */
	version: string;
}

/**
 * A `Duration` as serialized by the Rust core — a `u64` of milliseconds
 * via the `duration_ms` serde adapter. Used on the wire for
 * `StoredShot.record.duration` and every `TimedSample.elapsed`;
 * the shell holds the same shape so no boundary conversion is needed.
 */
export type RustDuration = number;

/** One telemetry sample as the Rust core emits it. */
export interface RustShotSample {
	sampleTime: number;
	groupPressure: number;
	groupFlow: number;
	headTemp: number;
	mixTemp: number;
	setHeadTemp: number;
	setMixTemp: number;
	setGroupPressure: number;
	setGroupFlow: number;
	frameNumber: number;
	steamTemp: number;
}

/** One timestamped sample in a `RustStoredShot.record.samples` array. */
export interface RustTimedSample {
	elapsed: RustDuration;
	sample: RustShotSample;
	/**
	 * Overlay channels added in the telemetry-bundle PR. Each rides on a
	 * `TimedSample` only when known: a shot pulled without a scale paired
	 * (and any legacy `.shot.json` parsed before this PR's exporter
	 * touched it) carries none. The Rust side emits these as a
	 * same-length series of floats in the v2 doc (`None` → `0.0`), so a
	 * re-import sees `Some(0.0)` for absent values; the shell treats both
	 * `undefined` and `0` as "no signal" for the resistance / scale
	 * fallback path. Wire shape is `camelCase` (Rust struct uses
	 * `#[serde(rename_all = "camelCase")]`).
	 */
	scaleWeight?: number;
	scaleFlowWeight?: number;
	dispensedVolume?: number;
	resistance?: number;
	resistanceWeight?: number;
}

/** Barista journal metadata as the Rust core emits it. */
export interface RustShotMetadata {
	dose: number | null;
	yieldOut: number | null;
	beans: string | null;
	grinderSetting: string | null;
	notes: string | null;
	rating: number | null;
	tds: number | null;
	extractionYield: number | null;
}

/**
 * The Rust-shape `StoredShot` the bridge returns from
 * `importLegacyTclShot` / `importV2JsonShot`. Field names match the
 * Rust struct's serde projection (`#[serde(rename_all = "camelCase")]`),
 * so this is the actual wire shape — not the Rust source-level names.
 * The shell maps this onto its own (`$lib/history` `StoredShot`) shape
 * at the import boundary.
 */
export interface RustStoredShot {
	formatVersion: number;
	completedAt: number;
	profile: Profile | null;
	stopReason: unknown | null;
	metadata: RustShotMetadata;
	record: {
		duration: RustDuration;
		samples: RustTimedSample[];
	};
}

/**
 * Which BLE characteristic an incoming notification came from. A plain string
 * union mirroring the wasm `NotificationSource` enum — the facade maps it to
 * the wasm enum internally so callers never import a wasm type.
 */
export type NotificationSource =
	| 'De1State'
	| 'De1ShotSample'
	| 'ScaleWeight'
	| 'ScaleCommand'
	| 'De1WaterLevels'
	| 'De1Version'
	| 'De1MmrRead'
	| 'De1Calibration'
	| 'De1ShotSettings'
	| 'De1ProfileHeader'
	| 'De1FrameAck';

/**
 * The async core facade. One instance owns one `CremaBridge`; obtain it from
 * {@link loadCore}. Methods that take raw bytes / produce a `CoreOutput`
 * mirror the bridge surface; `reset` is passed through.
 */
export interface CremaCore {
	/**
	 * Feed a raw GATT notification — `source` identifies the characteristic,
	 * `data` is the payload, `nowMs` is a `performance.now()` timestamp.
	 * Resolves to the parsed `CoreOutput`.
	 */
	onNotification(source: NotificationSource, data: Uint8Array, nowMs: number): Promise<CoreOutput>;
	/**
	 * The `nowMs` of the most recent `onNotification` call, or `null` before
	 * the first call. Lets the shell pin shot-slice bounds to the same
	 * timebase the rolling capture buffer uses — `performance.now()` during
	 * live BLE, the replay file's original timestamps during a replay — so
	 * `captureSliceJsonl` finds the right entries regardless of source.
	 */
	readonly lastNotificationAtMs: number | null;
	/** Feed a periodic clock tick. Resolves to the parsed `CoreOutput`. */
	onTick(nowMs: number): Promise<CoreOutput>;
	/** Discard all session state — e.g. on disconnect. */
	reset(): Promise<void>;
	/**
	 * Slice the rolling BLE-capture buffer to JSONL covering `[fromMs, toMs]`,
	 * with connect-phase identity entries + META prelude prepended. The shell
	 * persists the result to IndexedDB on every `ShotCompleted`; the same
	 * JSONL bytes are the format the Android `BleSessionRecorder`, the Rust
	 * `examples/replay.rs` tool, and the web `$lib/replay/capture` parser all
	 * consume. Empty when no notifications have been recorded.
	 */
	captureSliceJsonl(fromMs: number, toMs: number): Promise<string>;
	/**
	 * Drop every captured entry — called by the BLE manager on disconnect,
	 * where the live session is gone but the rest of the core (settings,
	 * history, the active profile, …) must stay intact. Distinct from
	 * {@link reset}, which wipes everything.
	 */
	captureClear(): Promise<void>;
	/**
	 * Enter replay mode: stash the live core into a hidden slot on the
	 * bridge and install a fresh {@link CremaCore} for the replay to run
	 * against. All subsequent {@link onNotification}, {@link captureSliceJsonl},
	 * {@link connectScale}, etc. calls target the shadow core.
	 *
	 * Rejects if a replay is already in progress (double-begin would lose
	 * the live core). Pair with {@link endReplay} in a try/finally.
	 *
	 * See `docs/48-replay-architecture-problem-statement.md` §4 for design.
	 */
	beginReplay(): Promise<void>;
	/**
	 * Leave replay mode: discard the replay-driven shadow core and
	 * restore the previously-stashed live core. Idempotent — safe to
	 * call from a `finally` whether or not {@link beginReplay} ran.
	 */
	endReplay(): Promise<void>;
	/** True while a replay shadow core is installed. */
	inReplay(): Promise<boolean>;
	/**
	 * Identify and connect a scale from its BLE advertised name. Resolves to
	 * the scale's display label, or `undefined` if the name matched no
	 * supported scale.
	 */
	connectScale(advertisedName: string): Promise<string | undefined>;
	/**
	 * What the connected scale can do beyond reporting a bare weight, or
	 * `undefined` when no scale is connected. Drives capability-gated UI.
	 */
	scaleCapabilities(): Promise<ScaleCapabilities | undefined>;
	/**
	 * The connected scale's BLE service / characteristic UUIDs, or `undefined`
	 * when no scale is connected — tells the BLE layer which Web Bluetooth
	 * characteristics to subscribe to.
	 */
	scaleUuids(): Promise<ScaleUuids | undefined>;
	/**
	 * Compare the most recently observed DE1 firmware version against the
	 * latest firmware Crema was compiled against. Pure read — no BLE traffic.
	 * Returns the `Unknown` variant until the DE1's `Version` characteristic
	 * has been observed.
	 */
	firmwareUpdateStatus(): Promise<FirmwareUpdateStatus>;
	/**
	 * Start uploading `profile` to the DE1. Resolves to the initial
	 * `CoreOutput` carrying `ProfileUploadStarted` + every BLE write
	 * command in upload order. Subsequent progress arrives via
	 * `ProfileUploadProgress` events on `De1FrameAck` notifications,
	 * success via `ProfileUploadCompleted`, failure via
	 * `ProfileUploadFailed`.
	 */
	uploadProfile(profile: unknown, nowMs: number): Promise<CoreOutput>;
	/** Cancel an in-progress upload; emits `ProfileUploadFailed { Aborted }`. */
	cancelProfileUpload(): Promise<CoreOutput>;
	/** `true` from `uploadProfile` until the tail ack / failure / cancel. */
	profileUploadInProgress(): Promise<boolean>;
	/**
	 * Name of the profile most recently uploaded successfully — the
	 * "active profile on the DE1" identity. `null` until the first
	 * successful upload; cleared by `reset`. (Bridge-side Rust method is
	 * `active_profile_title()` because the Rust value comes straight from
	 * `Profile.title`, the community-v2 JSON contract field. The web
	 * facade renames it to `Name` for UI clarity.)
	 */
	activeProfileName(): Promise<string | null>;
	/**
	 * Pin the AC mains frequency the volume integrator uses. `50` or `60`
	 * pins; `0` returns to auto-detect. (The wasm enum can't express
	 * `Option<f32>` cleanly so `0` is the auto sentinel.)
	 */
	setLineFrequencyOverride(hz: 0 | 50 | 60): Promise<void>;
	/**
	 * The effective AC mains frequency in use, Hz — the override if pinned,
	 * else the detector's locked value (after 1+ s of telemetry into a
	 * shot), else `null`.
	 */
	lineFrequencyHz(): Promise<number | null>;
	/**
	 * Parse a legacy de1app `.shot` (Tcl-dict) history file. Returns the
	 * resulting Rust-shape `StoredShot` parsed from the bridge's JSON
	 * reply. Throws with the importer's error message if parsing
	 * fails.
	 */
	importLegacyTclShot(content: string): Promise<RustStoredShot>;
	/**
	 * Parse a modern de1app v2 `.shot.json` history file. Same return
	 * convention as `importLegacyTclShot`.
	 */
	importV2JsonShot(content: string): Promise<RustStoredShot>;
	/**
	 * Parse a community-v2 `.json` profile file. Returns the Rust-shape
	 * `Profile` parsed from the bridge's JSON reply. Throws with the
	 * importer's error message on failure.
	 */
	importV2JsonProfile(content: string): Promise<Profile>;
	/** Parse a legacy de1app `.tcl` profile file. */
	importLegacyTclProfile(content: string): Promise<Profile>;
	/**
	 * Export a Crema `Profile` as a community-v2 `.json` document.
	 * Pure encoder — the result drops straight into a `Blob` for
	 * download.
	 */
	exportV2JsonProfile(profile: Profile): Promise<string>;
	/** Build a `CoreOutput` whose command queries the connected scale's settings. */
	queryScaleSettings(): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose command reads one DE1 memory-mapped register.
	 * The DE1 answers on the `De1MmrRead` characteristic, decoding to an
	 * `MmrValue` event.
	 */
	readMmr(register: MmrRegister): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose command reads a DE1 sensor's calibration —
	 * the current (in-use) calibration, or the factory one when `factory` is
	 * `true`. The DE1 answers on the `De1Calibration` characteristic, decoding
	 * to a `Calibration` event.
	 */
	readCalibration(sensor: CalTarget, factory?: boolean): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose command writes a new sensor calibration:
	 * the DE1 reported `reported` while the externally-measured true value
	 * was `measured`. Both arguments are in the sensor's canonical units
	 * (°C / bar / ml·s⁻¹); the shell converts at the I/O boundary.
	 */
	writeCalibration(sensor: CalTarget, reported: number, measured: number): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose command resets a sensor to its factory
	 * calibration. The DE1 starts applying the factory values immediately;
	 * the caller should follow up with `readCalibration` to surface the
	 * resulting in-use value.
	 */
	resetCalibrationToFactory(sensor: CalTarget): Promise<CoreOutput>;
	/** Build a `CoreOutput` whose command tares the connected scale. */
	tareScale(): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose command starts the connected scale's built-in
	 * timer. Capability-gated to scales that support software timer commands
	 * (the Bookoo today); empty for weight-only / timer-less scales.
	 */
	startTimer(): Promise<CoreOutput>;
	/** Build a `CoreOutput` whose command stops the connected scale's built-in timer. */
	stopTimer(): Promise<CoreOutput>;
	/** Build a `CoreOutput` whose command resets the connected scale's built-in timer to zero. */
	resetTimer(): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose commands enable the connected scale's
	 * on-scale LCD in the unit the user has chosen. Capability-driven:
	 * the bridge asks the scale for its LCD-enable wire bytes (Decent
	 * one packet, Skale 2–3 writes); scales without an LCD reject with
	 * an `UnsupportedOnHardware` error. The shell schedules periodic
	 * `scaleHeartbeat` writes once the LCD is enabled for scales whose
	 * `ScaleCapabilities.heartbeat_interval_ms` is non-null. `unit` is the
	 * shell's legacy `'g' | 'oz'` pref; the bridge maps it to the
	 * core's `'grams' | 'ounces'` wire form.
	 */
	enableScaleLcd(unit: ShellWeightUnit): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose commands disable the connected scale's
	 * on-scale LCD. Capability-driven; rejects on scales without a
	 * settable LCD.
	 */
	disableScaleLcd(): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose command emits one keep-alive heartbeat
	 * to the connected scale (Decent Scale needs this every ~2 s).
	 * Capability-driven; rejects on scales that don't need a heartbeat.
	 */
	scaleHeartbeat(): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose commands power off the connected scale.
	 * Capability-driven — Decent / Eureka / Solo expose this today;
	 * scales without a host-driven power-off reject with an
	 * `UnsupportedOnHardware` error.
	 */
	powerOffScale(): Promise<CoreOutput>;
	/**
	 * Cache the user's chosen weight unit on the core, so the Decent
	 * Scale LCD-enable auto-policy (triggered on the DE1's Idle entry)
	 * picks the right wire packet without the shell re-dispatching
	 * `enableScaleLcd` on every state change. The shell calls this
	 * whenever the `weightUnit` settings pref changes.
	 */
	setWeightUnitPref(unit: ShellWeightUnit): Promise<void>;
	/**
	 * Enable or disable auto-tare on shot start. Latched in the core and
	 * consulted on `ShotEvent::Started` regardless of who initiated the
	 * shot (Crema-tap or DE1 group-head touch). Default `true` until set.
	 */
	setAutoTare(enabled: boolean): Promise<void>;
	/**
	 * Enable or disable stop-at-weight. When `false`, SAW never arms even
	 * if a target weight is configured. Default `true` until set.
	 */
	setStopOnWeight(enabled: boolean): Promise<void>;
	/**
	 * Per-shot kill switch for the weight target — when `true`, the core
	 * skips arming weight-based auto-stop for the next shot regardless of
	 * the configured target. Independent of {@link setStopOnWeight} (the
	 * user's persistent pref). The shell flips this when the user toggles
	 * the QC yield dot OFF, and resets to `false` on every profile change.
	 */
	setWeightTargetDisabled(disabled: boolean): Promise<void>;
	/**
	 * Clear the running scale-derived peaks (peak weight + final weight)
	 * without disturbing pressure / temperature peaks. The Scale page's
	 * "Reset peak" button.
	 */
	resetScalePeaks(): Promise<void>;
	/**
	 * Enable / disable steam eco mode. The DE1 firmware exposes two
	 * steam profiles — full-power steam (default) and a lower-flow,
	 * lower-temp variant for cleaner milk texture on small jugs.
	 * Returns the `CoreOutput` whose commands write the new steam
	 * settings to the DE1.
	 */
	enableSteamEcoMode(enabled: boolean): Promise<CoreOutput>;
	/**
	 * Set the active profile's recipe target weight, grams. `undefined`
	 * (or a `<=0` / non-finite value) means "no target." Called when the
	 * shell activates or edits a profile.
	 */
	setProfileTargetWeight(grams: number | undefined): Promise<void>;
	/**
	 * Set the per-shot dial-override weight, grams. `undefined` clears
	 * the override and falls back to the profile recipe target.
	 */
	setShotTargetWeight(grams: number | undefined): Promise<void>;
	/**
	 * Set the active profile's volume limit (SAV), millilitres.
	 * `undefined` means "no limit."
	 */
	setProfileVolumeLimit(milliliters: number | undefined): Promise<void>;
	/**
	 * Set the global maximum shot duration, seconds. `undefined` means
	 * "no max." Legacy default is 60 s.
	 */
	setMaxShotDuration(seconds: number | undefined): Promise<void>;
	/**
	 * Build a `CoreOutput` whose command fires a beep on the connected
	 * scale. Capability-driven — Eureka / Solo support it; other scales
	 * reject with `UnsupportedOnHardware`.
	 */
	scaleBeep(): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose command explicitly sets the connected
	 * scale's display unit to grams. Capability-driven — Eureka / Solo
	 * / Difluid expose this. For scales whose unit lives in the
	 * LCD-enable bytes (Decent / Skale) the unit is set via
	 * `enableScaleLcd`; for toggle-only scales (Hiroia) use
	 * `toggleScaleUnit`.
	 */
	setScaleUnitGrams(): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose command toggles the connected scale's
	 * display unit. Capability-driven — Hiroia is the only scale that
	 * exposes a toggle today.
	 */
	toggleScaleUnit(): Promise<CoreOutput>;
	/**
	 * Toggle whether the connected scale should be powered off on machine
	 * Sleep entry. Off by default. Capability-gated by the scale's
	 * `power_off_command` — applies to any scale with a host-driven
	 * power-off (Decent / Eureka / Solo today).
	 */
	setAutoOffScaleOnSleep(enabled: boolean): Promise<void>;
	/**
	 * Whether the connected scale is configured to power off on machine
	 * Sleep entry.
	 */
	autoOffScaleOnSleep(): Promise<boolean>;
	/** Build a `CoreOutput` whose command sets the scale beeper volume. */
	setScaleVolume(level: number): Promise<CoreOutput>;
	/** Build a `CoreOutput` whose command sets the scale auto-standby timeout. */
	setScaleStandby(minutes: number): Promise<CoreOutput>;
	/** Build a `CoreOutput` whose command toggles scale flow smoothing. */
	setScaleFlowSmoothing(enabled: boolean): Promise<CoreOutput>;
	/** Build a `CoreOutput` whose command toggles scale anti-mistouch. */
	setScaleAntiMistouch(enabled: boolean): Promise<CoreOutput>;
	/** Build a `CoreOutput` whose commands switch the scale display mode. */
	setScaleMode(modeId: number): Promise<CoreOutput>;
	/** Build a `CoreOutput` whose command selects the scale auto-stop mode. */
	setScaleAutoStop(modeId: number): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose command writes the DE1's water-tank refill
	 * threshold (`cuuid_11`). `thresholdMm` is the level at or below which the
	 * DE1 should ask for a refill.
	 */
	setRefillThreshold(thresholdMm: number): Promise<CoreOutput>;
	/**
	 * Build a `CoreOutput` whose command writes one DE1 memory-mapped
	 * register. `value` is the raw little-endian word the register expects
	 * (already scaled); `byteLen` is `1`, `2`, or `4`.
	 */
	writeMmr(register: MmrRegister, value: number, byteLen: number): Promise<CoreOutput>;
	/** Set the fan-on temperature threshold. Celsius is the canonical unit (see docs/25 §7). */
	setFanThreshold(celsius: number): Promise<CoreOutput>;
	/** Set the tank desired water-temperature threshold. Celsius is the canonical unit (see docs/25 §7). */
	setTankThreshold(celsius: number): Promise<CoreOutput>;
	/** Set the steam flow rate, ml/s. */
	setSteamFlow(mlPerS: number): Promise<CoreOutput>;
	/** Set the seconds of high-flow steam at the start of a steam cycle. */
	setSteamHighflowStart(seconds: number): Promise<CoreOutput>;
	/** Set the group-head-control mode. */
	setGhcMode(mode: number): Promise<CoreOutput>;
	/** Set the hot-water flow rate, ml/s. */
	setHotWaterFlowRate(mlPerS: number): Promise<CoreOutput>;
	/** Set the flush flow rate, ml/s. */
	setFlushFlowRate(mlPerS: number): Promise<CoreOutput>;
	/** Set the flush water target temperature. Celsius is the canonical unit; wire value is `celsius × 10`. */
	setFlushTemp(celsius: number): Promise<CoreOutput>;
	/** Set the flush timeout, milliseconds. */
	setFlushTimeout(ms: number): Promise<CoreOutput>;
	/** Enable / disable the tablet's USB charging output. */
	setUsbChargerOn(enabled: boolean): Promise<CoreOutput>;
	/**
	 * Ask the DE1 to enter a machine state — Sleep (0), Idle (1),
	 * Espresso (2), Steam (3), HotWater (4), Flush (5), Descale (6),
	 * Clean (7). Writes one byte to RequestedState (cuuid_02). Idle
	 * also stops a running shot and wakes from sleep. Most non-Sleep /
	 * Idle states are normally initiated by the on-machine touch
	 * buttons; the shell exposes them for completeness.
	 */
	requestMachineState(state: import('./crema-core').MachineState): Promise<CoreOutput>;
	/** Tell the firmware whether the user is present (distinct from feature flags). */
	setUserPresent(present: boolean): Promise<CoreOutput>;
	/** Set the firmware feature-flag bitmask (distinct from user-present). */
	setFeatureFlags(flags: number): Promise<CoreOutput>;
	/** Override the refill-kit presence flag (0/1/2). */
	setRefillKitPresent(state: number): Promise<CoreOutput>;
	/**
	 * Set the mains heater voltage. **Damaging if mis-set** — wrong voltage
	 * on the wrong wall outlet can permanently damage the heater. Gate
	 * behind `MainsConfirmModal`. Only `120` and `230` are accepted; the
	 * core rejects any other value (the call throws). Wire-side the value
	 * carries the firmware's `+1000` user-committed marker.
	 */
	setHeaterVoltage(volts: 120 | 230): Promise<CoreOutput>;
	/**
	 * Reset 8 machine settings to factory baseline — mirrors reaprime's
	 * `DELETE /api/v1/machine/settings/reset`. Emits 8 sequential MMR
	 * writes (fan threshold, hot-water idle temp, heater phase 1/2 flows,
	 * espresso warmup timeout, refill kit auto, flow-calibration
	 * multiplier, steam purge mode). Profiles, shot history, and app
	 * preferences are untouched.
	 */
	resetMachineDefaults(): Promise<CoreOutput>;
	/** Set the cup-warmer temperature (Bengle models only). Celsius is the canonical unit (see docs/25 §7). */
	setCupWarmerTemp(celsius: number): Promise<CoreOutput>;
	/** Set the flow-calibration multiplier (scaled `int(1000 × multiplier)`). */
	setCalibrationFlowMultiplier(multiplier: number): Promise<CoreOutput>;
	/** Set the hot-water phase-1 flow rate, ml/s (scaled `int(10 × rate)` on the wire). MMR `0x803810`, 4-byte LE. */
	setPhase1FlowRate(mlPerS: number): Promise<CoreOutput>;
	/** Set the hot-water phase-2 flow rate, ml/s (scaled `int(10 × rate)` on the wire). MMR `0x803814`, 4-byte LE. */
	setPhase2FlowRate(mlPerS: number): Promise<CoreOutput>;
	/** Set the hot-water boiler idle target temperature; Celsius is canonical (see docs/25 §7). Wire value is `celsius × 10` (MMR `0x803818`, 4-byte LE). */
	setHotWaterIdleTemp(celsius: number): Promise<CoreOutput>;
	/** Set the espresso group warmup timeout, seconds (scaled `int(10 × s)` on the wire). MMR `0x803838`, 4-byte LE. */
	setEspressoWarmupTimeout(seconds: number): Promise<CoreOutput>;
	/** Set the steam two-tap-stop (`steamPurgeMode`), 0/1. MMR `0x803850`, 4-byte LE int. */
	setSteamTwoTapStop(value: number): Promise<CoreOutput>;
	/**
	 * The standard DE1 profiles Crema ships built in, as a parsed array of
	 * {@link Profile}. The bridge returns a JSON-string of `Profile[]` (the
	 * "Option S" encoding); this method parses it. The list is read-only — the
	 * built-in corpus is fixed at compile time in the core.
	 */
	builtinProfiles(): Promise<Profile[]>;
}

/**
 * The memoized core-load promise. The wasm bundle and its `CremaBridge` are a
 * process-wide singleton: `loadCore()` is called from more than one place (the
 * orchestrator's `createCremaApp` and `ProfileStore.ensureLoaded`), and each
 * must get the *same* `CremaCore` instance — two independent `CremaBridge`es
 * would mean two divergent core sessions. Caching the `Promise` here, rather
 * than the resolved value, also collapses concurrent first calls onto one
 * in-flight load.
 */
let corePromise: Promise<CremaCore> | undefined;

/**
 * The loaded wasm namespace, captured by {@link createCore} so synchronous
 * helpers (e.g. {@link newProfileId}) can reach the pure top-level wasm
 * exports without re-doing the async init. `undefined` until the first
 * {@link loadCore} resolves.
 */
let wasmModule: typeof import('$lib/wasm/de1_wasm.js') | undefined;

/**
 * Mint a fresh profile ID — a UUID v7 (RFC 9562, 2024) in the
 * 36-character dashed form, e.g. `01910f80-7a3b-7c54-b2d1-23a4f8e9cd00`.
 *
 * Synchronous wrapper over the wasm `newProfileId` export, which the
 * Rust core ([`de1_domain::new_profile_id`]) backs. Requires
 * {@link loadCore} to have resolved at least once: every place that
 * calls this (profile create / duplicate / import) sits behind the
 * orchestrator's boot, where the core has already loaded.
 *
 * Used for **custom** profiles. Built-in profile IDs are pre-generated
 * and ship in `core/de1-domain/profiles/builtin.json` — not minted at
 * runtime.
 *
 * @throws `Error` if the wasm module has not been loaded yet
 *  (a programming error — call `await loadCore()` once at boot).
 */
export function newProfileId(): string {
	if (!wasmModule) {
		throw new Error(
			'newProfileId() called before the wasm core was loaded; await loadCore() first'
		);
	}
	return wasmModule.newProfileId();
}

/**
 * Load the wasm core and return the async facade.
 *
 * Dynamic-imports the `de1-wasm` bundle, `await`s its async `init()`, and
 * constructs a `CremaBridge`. The bundle is `--target web`: its default export
 * is the async `init()`, and `CremaBridge` / `NotificationSource` are named
 * exports.
 *
 * Memoized: every call returns the same `Promise<CremaCore>`, so the whole app
 * shares one `CremaBridge` instance.
 */
export function loadCore(): Promise<CremaCore> {
	if (!corePromise) corePromise = createCore();
	return corePromise;
}

/** The actual one-time wasm load + facade construction, memoized by {@link loadCore}. */
async function createCore(): Promise<CremaCore> {
	const wasm = await import('$lib/wasm/de1_wasm.js');
	await wasm.default();
	// Cache the namespace so the synchronous top-level helpers (e.g.
	// `newProfileId`) can reach the wasm without going through the
	// async facade — these helpers run from synchronous edit-shell
	// paths (create / duplicate / import a profile).
	wasmModule = wasm;
	// Mirror the wasm namespace onto `window` so deep modules that
	// can't import `$lib/core` without a cycle (e.g. `$lib/history/model`'s
	// `shotId` minter) can still reach into the Rust UUID-v7 minter
	// once the core is loaded. SSR / test paths fall back to
	// `crypto.randomUUID`.
	if (typeof window !== 'undefined') {
		(window as { __cremaWasmCore?: typeof wasm }).__cremaWasmCore = wasm;
	}
	const bridge = new wasm.CremaBridge();

	/**
	 * Map a facade `NotificationSource` string onto the wasm numeric enum.
	 * Confining the wasm enum here means no caller ever imports a wasm type.
	 */
	const toWasmSource = (
		source: NotificationSource
	): (typeof wasm.NotificationSource)[keyof typeof wasm.NotificationSource] => {
		switch (source) {
			case 'De1State':
				return wasm.NotificationSource.De1State;
			case 'De1ShotSample':
				return wasm.NotificationSource.De1ShotSample;
			case 'ScaleWeight':
				return wasm.NotificationSource.ScaleWeight;
			case 'ScaleCommand':
				return wasm.NotificationSource.ScaleCommand;
			case 'De1WaterLevels':
				return wasm.NotificationSource.De1WaterLevels;
			case 'De1Version':
				return wasm.NotificationSource.De1Version;
			case 'De1MmrRead':
				return wasm.NotificationSource.De1MmrRead;
			case 'De1Calibration':
				return wasm.NotificationSource.De1Calibration;
			case 'De1ShotSettings':
				return wasm.NotificationSource.De1ShotSettings;
			case 'De1ProfileHeader':
				return wasm.NotificationSource.De1ProfileHeader;
			case 'De1FrameAck':
				return wasm.NotificationSource.De1FrameAck;
		}
	};

	/**
	 * Map a typeshare `MmrRegister` string onto the wasm-bindgen numeric
	 * enum. Both come from the same Rust source of truth
	 * (`de1_protocol::MmrRegister`) — typeshare emits the string form
	 * for JSON CoreOutput payloads, wasm-bindgen emits the numeric form
	 * for the direct-call ABI used by `read_mmr` / `write_mmr`. The
	 * variant names line up, so this is one bracket lookup.
	 */
	const toWasmMmrRegister = (
		register: MmrRegister
	): (typeof wasm.MmrRegister)[keyof typeof wasm.MmrRegister] =>
		wasm.MmrRegister[register];

	/** Map a typeshare `CalTarget` string onto the wasm numeric `CalSensor`. */
	const toWasmCalSensor = (
		sensor: CalTarget
	): (typeof wasm.CalSensor)[keyof typeof wasm.CalSensor] => wasm.CalSensor[sensor];

	/** Parse a bridge JSON string into a typed `CoreOutput`. */
	const parseOutput = (raw: string): CoreOutput => JSON.parse(raw) as CoreOutput;

	// `lastNotificationAtMs` is mutated inside `onNotification` so it stays
	// in lockstep with whatever timestamp the core just stamped into its
	// rolling capture buffer. Held in a closure-captured `let` and read via
	// a getter so the facade's interface still presents it as a `readonly`
	// number from the outside.
	let lastNotificationAtMs: number | null = null;
	return {
		async onNotification(source, data, nowMs) {
			lastNotificationAtMs = nowMs;
			return parseOutput(bridge.on_notification(toWasmSource(source), data, nowMs));
		},
		get lastNotificationAtMs(): number | null {
			return lastNotificationAtMs;
		},
		async onTick(nowMs) {
			return parseOutput(bridge.on_tick(nowMs));
		},
		async reset() {
			bridge.reset();
		},
		async captureSliceJsonl(fromMs, toMs) {
			return bridge.capture_slice_jsonl(fromMs, toMs);
		},
		async captureClear() {
			bridge.capture_clear();
		},
		async beginReplay() {
			bridge.begin_replay();
		},
		async endReplay() {
			bridge.end_replay();
		},
		async inReplay() {
			return bridge.in_replay();
		},
		async connectScale(advertisedName) {
			return bridge.connect_scale(advertisedName);
		},
		async scaleCapabilities() {
			const raw = bridge.scale_capabilities();
			return raw === undefined ? undefined : (JSON.parse(raw) as ScaleCapabilities);
		},
		async scaleUuids() {
			const raw = bridge.scale_uuids();
			return raw === undefined ? undefined : (JSON.parse(raw) as ScaleUuids);
		},
		async firmwareUpdateStatus() {
			return JSON.parse(bridge.firmware_update_status()) as FirmwareUpdateStatus;
		},
		async uploadProfile(profile, nowMs) {
			return parseOutput(bridge.upload_profile(JSON.stringify(profile), nowMs));
		},
		async cancelProfileUpload() {
			return parseOutput(bridge.cancel_profile_upload());
		},
		async profileUploadInProgress() {
			return bridge.profile_upload_in_progress();
		},
		async activeProfileName() {
			const t = bridge.active_profile_title();
			return t === undefined ? null : t;
		},
		async setLineFrequencyOverride(hz) {
			bridge.set_line_frequency_override(hz);
		},
		async lineFrequencyHz() {
			const hz = bridge.line_frequency_hz();
			return hz === undefined ? null : hz;
		},
		async importLegacyTclShot(content) {
			return JSON.parse(bridge.import_legacy_tcl_shot(content)) as RustStoredShot;
		},
		async importV2JsonShot(content) {
			return JSON.parse(bridge.import_v2_json_shot(content)) as RustStoredShot;
		},
		async importV2JsonProfile(content) {
			return JSON.parse(bridge.import_v2_json_profile(content)) as Profile;
		},
		async importLegacyTclProfile(content) {
			return JSON.parse(bridge.import_legacy_tcl_profile(content)) as Profile;
		},
		async exportV2JsonProfile(profile) {
			return bridge.export_v2_json_profile(JSON.stringify(profile));
		},
		async queryScaleSettings() {
			return parseOutput(bridge.query_scale_settings());
		},
		async readMmr(register) {
			return parseOutput(bridge.read_mmr(toWasmMmrRegister(register)));
		},
		async readCalibration(sensor, factory = false) {
			const wasmSensor = toWasmCalSensor(sensor);
			return parseOutput(
				factory
					? bridge.read_factory_calibration(wasmSensor)
					: bridge.read_calibration(wasmSensor)
			);
		},
		async writeCalibration(sensor, reported, measured) {
			return parseOutput(
				bridge.write_calibration(toWasmCalSensor(sensor), reported, measured)
			);
		},
		async resetCalibrationToFactory(sensor) {
			return parseOutput(bridge.reset_calibration_to_factory(toWasmCalSensor(sensor)));
		},
		async tareScale() {
			return parseOutput(bridge.tare_scale());
		},
		async startTimer() {
			return parseOutput(bridge.start_timer());
		},
		async stopTimer() {
			return parseOutput(bridge.stop_timer());
		},
		async resetTimer() {
			return parseOutput(bridge.reset_timer());
		},
		async enableScaleLcd(unit: ShellWeightUnit) {
			return parseOutput(bridge.enable_scale_lcd(weightUnitToWire(unit)));
		},
		async disableScaleLcd() {
			return parseOutput(bridge.disable_scale_lcd());
		},
		async scaleHeartbeat() {
			return parseOutput(bridge.scale_heartbeat());
		},
		async powerOffScale() {
			// The bridge throws a string on unsupported-hardware; surface it
			// as a JS Error so callers can `try/catch` it normally and pull
			// the user-facing reason out of `error.message`.
			return parseOutput(bridge.power_off_scale());
		},
		async setWeightUnitPref(unit: ShellWeightUnit) {
			bridge.set_weight_unit_pref(weightUnitToWire(unit));
		},
		async setAutoTare(enabled) {
			bridge.set_auto_tare(enabled);
		},
		async setStopOnWeight(enabled) {
			bridge.set_stop_on_weight(enabled);
		},
		async setWeightTargetDisabled(disabled) {
			bridge.set_weight_target_disabled(disabled);
		},
		async resetScalePeaks() {
			bridge.reset_scale_peaks();
		},
		async enableSteamEcoMode(enabled) {
			return parseOutput(bridge.enable_steam_eco_mode(enabled, performance.now()));
		},
		async setProfileTargetWeight(grams) {
			bridge.set_profile_target_weight(grams);
		},
		async setShotTargetWeight(grams) {
			bridge.set_shot_target_weight(grams);
		},
		async setProfileVolumeLimit(milliliters) {
			bridge.set_profile_volume_limit(milliliters);
		},
		async setMaxShotDuration(seconds) {
			bridge.set_max_shot_duration(seconds);
		},
		async scaleBeep() {
			return parseOutput(bridge.scale_beep());
		},
		async setScaleUnitGrams() {
			return parseOutput(bridge.set_scale_unit_grams());
		},
		async toggleScaleUnit() {
			return parseOutput(bridge.toggle_scale_unit());
		},
		async setAutoOffScaleOnSleep(enabled: boolean) {
			bridge.set_auto_off_scale_on_sleep(enabled);
		},
		async autoOffScaleOnSleep() {
			return bridge.auto_off_scale_on_sleep();
		},
		async setScaleVolume(level) {
			return parseOutput(bridge.set_scale_volume(level));
		},
		async setScaleStandby(minutes) {
			return parseOutput(bridge.set_scale_standby(minutes));
		},
		async setScaleFlowSmoothing(enabled) {
			return parseOutput(bridge.set_scale_flow_smoothing(enabled));
		},
		async setScaleAntiMistouch(enabled) {
			return parseOutput(bridge.set_scale_anti_mistouch(enabled));
		},
		async setScaleMode(modeId) {
			return parseOutput(bridge.set_scale_mode(modeId));
		},
		async setScaleAutoStop(modeId) {
			return parseOutput(bridge.set_scale_auto_stop(modeId));
		},
		async setRefillThreshold(thresholdMm) {
			return parseOutput(bridge.set_refill_threshold(thresholdMm));
		},
		async writeMmr(register, value, byteLen) {
			return parseOutput(bridge.write_mmr(toWasmMmrRegister(register), value, byteLen));
		},
		async setFanThreshold(celsius) {
			return parseOutput(bridge.set_fan_threshold(celsius));
		},
		async setTankThreshold(celsius) {
			return parseOutput(bridge.set_tank_threshold(celsius));
		},
		async setSteamFlow(mlPerS) {
			return parseOutput(bridge.set_steam_flow(mlPerS));
		},
		async setSteamHighflowStart(seconds) {
			return parseOutput(bridge.set_steam_highflow_start(seconds));
		},
		async setGhcMode(mode) {
			return parseOutput(bridge.set_ghc_mode(mode));
		},
		async setHotWaterFlowRate(mlPerS) {
			return parseOutput(bridge.set_hot_water_flow_rate(mlPerS));
		},
		async setFlushFlowRate(mlPerS) {
			return parseOutput(bridge.set_flush_flow_rate(mlPerS));
		},
		async setFlushTemp(celsius) {
			return parseOutput(bridge.set_flush_temp(celsius));
		},
		async setFlushTimeout(ms) {
			return parseOutput(bridge.set_flush_timeout(ms));
		},
		async setUsbChargerOn(enabled) {
			return parseOutput(bridge.set_usb_charger_on(enabled));
		},
		async requestMachineState(state) {
			// The wasm bridge takes a narrower `MachineRequest` enum (only
			// the eight states the host can ask for) rather than the full
			// `MachineState`. Map by name; unknown / non-requestable values
			// fall through to NoRequest (a no-op the firmware accepts).
			const map: Partial<Record<string, (typeof wasm.MachineRequest)[keyof typeof wasm.MachineRequest]>> = {
				Sleep: wasm.MachineRequest.Sleep,
				Idle: wasm.MachineRequest.Idle,
				Espresso: wasm.MachineRequest.Espresso,
				Steam: wasm.MachineRequest.Steam,
				HotWater: wasm.MachineRequest.HotWater,
				// `Flush` in the wasm enum is the firmware's HotWaterRinse
				// state (group-flush mode). Callers pass the public
				// MachineState string `'HotWaterRinse'`; the bridge does the
				// rename. See core/de1-wasm/src/lib.rs:214.
				HotWaterRinse: wasm.MachineRequest.Flush,
				Descale: wasm.MachineRequest.Descale,
				Clean: wasm.MachineRequest.Clean,
				// Five additional state requests the wasm bridge already
				// supports but the facade didn't expose by name. Added to
				// match legacy de1app + reaprime's full requestable set.
				// ShortCal / SelfTest are diagnostic
				// states — gate them behind an explicit user action.
				SkipToNext: wasm.MachineRequest.SkipToNext,
				SteamRinse: wasm.MachineRequest.SteamRinse,
				AirPurge: wasm.MachineRequest.AirPurge,
				SchedIdle: wasm.MachineRequest.SchedIdle,
				ShortCal: wasm.MachineRequest.ShortCal,
				SelfTest: wasm.MachineRequest.SelfTest
			};
			const req = map[state];
			if (req === undefined) {
				throw new Error(`Machine state ${state} is not requestable from the host`);
			}
			return parseOutput(bridge.request_machine_state(req));
		},
		async setUserPresent(present) {
			return parseOutput(bridge.set_user_present(present));
		},
		async setFeatureFlags(flags) {
			return parseOutput(bridge.set_feature_flags(flags));
		},
		async setRefillKitPresent(state) {
			return parseOutput(bridge.set_refill_kit_present(state));
		},
		async setHeaterVoltage(volts) {
			// wasm-bindgen surfaces `Result<String, String>::Err` as a thrown
			// `Error` whose `message` is the AppError display string. The
			// shell pre-validates via `MainsConfirmModal`; this throw is
			// the last-line guard and the caller should surface it as a
			// toast / banner.
			return parseOutput(bridge.set_heater_voltage(volts));
		},
		async resetMachineDefaults() {
			// Bridge returns `Result<String, String>`; today infallible but
			// shaped for forward symmetry with `setHeaterVoltage`.
			return parseOutput(bridge.reset_machine_defaults());
		},
		async setCupWarmerTemp(celsius) {
			return parseOutput(bridge.set_cup_warmer_temperature(celsius));
		},
		async setCalibrationFlowMultiplier(multiplier) {
			return parseOutput(bridge.set_calibration_flow_multiplier(multiplier));
		},
		async setPhase1FlowRate(mlPerS) {
			return parseOutput(bridge.set_phase_1_flow_rate(mlPerS));
		},
		async setPhase2FlowRate(mlPerS) {
			return parseOutput(bridge.set_phase_2_flow_rate(mlPerS));
		},
		async setHotWaterIdleTemp(celsius) {
			return parseOutput(bridge.set_hot_water_idle_temp(celsius));
		},
		async setEspressoWarmupTimeout(seconds) {
			return parseOutput(bridge.set_espresso_warmup_timeout(seconds));
		},
		async setSteamTwoTapStop(value) {
			return parseOutput(bridge.set_steam_two_tap_stop(value));
		},
		async builtinProfiles() {
			return JSON.parse(bridge.builtin_profiles_json()) as Profile[];
		}
	};
}

export type { CoreOutput, ScaleCapabilities, ScaleUuids, FirmwareUpdateStatus } from './crema-core';
export type { Event, Command, ModeInfo, RangeCapability } from './crema-core';
// Re-export the wire enums so consumers comparing snapshot fields don't have
// to deep-import from `./crema-core`. These are the discriminant strings
// produced by typeshare; values matter (consumers `===` them).
export { MachineState, SubState, ShotPhase } from './crema-core';
export { CalCommand, CalTarget, MmrRegister } from './crema-core';
