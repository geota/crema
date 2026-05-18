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
	| 'De1WaterLevels';

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
}

/**
 * Load the wasm core and return the async facade.
 *
 * Dynamic-imports the `de1-wasm` bundle, `await`s its async `init()`, and
 * constructs a `CremaBridge`. The bundle is `--target web`: its default export
 * is the async `init()`, and `CremaBridge` / `NotificationSource` are named
 * exports. Call once at app start.
 */
export async function loadCore(): Promise<CremaCore> {
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
		}
	};

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
		}
	};
}

export type { CoreOutput, ScaleCapabilities, ScaleUuids } from './crema-core';
export type { Event, Command, ModeInfo, RangeCapability } from './crema-core';
