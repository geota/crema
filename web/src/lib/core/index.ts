/**
 * `$lib/core` — the async facade over the `de1-wasm` `CremaBridge`.
 *
 * The Crema core is a sans-IO event processor: feed it raw BLE bytes and clock
 * ticks, get back a `CoreOutput` of observed `Event`s and `Command`s to
 * perform. `de1-wasm` exposes it as a wasm-bindgen `CremaBridge` whose
 * `CoreOutput`-producing methods return a JSON **string** (the "Option S"
 * encoding from `docs/08`). This module wraps that bridge so callers never see
 * the wasm or the JSON: every method parses the string into the typed
 * `CoreOutput` / `ScaleCapabilities` / `ScaleUuids` from `crema-core.ts`.
 *
 * ## Why every method is `async`
 *
 * The core runs on the main thread today — it is microsecond-scale work — so
 * each method resolves synchronously. They are nonetheless declared `async`
 * (return a `Promise`) on purpose: per `docs/09`, this keeps the facade
 * swappable to a Web Worker later (a `postMessage` round-trip is inherently
 * async) with **zero caller changes**. `loadCore()` is genuinely async — it
 * dynamic-imports the wasm bundle and `await`s its `init()`.
 *
 * Mirrors the role of the Android shell's `CremaBridge` Kotlin binding.
 */

import type { CoreOutput, ScaleCapabilities, ScaleUuids } from './crema-core';
import type { CalTarget, MmrRegister, FirmwareUpdateStatus } from './crema-core';

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
	/** The threshold value, bar or mL/s per `metric`. */
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
	/** Target value — bar (pressure) or mL/s (flow), per `pump`. */
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
	/** Per-step dispensed-volume limit, mL (0 = no limit). */
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
	/** Maximum flow for pressure-priority steps, mL/s. */
	maximum_flow: number;
	/** Whole-shot dispensed-volume limit, mL (0 = no limit). */
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
 * A `Duration` as serialized by the Rust core — `{secs, nanos}`. The
 * bridge emits this for `StoredShot.record.duration` and every
 * `TimedSample.elapsed`; the shell folds it to plain milliseconds at
 * the import boundary.
 */
export interface RustDuration {
	secs: number;
	nanos: number;
}

/** One telemetry sample as the Rust core emits it. */
export interface RustShotSample {
	sample_time: number;
	group_pressure: number;
	group_flow: number;
	head_temp: number;
	mix_temp: number;
	set_head_temp: number;
	set_mix_temp: number;
	set_group_pressure: number;
	set_group_flow: number;
	frame_number: number;
	steam_temp: number;
}

/** One timestamped sample in a `RustStoredShot.record.samples` array. */
export interface RustTimedSample {
	elapsed: RustDuration;
	sample: RustShotSample;
}

/** Barista journal metadata as the Rust core emits it. */
export interface RustShotMetadata {
	dose: number | null;
	yield_out: number | null;
	beans: string | null;
	grinder_setting: string | null;
	notes: string | null;
	rating: number | null;
	tds: number | null;
	extraction_yield: number | null;
}

/**
 * The Rust-shape `StoredShot` the bridge returns from
 * `importLegacyTclShot` / `importV2JsonShot`. Field names match the
 * Rust struct (snake_case). The shell maps this onto its own
 * (`$lib/history` `StoredShot`) shape at the import boundary.
 */
export interface RustStoredShot {
	format_version: number;
	recorded_at: number;
	profile: Profile | null;
	stop_reason: unknown | null;
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
	/** Feed a periodic clock tick. Resolves to the parsed `CoreOutput`. */
	onTick(nowMs: number): Promise<CoreOutput>;
	/** Discard all session state — e.g. on disconnect. */
	reset(): Promise<void>;
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
	 * `ProfileUploadFailed`. See `docs/16-profile-upload-plan.md`.
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
	 * fails. docs/22 §5.1.
	 */
	importLegacyTclShot(content: string): Promise<RustStoredShot>;
	/**
	 * Parse a modern de1app v2 `.shot.json` history file. Same return
	 * convention as `importLegacyTclShot`.
	 */
	importV2JsonShot(content: string): Promise<RustStoredShot>;
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
	/** Build a `CoreOutput` whose command sets the scale beeper volume. */
	setScaleVolume(level: number): Promise<CoreOutput>;
	/** Build a `CoreOutput` whose command sets the scale auto-standby timeout. */
	setScaleStandbyMinutes(minutes: number): Promise<CoreOutput>;
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
	/** Set the fan-on temperature threshold, °C. */
	setFanThreshold(tempC: number): Promise<CoreOutput>;
	/** Set the tank desired water-temperature threshold, °C. */
	setTankThreshold(tempC: number): Promise<CoreOutput>;
	/** Set the steam flow rate, mL/s. */
	setSteamFlow(mlPerS: number): Promise<CoreOutput>;
	/** Set the seconds of high-flow steam at the start of a steam cycle. */
	setSteamHighflowStart(seconds: number): Promise<CoreOutput>;
	/** Set the group-head-control mode. */
	setGhcMode(mode: number): Promise<CoreOutput>;
	/** Set the hot-water flow rate, mL/s. */
	setHotWaterFlowRate(mlPerS: number): Promise<CoreOutput>;
	/** Set the flush flow rate, mL/s. */
	setFlushFlowRate(mlPerS: number): Promise<CoreOutput>;
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
	/** Set the mains heater voltage. Damaging if mis-set — gate behind a typed-to-confirm modal. */
	setHeaterVoltage(volts: number): Promise<CoreOutput>;
	/** Set the cup-warmer temperature, °C (Bengle models only). */
	setCupWarmerTemperature(tempC: number): Promise<CoreOutput>;
	/** Set the flow-calibration multiplier (scaled `int(1000 × multiplier)`). */
	setCalibrationFlowMultiplier(multiplier: number): Promise<CoreOutput>;
	/**
	 * Apply the seven-register `heater_tweaks` composite write — the legacy
	 * connect-time push of the user's saved values.
	 */
	setHeaterTweaks(tweaks: {
		phase1FlowRate: number;
		phase2FlowRate: number;
		hotWaterIdleTempC: number;
		espressoWarmupTimeoutSeconds: number;
		steamTwoTapStop: number;
		flushTimeoutMs: number;
		flushFlowRate: number;
		hotWaterFlowRate: number;
	}): Promise<CoreOutput>;
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
	 * Map a typeshare `MmrRegister` string onto the wasm numeric `MmrReg`
	 * enum. The two enums are kept name-for-name in sync — `MmrRegister` is
	 * generated from the core's register list, `MmrReg` is its wasm mirror.
	 */
	const toWasmMmrReg = (
		register: MmrRegister
	): (typeof wasm.MmrReg)[keyof typeof wasm.MmrReg] => wasm.MmrReg[register];

	/** Map a typeshare `CalTarget` string onto the wasm numeric `CalSensor`. */
	const toWasmCalSensor = (
		sensor: CalTarget
	): (typeof wasm.CalSensor)[keyof typeof wasm.CalSensor] => wasm.CalSensor[sensor];

	/** Parse a bridge JSON string into a typed `CoreOutput`. */
	const parseOutput = (raw: string): CoreOutput => JSON.parse(raw) as CoreOutput;

	return {
		async onNotification(source, data, nowMs) {
			return parseOutput(bridge.on_notification(toWasmSource(source), data, nowMs));
		},
		async onTick(nowMs) {
			return parseOutput(bridge.on_tick(nowMs));
		},
		async reset() {
			bridge.reset();
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
		async queryScaleSettings() {
			return parseOutput(bridge.query_scale_settings());
		},
		async readMmr(register) {
			return parseOutput(bridge.read_mmr(toWasmMmrReg(register)));
		},
		async readCalibration(sensor, factory = false) {
			const wasmSensor = toWasmCalSensor(sensor);
			return parseOutput(
				factory
					? bridge.read_factory_calibration(wasmSensor)
					: bridge.read_calibration(wasmSensor)
			);
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
		async setScaleVolume(level) {
			return parseOutput(bridge.set_scale_volume(level));
		},
		async setScaleStandbyMinutes(minutes) {
			return parseOutput(bridge.set_scale_standby_minutes(minutes));
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
			return parseOutput(bridge.write_mmr(toWasmMmrReg(register), value, byteLen));
		},
		async setFanThreshold(tempC) {
			return parseOutput(bridge.set_fan_threshold(tempC));
		},
		async setTankThreshold(tempC) {
			return parseOutput(bridge.set_tank_threshold(tempC));
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
				// supports but the facade didn't expose by name. Added per
				// docs/22 §3.3 to match legacy de1app + reaprime's full
				// requestable set. ShortCal / SelfTest are diagnostic
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
			return parseOutput(bridge.set_heater_voltage(volts));
		},
		async setCupWarmerTemperature(tempC) {
			return parseOutput(bridge.set_cup_warmer_temperature(tempC));
		},
		async setCalibrationFlowMultiplier(multiplier) {
			return parseOutput(bridge.set_calibration_flow_multiplier(multiplier));
		},
		async setHeaterTweaks(tweaks) {
			return parseOutput(
				bridge.set_heater_tweaks(
					tweaks.phase1FlowRate,
					tweaks.phase2FlowRate,
					tweaks.hotWaterIdleTempC,
					tweaks.espressoWarmupTimeoutSeconds,
					tweaks.steamTwoTapStop,
					tweaks.flushTimeoutMs,
					tweaks.flushFlowRate,
					tweaks.hotWaterFlowRate
				)
			);
		},
		async builtinProfiles() {
			return JSON.parse(bridge.builtin_profiles_json()) as Profile[];
		}
	};
}

export type { CoreOutput, ScaleCapabilities, ScaleUuids, FirmwareUpdateStatus } from './crema-core';
export type { Event, Command, ModeInfo, RangeCapability } from './crema-core';
export { CalCommand, CalTarget, MmrRegister } from './crema-core';
