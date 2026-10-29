import { describe, expect, it } from 'vitest';
import { heaterVoltageSelection, heaterVoltageText, readHeaterVoltage } from './heater-voltage';

describe('heater voltage readout (cohort review 2026-10-28, #2 N7)', () => {
	it('decodes the firmware +1000 marker through the core', () => {
		expect(heaterVoltageText(readHeaterVoltage(1120))).toBe('120 V');
		expect(heaterVoltageText(readHeaterVoltage(1230))).toBe('230 V');
		expect(heaterVoltageText(readHeaterVoltage(120))).toBe('120 V');
		expect(heaterVoltageSelection(readHeaterVoltage(1230))).toBe(230);
	});

	it('a doubled marker reads as not recognised, never "1120 V"', () => {
		const r = readHeaterVoltage(2120);
		expect(r.kind).toBe('unrecognised');
		expect(heaterVoltageText(r)).toBe('Not recognised — set it again');
		expect(heaterVoltageSelection(r)).toBe(0);
	});

	it('unread and not-set stay distinct', () => {
		expect(heaterVoltageText(readHeaterVoltage(null))).toBe('—');
		expect(heaterVoltageText(readHeaterVoltage(0))).toBe('Not set (auto-detect)');
	});
});
