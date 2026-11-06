/**
 * A logged brew's date (issue #300): future dates are refused, and the history
 * stays newest first however a row was dated, because the core's "last brew"
 * and the 300-row cap both follow list order.
 */
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { brewLogSeeds } from '#lib/brew/methods.js';
import { brews_remaining_estimate } from '#lib/wasm/de1_wasm.js';
import {
	BREW_DATE_FUTURE,
	brewDateError,
	fitsHistory,
	fromLocalInput,
	newestFirst,
	toLocalInput
} from './brew-date';
import { HistoryStore } from './store.svelte';
import type { StoredShot } from './model';

const HOUR = 60 * 60 * 1000;
const NOW = 1_700_000_000_000;

const brew = (completedAt: number, dose = 15) => ({
	method: 'pourover',
	completedAt,
	bean: null,
	dose,
	waterG: 250,
	yieldOut: null,
	grinderSetting: null,
	brewTempC: null,
	durationMs: 180_000,
	rating: null,
	notes: null,
	nextPlan: null
});

/** The rows `LogBrewDialog` hands the core. */
const seedRows = (store: HistoryStore) =>
	store.all.map((s) => ({
		brewMethod: s.brewMethod ?? undefined,
		doseG: s.metadata.dose ?? undefined,
		waterG: s.metadata.waterG ?? undefined,
		durationMs: s.record.duration
	}));

beforeEach(() => localStorage.clear());

describe('brewDateError', () => {
	it('takes now and any time before it', () => {
		expect(brewDateError(NOW, NOW)).toBeNull();
		expect(brewDateError(NOW - 365 * 24 * HOUR, NOW)).toBeNull();
	});

	it('refuses a future date, even a minute ahead', () => {
		expect(brewDateError(NOW + 60_000, NOW)).toBe(BREW_DATE_FUTURE);
	});

	it('refuses what a date field can hold but a stored row can’t', () => {
		expect(brewDateError(fromLocalInput(''), NOW)).not.toBeNull();
		expect(brewDateError(fromLocalInput('0002-01-01T00:00'), NOW)).not.toBeNull();
	});
});

describe('datetime-local values', () => {
	it('round-trips to the minute in local time', () => {
		const at = new Date(2026, 10, 6, 7, 5).getTime();
		expect(toLocalInput(at)).toBe('2026-11-06T07:05');
		expect(fromLocalInput(toLocalInput(at))).toBe(at);
	});
});

describe('newestFirst', () => {
	it('keeps the order of rows with the same date', () => {
		const rows = [
			{ id: 'a', completedAt: 1 },
			{ id: 'b', completedAt: 2 },
			{ id: 'c', completedAt: 1 }
		];
		expect(newestFirst(rows).map((r) => r.id)).toEqual(['b', 'a', 'c']);
	});
});

describe('a backdated brew', () => {
	beforeAll(() => initTestWasm());

	it('sorts by its date, not by when it was logged', () => {
		const store = new HistoryStore();
		const newest = store.addManualBrew(brew(NOW));
		const older = store.addManualBrew(brew(NOW - 2 * HOUR));
		const backdated = store.addManualBrew(brew(NOW - HOUR));
		expect(store.all.map((s) => s.id)).toEqual([newest.id, backdated.id, older.id]);
	});

	it('does not become the last brew that seeds the next log', () => {
		const store = new HistoryStore();
		store.addManualBrew(brew(NOW, 15));
		store.addManualBrew(brew(NOW - 24 * HOUR, 30));
		const seeds = brewLogSeeds({
			method: 'pourover',
			beanId: null,
			beanGrinderSetting: null,
			prefill: undefined,
			rows: seedRows(store)
		});
		expect(seeds.dose).toBe(15);
	});

	it('leads the seeds when it really is the newest', () => {
		const store = new HistoryStore();
		store.addManualBrew(brew(NOW - 24 * HOUR, 30));
		store.addManualBrew(brew(NOW - HOUR, 15));
		const seeds = brewLogSeeds({
			method: 'pourover',
			beanId: null,
			beanGrinderSetting: null,
			prefill: undefined,
			rows: seedRows(store)
		});
		expect(seeds.dose).toBe(15);
	});

	it('feeds the bag’s brews-left estimate in date order', () => {
		const store = new HistoryStore();
		for (let i = 0; i < 10; i++) store.addManualBrew(brew(NOW - i * HOUR, 20));
		store.addManualBrew(brew(NOW - 100 * HOUR, 100));
		const doses = Float32Array.from(store.all.map((s) => s.metadata.dose ?? 0));
		// Only the newest ten count, and the old 100 g brew isn't one of them.
		expect(brews_remaining_estimate(200, doses)).toBe(10);
	});

	it('leaves the newest rows in place when the history is full', () => {
		const store = new HistoryStore();
		for (let i = 0; i < 300; i++) store.addManualBrew(brew(NOW - i * HOUR));
		const oldest = store.all.at(-1)!;
		const backdated = store.addManualBrew(brew(NOW - 1.5 * HOUR));
		expect(store.all).toHaveLength(300);
		expect(store.all[0].completedAt).toBe(NOW);
		expect(store.get(backdated.id)).toBeDefined();
		expect(store.get(oldest.id)).toBeUndefined();
	});

	it('is flagged before saving when a full history would drop it', () => {
		const rows = [{ completedAt: 3 }, { completedAt: 2 }];
		expect(fitsHistory(rows, 1, 3)).toBe(true);
		expect(fitsHistory(rows, 2, 2)).toBe(true);
		expect(fitsHistory(rows, 1, 2)).toBe(false);
	});
});

describe('changing a saved date', () => {
	it('moves the row to its place and survives a reload', () => {
		const store = new HistoryStore();
		const first = store.addManualBrew(brew(NOW));
		const second = store.addManualBrew(brew(NOW - HOUR));
		store.updateManualBrew(first.id, { completedAt: NOW - 2 * HOUR });
		expect(store.all.map((s) => s.id)).toEqual([second.id, first.id]);
		const reloaded = new HistoryStore();
		expect(reloaded.all.map((s) => s.id)).toEqual([second.id, first.id]);
		expect(reloaded.get(first.id)?.completedAt).toBe(NOW - 2 * HOUR);
	});

	it('refuses a future date and leaves the row as it was', () => {
		const store = new HistoryStore();
		const row = store.addManualBrew(brew(NOW));
		store.updateManualBrew(row.id, { completedAt: Date.now() + 60_000 });
		expect(store.get(row.id)?.completedAt).toBe(NOW);
	});

	it('never touches a machine shot', () => {
		const store = new HistoryStore();
		const shot = {
			...store.addManualBrew(brew(NOW)),
			id: 'machine',
			brewMethod: null,
			record: { duration: 30_000, samples: [] }
		} as StoredShot;
		store.insertPulled(shot);
		store.updateManualBrew('machine', { completedAt: NOW - HOUR });
		expect(store.get('machine')?.completedAt).toBe(NOW);
	});

	it('never touches a guided session, which has a measured series', () => {
		const store = new HistoryStore();
		const row = store.addManualBrew({
			...brew(NOW),
			brewSeries: { samples: [{ elapsedMs: 0, weightG: 0 }], stageMarks: [] }
		} as Parameters<HistoryStore['addManualBrew']>[0]);
		store.updateManualBrew(row.id, { completedAt: NOW - HOUR });
		expect(store.get(row.id)?.completedAt).toBe(NOW);
	});
});

describe('rows saved out of order', () => {
	it('are put in date order on load, so eviction drops the oldest', () => {
		const first = new HistoryStore();
		const a = first.addManualBrew(brew(NOW));
		const b = first.addManualBrew(brew(NOW - HOUR));
		const raw = JSON.parse(localStorage.getItem('crema.history.v2') as string);
		localStorage.setItem('crema.history.v2', JSON.stringify(raw.reverse()));
		expect(new HistoryStore().all.map((s) => s.id)).toEqual([a.id, b.id]);
	});

	it('a pulled shot lands by its date', () => {
		const store = new HistoryStore();
		const newer = store.addManualBrew(brew(NOW));
		const pulled = { ...newer, id: 'pulled', completedAt: NOW - HOUR } as StoredShot;
		store.insertPulled(pulled);
		expect(store.all.map((s) => s.id)).toEqual([newer.id, 'pulled']);
	});
});
