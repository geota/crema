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
import type { CalTarget, MmrRegister } from './crema-core';

/**
 * Which quantity a profile step holds at its target — mirrors the core's
 * `Pump` enum (serde-serialised as the bare variant name).
 */
export type ProfilePump = 'Pressure' | 'Flow';

/** How a profile step moves to its target — mirrors the core's `Transition`. */
export type ProfileTransition = 'Fast' | 'Smooth';

/** Which temperature sensor a step regulates — mirrors `TempSensor`. */
export type ProfileTempSensor = 'Basket' | 'Mix';

/** The metric an exit condition watches — mirrors `ExitMetric`. */
export type ProfileExitMetric = 'Pressure' | 'Flow';

/** The direction of an exit comparison — mirrors `Compare`. */
export type ProfileCompare = 'Over' | 'Under';

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
	| 'De1Calibration';

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
		async builtinProfiles() {
			return JSON.parse(bridge.builtin_profiles_json()) as Profile[];
		}
	};
}

export type { CoreOutput, ScaleCapabilities, ScaleUuids } from './crema-core';
export type { Event, Command, ModeInfo, RangeCapability } from './crema-core';
export { CalCommand, CalTarget, MmrRegister } from './crema-core';
