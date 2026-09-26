/**
 * Brew Log rows in the history store (issue #10): the manual-save path
 * persists a zero-sample row with no DE1 machine stamp, the reload keeps
 * the brew fields, and `isBrewLog` is the one local-only gate.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { HistoryStore } from './store.svelte';
import { isBrewLog, isManualLog, type StoredShot } from './model';

const input = {
	method: 'pourover',
	completedAt: 1_700_000_000_000,
	bean: null,
	dose: 15,
	waterG: 250,
	yieldOut: null,
	grinderSetting: '22',
	brewTempC: 94,
	durationMs: 185_000,
	rating: null,
	notes: null,
	nextPlan: null
};

beforeEach(() => localStorage.clear());

describe('HistoryStore.addManualBrew', () => {
	it('saves a zero-sample brew row with NO machine stamp', () => {
		const store = new HistoryStore();
		const row = store.addManualBrew(input);
		expect(row.record.samples).toEqual([]);
		expect(row.brewMethod).toBe('pourover');
		expect(row.metadata.waterG).toBe(250);
		expect(row.machine).toBeUndefined();
		expect(row.decentId).toBeUndefined();
		expect(isBrewLog(row)).toBe(true);
		expect(isManualLog(row)).toBe(true);
	});

	it('keeps the brew fields across a reload (no silent upcast to espresso)', () => {
		const first = new HistoryStore();
		const row = first.addManualBrew(input);
		const reloaded = new HistoryStore().get(row.id);
		expect(reloaded?.brewMethod).toBe('pourover');
		expect(reloaded?.metadata.waterG).toBe(250);
		expect(reloaded?.machine ?? undefined).toBeUndefined();
		expect(reloaded && isBrewLog(reloaded)).toBe(true);
	});
});

describe('isBrewLog', () => {
	it('is keyed on brewMethod alone — machine espresso is not a brew', () => {
		expect(isBrewLog({ brewMethod: null })).toBe(false);
		expect(isBrewLog({})).toBe(false);
		expect(isBrewLog({ brewMethod: 'espresso' })).toBe(true);
		expect(isBrewLog({ brewMethod: 'aeropress' })).toBe(true);
	});
	it('covers guided brews — a recorded weight series does not make a row uploadable', () => {
		const guided = {
			brewMethod: 'pourover',
			record: { duration: 180_000, samples: [] },
			brewSeries: { samples: [{ elapsedMs: 0, weightG: 0 }], stageMarks: [] }
		} as unknown as StoredShot;
		expect(isBrewLog(guided)).toBe(true);
		expect(isManualLog(guided)).toBe(false);
	});
});
