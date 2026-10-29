/**
 * `#lib/state/heater-voltage.js` — the Settings readout of the mains heater
 * voltage register (MMR 0x803834), decoded by the core (cohort review
 * 2026-10-28, #2 N7).
 *
 * The firmware stamps `+1000` on a user-committed value, so a set machine
 * reads back 1120 / 1230. The shell used to strip that itself (`raw - 1000`
 * for any raw ≥ 1000), which showed a doubled marker (2120 — a machine set by
 * an older Crema build) as "1120 V". The core's `heater_voltage_from_raw`
 * buckets like reaprime and returns nothing for such a word; the readout then
 * asks for the voltage to be set again. Android's `heaterVoltageLabel` does the
 * same.
 */

import { heaterVoltageFromRaw } from '#lib/wasm/de1_wasm.js';

export type HeaterVoltageReading =
	| { readonly kind: 'unread' }
	| { readonly kind: 'unset' }
	| { readonly kind: 'volts'; readonly volts: number }
	| { readonly kind: 'unrecognised'; readonly raw: number };

/** Decode the raw register word (`null` = not read yet). */
export function readHeaterVoltage(raw: number | null): HeaterVoltageReading {
	if (raw === null) return { kind: 'unread' };
	if (raw === 0) return { kind: 'unset' };
	const volts = heaterVoltageFromRaw(raw);
	return volts === undefined || volts === null ? { kind: 'unrecognised', raw } : { kind: 'volts', volts };
}

/** The volts the segmented control shows as selected, or 0 for none. */
export function heaterVoltageSelection(reading: HeaterVoltageReading): number {
	return reading.kind === 'volts' ? reading.volts : 0;
}

/** The Settings row text. */
export function heaterVoltageText(reading: HeaterVoltageReading): string {
	switch (reading.kind) {
		case 'unread':
			return '—';
		case 'unset':
			return 'Not set (auto-detect)';
		case 'volts':
			return `${reading.volts} V`;
		case 'unrecognised':
			return 'Not recognised — set it again';
	}
}
