/**
 * DE1 GATT service and characteristic UUIDs — the web shell's mirror of the
 * Android shell's `De1Uuids.kt`.
 *
 * Source: `docs/02-ble-protocol.md` §1. All DE1 characteristics share the
 * Bluetooth-SIG base UUID `0000xxxx-0000-1000-8000-00805f9b34fb` with a 16-bit
 * value in the `xxxx` slot (`A000`..`A012`).
 *
 * Web Bluetooth wants **lowercase** 128-bit UUID strings (or 16-bit numbers);
 * these are pre-expanded to the lowercase 128-bit form. The shell uses the two
 * notify characteristics — `StateInfo` and `ShotSample` — plus `WaterLevels`.
 * The DE1 is read-only in the shell, so no write characteristic is wired.
 */

/** Expand a 16-bit short UUID into the full lowercase 128-bit Bluetooth-SIG UUID. */
function short(value: string): string {
	return `0000${value.toLowerCase()}-0000-1000-8000-00805f9b34fb`;
}

export const De1Uuids = {
	/** The DE1 GATT service (`suuid`, `A000`). */
	SERVICE: short('a000'),

	/** `cuuid_0D` / `A00D` — ShotSample: the ~4-10 Hz telemetry notify stream. */
	SHOT_SAMPLE: short('a00d'),

	/** `cuuid_0E` / `A00E` — StateInfo: 2-byte machine state + substate notify. */
	STATE_INFO: short('a00e'),

	/** `cuuid_11` / `A011` — WaterLevels: 4-byte tank level notify. */
	WATER_LEVELS: short('a011'),

	/**
	 * The DE1 advertises with a name beginning "DE1"; some units advertise
	 * "BENGLE". Web Bluetooth's `requestDevice` filters on these name prefixes.
	 */
	NAME_PREFIXES: ['DE1', 'BENGLE'] as const
} as const;
