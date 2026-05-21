/**
 * DE1 GATT service and characteristic UUIDs — the web shell's mirror of the
 * Android shell's `De1Uuids.kt`.
 *
 * Source: `docs/02-ble-protocol.md` §1. All DE1 characteristics share the
 * Bluetooth-SIG base UUID `0000xxxx-0000-1000-8000-00805f9b34fb` with a 16-bit
 * value in the `xxxx` slot (`A000`..`A012`).
 *
 * Web Bluetooth wants **lowercase** 128-bit UUID strings (or 16-bit numbers);
 * these are pre-expanded to the lowercase 128-bit form. The shell subscribes
 * to the notify characteristics — `StateInfo`, `ShotSample`, `WaterLevels`,
 * and `MMR_READ` (for MMR read replies) — and writes to the matching
 * write/read-request characteristics for memory-mapped register access.
 */

/** Expand a 16-bit short UUID into the full lowercase 128-bit Bluetooth-SIG UUID. */
function short(value: string): string {
	return `0000${value.toLowerCase()}-0000-1000-8000-00805f9b34fb`;
}

export const De1Uuids = {
	/** The DE1 GATT service (`suuid`, `A000`). */
	SERVICE: short('a000'),

	/** `cuuid_01` / `A001` — Version: the BLE + firmware version block (Read). */
	VERSION: short('a001'),

	/**
	 * `cuuid_02` / `A002` — RequestedState: write a 1-byte state-machine
	 * request (Sleep / Idle / Espresso / …) to ask the DE1 to transition.
	 */
	REQUESTED_STATE: short('a002'),

	/**
	 * `cuuid_0B` / `A00B` — ShotSettings: steam and hot-water targets; one
	 * packet sets the steam temperature, steam duration, hot-water target
	 * temperature, etc.
	 */
	SHOT_SETTINGS: short('a00b'),

	/**
	 * `cuuid_05` / `A005` — ReadFromMMR: the memory-mapped register window. A
	 * read request is *written* to it and the DE1 answers with a notification
	 * on the same characteristic (request/reply).
	 */
	MMR_READ: short('a005'),

	/**
	 * `cuuid_06` / `A006` — WriteToMMR: writes a value to a memory-mapped
	 * register. Sibling of {@link MMR_READ} on the write side; one packet per
	 * register.
	 */
	MMR_WRITE: short('a006'),

	/** `cuuid_0D` / `A00D` — ShotSample: the ~4-10 Hz telemetry notify stream. */
	SHOT_SAMPLE: short('a00d'),

	/** `cuuid_0E` / `A00E` — StateInfo: 2-byte machine state + substate notify. */
	STATE_INFO: short('a00e'),

	/** `cuuid_11` / `A011` — WaterLevels: 4-byte tank level notify. */
	WATER_LEVELS: short('a011'),

	/**
	 * `cuuid_12` / `A012` — Calibration: sensor calibration. A read request is
	 * *written* to it and the DE1 answers with a notification on the same
	 * characteristic (request/reply).
	 */
	CALIBRATION: short('a012'),

	/**
	 * The DE1 advertises with a name beginning "DE1"; some units advertise
	 * "BENGLE". Its Nordic nRF5x BLE module can also surface under the chip's
	 * default name "nRF5x" — kept as a prefix for now so the chooser still
	 * scopes to the DE1. `requestDevice` filters on these name prefixes.
	 */
	NAME_PREFIXES: ['DE1', 'BENGLE', 'nRF5x'] as const
} as const;
