import { describe, expect, it } from 'vitest';
import { CalTarget, MachineState, type CoreOutput } from '#lib/core/index.js';
import {
	CALIBRATION_BANDS,
	CalibrationRefusedError,
	calibrationRefusalIn,
	calibrationRefusalMessage
} from './refusal';

describe('calibration refusal copy (cohort review 02 P1-2, P3-6)', () => {
	it('finds the refusal in a core output', () => {
		const out = {
			commands: [],
			events: [
				{
					type: 'CalibrationRefused',
					content: { target: CalTarget.Pressure, reason: { kind: 'ZeroReported' } }
				}
			]
		} as unknown as CoreOutput;
		expect(calibrationRefusalIn(out)).toEqual({ kind: 'ZeroReported' });
		expect(calibrationRefusalIn({ commands: [], events: [] } as unknown as CoreOutput)).toBeNull();
	});

	it('names the state and the range', () => {
		expect(
			calibrationRefusalMessage({
				kind: 'MachineNotIdle',
				details: { state: MachineState.Espresso }
			})
		).toContain('Espresso');
		expect(
			calibrationRefusalMessage({ kind: 'OutOfRange', details: { value: 130, min: 2, max: 10 } })
		).toContain('2–10');
		expect(new CalibrationRefusedError({ kind: 'FirmwareRejected' }).message).toBe(
			'The DE1 rejected the calibration request.'
		);
	});

	it('mirrors the core bands', () => {
		expect(CALIBRATION_BANDS[CalTarget.Pressure]).toEqual([2, 10]);
		expect(CALIBRATION_BANDS[CalTarget.Temperature]).toEqual([80, 100]);
		expect(CALIBRATION_BANDS[CalTarget.Flow]).toEqual([0.1, 8]);
	});
});
