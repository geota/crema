/**
 * Brew Log rows in the history store (issue #10): the manual-save path
 * persists a zero-sample row with no DE1 machine stamp, the reload keeps
 * the brew fields, and `isBrewLog` is the one local-only gate.
 */
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '$lib/testing/test-init';
import { BeanLibraryStore } from '$lib/bean/store.svelte';
import { coerceBean } from '$lib/bean/model';
import { brewLogSeeds, brewMethodPresets } from '$lib/brew/methods';
import { brews_remaining_estimate } from '$lib/wasm/de1_wasm';
import { HistoryStore } from './store.svelte';
import { isBrewLog, isManualLog, ratioLabel, type StoredShot } from './model';

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

// ── #99 drift fixes: core-backed rules the web now calls ──────────────

describe('isBrewLog / manual-save normalization (drift bug 8)', () => {
	it('treats an empty brewMethod as a brew, like the core', () => {
		expect(isBrewLog({ brewMethod: '' })).toBe(true);
	});

	it('never persists an empty method', () => {
		const store = new HistoryStore();
		const row = store.addManualBrew({ ...input, method: '  ' });
		expect(row.brewMethod).toBeNull();
	});

	it('a blank method patch leaves the manual row a brew', () => {
		const store = new HistoryStore();
		const row = store.addManualBrew(input);
		store.updateManualBrew(row.id, { method: '' });
		expect(store.get(row.id)?.brewMethod).toBe('pourover');
	});
});

describe('core-backed brew-log rules', () => {
	beforeAll(async () => {
		await initTestWasm();
	});

	it('ratioLabel uses the core rule — no invented 18 g espresso dose (drift bug 6)', () => {
		const store = new HistoryStore();
		const esp = store.addManualBrew({ ...input, method: 'espresso', dose: null, waterG: null, yieldOut: 36 });
		expect(ratioLabel(esp)).toBe('1:—');
		const pour = store.addManualBrew(input);
		expect(ratioLabel(pour)).toBe('1:16.7');
	});

	it('brewLogSeeds opens on the last-used method and keeps the preset table in core', () => {
		localStorage.setItem('crema.brewlog.lastMethod.v1', 'aeropress');
		const s = brewLogSeeds({ method: null, beanId: null, beanGrinderSetting: '20', prefill: undefined, rows: [] });
		expect(s.method).toBe('aeropress');
		expect(s.dose).toBe(14);
		expect(s.water).toBe(220);
		expect(s.grind).toBe(20);
		expect(brewMethodPresets().map((p) => p.id)).not.toContain('other');
		expect(brewMethodPresets()[0]).toMatchObject({ id: 'espresso', label: 'Espresso', seedYieldG: 36 });
	});

	it('resettleBean leaves a full bag alone when the dose is unchanged (drift bug 7)', () => {
		const lib = new BeanLibraryStore();
		lib.upsertBean({ ...coerceBean({ id: 'b1', name: 'House' })!, bagSize: 250, remaining: 250 });
		lib.resettleBean('b1', 18, 18);
		expect(lib.getBean('b1')?.remaining).toBe(250);
		// Only the net +2 g comes off; the old full-credit-then-debit lost 18 g.
		lib.resettleBean('b1', 18, 20);
		expect(lib.getBean('b1')?.remaining).toBe(248);
	});

	it('brews_remaining_estimate uses the bag’s own mean dose', () => {
		expect(brews_remaining_estimate(200, Float32Array.from([30, 30]))).toBe(6);
		expect(brews_remaining_estimate(180, new Float32Array())).toBe(10);
	});
});
