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
	historyFullNotice,
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

	it('is checked at the cut, not at the end of a list over the cap', () => {
		const rows = Array.from({ length: 350 }, (_, i) => ({ completedAt: 1_000 - i }));
		// Between ranks 320 and 321: newer than the last row, but cut at 300.
		expect(fitsHistory(rows, 1_000 - 320 + 0.5, 300)).toBe(false);
		expect(fitsHistory(rows, 1_000 - 299, 300)).toBe(true);
	});
});

describe('a row just made on this device', () => {
	const later = NOW + 1_000 * HOUR;
	const fullAheadOfTheClock = () => {
		const store = new HistoryStore();
		for (let i = 0; i < 300; i++) store.addManualBrew(brew(later - i * HOUR));
		return store;
	};

	it('stays when the clock is behind a full history, and the oldest other row goes', () => {
		const store = fullAheadOfTheClock();
		const oldest = store.all.at(-1)!;
		const made = store.addManualBrew(brew(NOW));
		expect(store.get(made.id)).toBeDefined();
		expect(store.get(oldest.id)).toBeUndefined();
		expect(store.all).toHaveLength(300);
	});

	it('stays when a machine shot is captured behind a full history', () => {
		const store = fullAheadOfTheClock();
		const oldest = store.all.at(-1)!;
		const captured = {
			...structuredClone(store.all[0]),
			id: 'captured',
			completedAt: NOW,
			brewMethod: null,
			record: { duration: 30_000, samples: [{ elapsedMs: 0 }] }
		};
		const record = store.recordCaptured(captured as unknown as Record<string, unknown>);
		expect(record?.id).toBe('captured');
		expect(store.get('captured')).toBeDefined();
		expect(store.get(oldest.id)).toBeUndefined();
	});
});

describe('the cap counts live rows', () => {
	const withTombstones = () => {
		const store = new HistoryStore();
		for (let i = 0; i < 300; i++) store.addManualBrew(brew(NOW - i * HOUR));
		for (const s of store.all.slice(0, 10)) {
			store.bindVisualizerId(s.id, `remote-${s.id}`);
			store.delete(s.id);
		}
		return store;
	};

	it('leaves room for a brew when tombstones fill the list', () => {
		const store = withTombstones();
		expect(store.all).toHaveLength(290);
		expect(store.fitsHistory(NOW - 1_000 * HOUR)).toBe(true);
		const old = store.addManualBrew(brew(NOW - 1_000 * HOUR));
		expect(store.get(old.id)).toBeDefined();
		expect(store.all).toHaveLength(291);
	});

	it('keeps tombstones, whose remote delete is still to go out', () => {
		const store = withTombstones();
		for (let i = 0; i < 20; i++) store.addManualBrew(brew(NOW + (i + 1) * 60_000));
		expect(store.all).toHaveLength(300);
		expect(store.rawAll).toHaveLength(310);
	});
});

describe('inserting a batch', () => {
	const template = (() => {
		const row = new HistoryStore().addManualBrew(brew(NOW));
		localStorage.clear();
		return row;
	})();
	const shot = (id: string, completedAt: number): StoredShot => ({ ...template, id, completedAt });
	const nearlyFull = () => {
		const store = new HistoryStore();
		for (let i = 0; i < 299; i++) store.addManualBrew(brew(NOW - i * HOUR));
		return store;
	};

	it('reports the rows kept and the rows the cap dropped', () => {
		const store = nearlyFull();
		const { kept, dropped } = store.insertMany([
			shot('new', NOW + HOUR),
			shot('old', NOW - 1_000 * HOUR),
			shot('older', NOW - 2_000 * HOUR)
		]);
		expect(kept.map((s) => s.id)).toEqual(['new']);
		expect(dropped).toBe(2);
		expect(store.all).toHaveLength(300);
	});

	it('doesn’t count a row a later row of the batch cut', () => {
		const store = nearlyFull();
		const { kept, dropped } = store.insertMany([shot('a', NOW - 1_000 * HOUR), shot('b', NOW + HOUR)]);
		expect(kept.map((s) => s.id)).toEqual(['b']);
		expect(dropped).toBe(1);
	});

	it('counts an existing row the batch pushed out', () => {
		const store = nearlyFull();
		const oldest = store.all.at(-1)!;
		const { kept, dropped } = store.insertMany([shot('a', NOW + HOUR), shot('b', NOW + 2 * HOUR)]);
		expect(kept).toHaveLength(2);
		expect(dropped).toBe(1);
		expect(store.get(oldest.id)).toBeUndefined();
	});

	it('pull_never_evicts_at_cap: a full history refuses a pulled row and loses nothing', () => {
		const store = nearlyFull();
		store.addManualBrew(brew(NOW - 400 * HOUR));
		const before = store.all.map((s) => s.id);
		expect(store.freeSlots).toBe(0);

		expect(store.insertPulled(shot('new', NOW + HOUR))).toBe(false);

		expect(store.all.map((s) => s.id)).toEqual(before);
		expect(new HistoryStore().all.map((s) => s.id)).toEqual(before);
	});

	it('the notice says Re-sync, because a plain Sync won’t fetch refused rows', () => {
		expect(historyFullNotice(2, 300)).toBe(
			'History is full at 300 shots, so 2 shots from Visualizer weren\u2019t added. After freeing up space, use Re-sync shots to fetch them.'
		);
		expect(historyFullNotice(1, 300)).toContain('to fetch it.');
	});

	it('a pull fills the last free slot by date', () => {
		const store = nearlyFull();
		expect(store.freeSlots).toBe(1);
		expect(store.insertPulled(shot('new', NOW + HOUR))).toBe(true);
		expect(store.all[0].id).toBe('new');
		expect(store.all).toHaveLength(300);
	});

	it('a tombstone doesn’t take a slot, and a pull leaves it', () => {
		const store = nearlyFull();
		const row = { ...shot('deleted', NOW), visualizerId: 'v-1' };
		expect(store.insertPulled(row)).toBe(true);
		store.delete('deleted');
		expect(store.freeSlots).toBe(1);
		expect(store.insertPulled(shot('new', NOW + HOUR))).toBe(true);
		expect(store.get('deleted')?.deletedAt).not.toBeNull();
		expect(store.rawAll).toHaveLength(301);
	});

	it('a list over the cap stays as it is through a pull', () => {
		const rows = Array.from({ length: 350 }, (_, i) => shot(`r${i}`, NOW - i * HOUR));
		localStorage.setItem('crema.history.v2', JSON.stringify(rows));
		const store = new HistoryStore();
		expect(store.insertPulled(shot('new', NOW + HOUR))).toBe(false);
		expect(store.all).toHaveLength(350);
	});

	it('leaves a list over the cap alone when nothing is new', () => {
		const rows = Array.from({ length: 350 }, (_, i) => shot(`r${i}`, NOW - i * HOUR));
		localStorage.setItem('crema.history.v2', JSON.stringify(rows));
		const store = new HistoryStore();
		expect(store.insertMany([rows[0], rows[1]])).toEqual({ kept: [], dropped: 0 });
		expect(store.all).toHaveLength(350);
	});
});

describe('a row with no date', () => {
	const stored = () => JSON.parse(localStorage.getItem('crema.history.v2') as string);

	it('stays at the tail and keeps what was stored', () => {
		const first = new HistoryStore();
		first.addManualBrew(brew(NOW));
		const raw = stored();
		const undated = { ...raw[0], id: 'undated', completedAt: 0 };
		localStorage.setItem('crema.history.v2', JSON.stringify([undated, ...raw]));
		const store = new HistoryStore();
		expect(store.all.at(-1)?.id).toBe('undated');
		store.addManualBrew(brew(NOW - HOUR));
		expect(stored().find((s: StoredShot) => s.id === 'undated').completedAt).toBe(0);
		expect(new HistoryStore().all.at(-1)?.id).toBe('undated');
	});

	it('doesn’t seed the next log', () => {
		const first = new HistoryStore();
		first.addManualBrew(brew(NOW, 15));
		const raw = stored();
		const undated = { ...raw[0], id: 'undated', completedAt: 0 };
		undated.metadata = { ...undated.metadata, dose: 30 };
		localStorage.setItem('crema.history.v2', JSON.stringify([undated, ...raw]));
		expect(new HistoryStore().all[0].metadata.dose).toBe(15);
	});
});

// Only a zone with a clock change has such an hour: run under
// TZ=America/New_York to exercise it. UTC skips it.
const repeatedFirst = new Date(2026, 10, 1, 1, 30).getTime();
const repeats = toLocalInput(repeatedFirst) === toLocalInput(repeatedFirst + HOUR);
describe.runIf(repeats)('an edit in the hour a clock change repeats', () => {
	it('stays on the pass the brew was on', () => {
		const later = repeatedFirst + 10 * 60_000;
		expect(fromLocalInput(toLocalInput(later), repeatedFirst + HOUR)).toBe(later + HOUR);
		expect(fromLocalInput(toLocalInput(later), repeatedFirst)).toBe(later);
		expect(fromLocalInput(toLocalInput(later))).toBe(later);
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
