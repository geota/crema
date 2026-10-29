import { describe, expect, it } from 'vitest';
import { excessSteps, stepCapNote, stepCapRefusal } from './step-cap';

describe('step cap (cohort review 2026-10-28, #6 #6)', () => {
	it('a profile within the DE1 limit has no note and no refusal', () => {
		expect(excessSteps(20)).toBe(0);
		expect(stepCapNote(20)).toBeNull();
		expect(stepCapRefusal('Twenty', 20)).toBeNull();
	});

	it('a 24-step profile says how many steps to remove', () => {
		expect(excessSteps(24)).toBe(4);
		expect(stepCapNote(24)).toBe('24 steps — the DE1 runs at most 20. Remove 4 to brew it.');
		expect(stepCapRefusal('Long', 24)).toBe(
			'“Long” has 24 steps; the DE1 runs at most 20. Remove 4 in the profile editor to brew it.'
		);
	});
});
