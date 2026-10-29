/**
 * `#lib/calibration/refusal` — shell copy for the core's calibration guard
 * (`core/de1-app/src/calibration_guard.rs`, cohort review 02 P1-2 / P3-6).
 *
 * The core is authoritative: it refuses a calibration write or reset with an
 * `Event::CalibrationRefused` and sends nothing. This module turns that
 * refusal into a sentence, and mirrors the per-sensor bands so the modal's
 * inputs can carry `min` / `max` (a convenience; the core still checks).
 */

import { CalTarget, type CalibrationRefusal, type CoreOutput } from '#lib/core/index.js';

/** Mirror of `calibration_guard::calibration_range`, canonical units. */
export const CALIBRATION_BANDS: Readonly<Record<CalTarget, readonly [number, number]>> = {
	[CalTarget.Flow]: [0.1, 8.0],
	[CalTarget.Pressure]: [2.0, 10.0],
	[CalTarget.Temperature]: [80.0, 100.0]
};

/** Thrown by the app's calibration actions when the core refused them. */
export class CalibrationRefusedError extends Error {
	readonly reason: CalibrationRefusal;
	constructor(reason: CalibrationRefusal) {
		super(calibrationRefusalMessage(reason));
		this.name = 'CalibrationRefusedError';
		this.reason = reason;
	}
}

/** The refusal in `output`, if the core refused the request. */
export function calibrationRefusalIn(output: CoreOutput): CalibrationRefusal | null {
	for (const event of output.events) {
		if (event.type === 'CalibrationRefused') return event.content.reason;
	}
	return null;
}

/** One sentence the user can act on. Values in canonical units (°C / bar / ml/s). */
export function calibrationRefusalMessage(reason: CalibrationRefusal): string {
	switch (reason.kind) {
		case 'NonFinite':
			return 'Enter a number for both values.';
		case 'ZeroReported':
			return 'The reported value cannot be zero: the DE1 divides by it.';
		case 'OutOfRange':
			return `${reason.details.value} is outside the allowed range ${reason.details.min}–${reason.details.max}. Check the units.`;
		case 'MachineNotIdle':
			return `Calibrate only while the DE1 is idle or asleep (it is in ${reason.details.state}).`;
		case 'MachineStateUnknown':
			return 'Wait for the DE1 to report its state, then try again.';
		case 'FirmwareRejected':
			return 'The DE1 rejected the calibration request.';
		default:
			return 'The calibration request was refused.';
	}
}
