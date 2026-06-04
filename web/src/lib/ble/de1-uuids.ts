/**
 * DE1 GATT service and characteristic UUIDs.
 *
 * Sourced from the Rust core (`de1Uuids()` wasm export), **not** hardcoded — so
 * the web and Android shells share ONE map for the DE1's single fixed GATT
 * layout (no cross-shell drift). The core stays **sans-IO**: it only
 * *describes* the layout as data; the shell does the GATT I/O. Writes are
 * addressed via the core's `WriteTarget` → UUID map (`uuidForWriteTarget` in
 * `de1.ts` calls the `de1WriteTargetUuid` export).
 *
 * Web Bluetooth wants **lowercase** 128-bit UUID strings; the core already
 * returns them lowercase.
 *
 * Resolved **lazily** (on first property read) and memoized — *never* at module
 * scope. The wasm export is only callable once `loadCore()` has run, yet this
 * module sits on the BLE import chain the app evaluates during boot (`$lib/state`
 * → `$lib/ble` → `de1.ts`). Calling `de1Uuids()` at module-load time raced wasm
 * init and crashed first paint with `Cannot read properties of undefined
 * (reading '__wbindgen_free')`. Every read here happens from a connect-time /
 * gesture-bound path, long after the core has loaded — the same rule
 * `scale.ts`'s `scanFilters()` states: a free wasm fn is safe to call, but
 * "never at module scope (the wasm bundle loads first)".
 */

import { de1Uuids as wasmDe1Uuids } from '$lib/wasm/de1_wasm';

interface De1UuidMap {
	service: string;
	version: string;
	requestedState: string;
	shotSettings: string;
	mmrRead: string;
	mmrWrite: string;
	shotSample: string;
	stateInfo: string;
	waterLevels: string;
	calibration: string;
	headerWrite: string;
	frameWrite: string;
}

/** The core's UUID map — parsed once on first access, then memoized (see header). */
let map: De1UuidMap | null = null;
const uuids = (): De1UuidMap => (map ??= JSON.parse(wasmDe1Uuids()) as De1UuidMap);

export const De1Uuids = {
	/** The DE1 GATT service (`A000`). */
	get SERVICE() {
		return uuids().service;
	},

	/** `cuuid_01` / `A001` — Version: the BLE + firmware version block (Read). */
	get VERSION() {
		return uuids().version;
	},

	/** `cuuid_02` / `A002` — RequestedState: write a 1-byte state request. */
	get REQUESTED_STATE() {
		return uuids().requestedState;
	},

	/** `cuuid_0B` / `A00B` — ShotSettings: steam / hot-water targets (notify + write). */
	get SHOT_SETTINGS() {
		return uuids().shotSettings;
	},

	/** `cuuid_05` / `A005` — ReadFromMMR: write a read request; reply on this char's notify. */
	get MMR_READ() {
		return uuids().mmrRead;
	},

	/** `cuuid_06` / `A006` — WriteToMMR: write a register value. */
	get MMR_WRITE() {
		return uuids().mmrWrite;
	},

	/** `cuuid_0D` / `A00D` — ShotSample: the ~4-10 Hz telemetry notify stream. */
	get SHOT_SAMPLE() {
		return uuids().shotSample;
	},

	/** `cuuid_0E` / `A00E` — StateInfo: 2-byte machine state + substate notify. */
	get STATE_INFO() {
		return uuids().stateInfo;
	},

	/** `cuuid_11` / `A011` — WaterLevels: 4-byte tank level notify. */
	get WATER_LEVELS() {
		return uuids().waterLevels;
	},

	/** `cuuid_12` / `A012` — Calibration: write a read request; reply on this char's notify. */
	get CALIBRATION() {
		return uuids().calibration;
	},

	/** `cuuid_0F` / `A00F` — HeaderWrite: the 5-byte profile-upload header (write). */
	get HEADER_WRITE() {
		return uuids().headerWrite;
	},

	/** `cuuid_10` / `A010` — FrameWrite: one 8-byte profile frame (write; no notify). */
	get FRAME_WRITE() {
		return uuids().frameWrite;
	},

	/**
	 * Advertised-name prefixes for the device chooser. Shell-level scan config
	 * (the `nRF5x` chip-default name is a web-only fallback), kept here — the
	 * core owns the GATT UUIDs, not the chooser UX. A plain constant (no wasm),
	 * so it stays eager.
	 */
	NAME_PREFIXES: ['DE1', 'BENGLE', 'nRF5x'] as const
} as const;
