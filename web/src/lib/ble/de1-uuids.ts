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
 * returns them lowercase. Resolved once at module load — `+layout.ts` awaits
 * `loadCore()` before any module evaluates, so the wasm export is ready here.
 */

import { de1Uuids as wasmDe1Uuids } from '$lib/wasm/de1_wasm';

const map = JSON.parse(wasmDe1Uuids()) as {
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
};

export const De1Uuids = {
	/** The DE1 GATT service (`A000`). */
	SERVICE: map.service,

	/** `cuuid_01` / `A001` — Version: the BLE + firmware version block (Read). */
	VERSION: map.version,

	/** `cuuid_02` / `A002` — RequestedState: write a 1-byte state request. */
	REQUESTED_STATE: map.requestedState,

	/** `cuuid_0B` / `A00B` — ShotSettings: steam / hot-water targets (notify + write). */
	SHOT_SETTINGS: map.shotSettings,

	/** `cuuid_05` / `A005` — ReadFromMMR: write a read request; reply on this char's notify. */
	MMR_READ: map.mmrRead,

	/** `cuuid_06` / `A006` — WriteToMMR: write a register value. */
	MMR_WRITE: map.mmrWrite,

	/** `cuuid_0D` / `A00D` — ShotSample: the ~4-10 Hz telemetry notify stream. */
	SHOT_SAMPLE: map.shotSample,

	/** `cuuid_0E` / `A00E` — StateInfo: 2-byte machine state + substate notify. */
	STATE_INFO: map.stateInfo,

	/** `cuuid_11` / `A011` — WaterLevels: 4-byte tank level notify. */
	WATER_LEVELS: map.waterLevels,

	/** `cuuid_12` / `A012` — Calibration: write a read request; reply on this char's notify. */
	CALIBRATION: map.calibration,

	/** `cuuid_0F` / `A00F` — HeaderWrite: the 5-byte profile-upload header (write). */
	HEADER_WRITE: map.headerWrite,

	/** `cuuid_10` / `A010` — FrameWrite: one 8-byte profile frame (write; no notify). */
	FRAME_WRITE: map.frameWrite,

	/**
	 * Advertised-name prefixes for the device chooser. Shell-level scan config
	 * (the `nRF5x` chip-default name is a web-only fallback), kept here — the
	 * core owns the GATT UUIDs, not the chooser UX.
	 */
	NAME_PREFIXES: ['DE1', 'BENGLE', 'nRF5x'] as const
} as const;
