/**
 * #310 P5w, SPEC decision 5: the History list's derivations are keyed on
 * versions. At 20,000 rows a star tap runs the row predicate zero times,
 * rebuilds no facet, and costs under 16 ms of main-thread work; the stats
 * still count the new rating once their debounce runs. A search, a sort by
 * rating, and a facet field each rerun what reads them.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as filterModule from './list-filter';

const predicate = vi.hoisted(() => ({ calls: 0 }));
vi.mock('./list-filter', async (importOriginal) => {
	const real = await importOriginal<typeof filterModule>();
	return {
		...real,
		shotPasses: (...args: Parameters<typeof real.shotPasses>) => {
			predicate.calls += 1;
			return real.shotPasses(...args);
		}
	};
});

import { flushSync } from 'svelte';
import { HistoryListModel, SEARCH_DEBOUNCE_MS, STATS_DEBOUNCE_MS, type ListInputs } from './list-model.svelte';
import type { ShotRow } from './model';

const N = 20_000;
const PROFILES = ['Best Practice', 'Blooming', 'Default', 'Turbo', 'Londinium'];

function row(i: number): ShotRow {
	return {
		formatVersion: 3,
		id: `shot:${String(i).padStart(6, '0')}`,
		completedAt: 1_767_323_075_678 - i * 3_600_000,
		profileName: PROFILES[i % PROFILES.length],
		metadata: { dose: 18, yieldOut: 36, rating: i % 6, notes: i % 3 === 0 ? `note ${i}` : '', nextPlan: null },
		record: { duration: 30_000 },
		bean: { beanId: `bean:${i % 9}`, name: `Coffee ${i % 9}`, roasterName: 'Sey' },
		tags: i % 4 === 0 ? ['dial-in'] : [],
		summary: {
			v: 1,
			sampleCount: 120,
			brewSampleCount: 0,
			peakWeight: 37,
			finalWeight: 36,
			peakPressure: 9,
			peakTemp: 93,
			spark: null
		}
	} as unknown as ShotRow;
}

/** The page's state, as runes, and a model over it. */
function setup() {
	let rows = $state.raw<ShotRow[]>(Array.from({ length: N }, (_, i) => row(i)));
	let q = $state('');
	let filters = $state<ListInputs>({
		cutoff: null,
		profile: 'all',
		pinnedOnly: false,
		bean: 'all',
		method: 'all',
		tags: [],
		sortField: 'completedAt',
		sortDir: 'desc'
	});
	let index: { rows: ShotRow[]; byId: Map<string, ShotRow> } | null = null;
	const byId = (): Map<string, ShotRow> => {
		if (index?.rows !== rows) index = { rows, byId: new Map(rows.map((r) => [r.id, r])) };
		return index.byId;
	};
	let model!: HistoryListModel;
	const stop = $effect.root(() => {
		model = new HistoryListModel({
			rows: () => rows,
			get: (id) => byId().get(id),
			query: () => q,
			filters: () => filters,
			tagsOf: (s) => s.tags ?? [],
			liveCoffee: () => null
		});
	});
	flushSync();
	/** Every derivation the page reads, as it reads them. */
	const read = () => ({
		filtered: model.filtered,
		shots: model.shots,
		profilesInUse: model.profilesInUse,
		profileCounts: model.profileCounts,
		pinnedCount: model.pinnedCount,
		methodsInUse: model.methodsInUse,
		coffeeKeyById: model.coffeeKeyById,
		beansInUse: model.beansInUse,
		tagFacets: model.tagFacets
	});
	/** Replace row `i` with an edited copy, as `HistoryStore.edit` does. */
	const edit = (i: number, change: (r: ShotRow) => ShotRow) => {
		rows = [...rows.slice(0, i), change(rows[i]), ...rows.slice(i + 1)];
	};
	return {
		model,
		read,
		edit,
		setQuery: (v: string) => (q = v),
		setFilters: (f: Partial<ListInputs>) => (filters = { ...filters, ...f }),
		stop
	};
}

const rate = (r: ShotRow, rating: number): ShotRow => ({ ...r, metadata: { ...r.metadata, rating } });
const note = (r: ShotRow, notes: string): ShotRow => ({ ...r, metadata: { ...r.metadata, notes } });

let page: ReturnType<typeof setup>;
beforeEach(() => {
	vi.useFakeTimers();
	page = setup();
	vi.runAllTimers();
	flushSync();
});
afterEach(() => {
	page.stop();
	vi.useRealTimers();
});

describe('a star tap at 20,000 rows', () => {
	it('runs the row predicate zero times and rebuilds no facet', () => {
		const before = page.read();
		expect(before.filtered).toHaveLength(N);
		predicate.calls = 0;
		const t0 = performance.now();
		page.edit(1234, (r) => rate(r, 5));
		flushSync();
		const after = page.read();
		const ms = performance.now() - t0;
		expect(predicate.calls).toBe(0);
		for (const k of Object.keys(before) as (keyof typeof before)[]) expect(after[k], k).toBe(before[k]);
		expect(page.model.versions.rating).toBeGreaterThan(0);
		// The SPEC's budget, on the Mac: the whole tap, derivations included.
		expect(ms).toBeLessThan(16);
	});

	it('the stats count the new rating once their debounce runs', () => {
		const rated = (): number | null | undefined => page.model.stats?.avgRating;
		const was = rated();
		expect(was).toBeTypeOf('number');
		// Rate every unrated row 5: the mean must move.
		for (let i = 0; i < 60; i += 6) page.edit(i, (r) => rate(r, 5));
		flushSync();
		expect(rated()).toBe(was);
		vi.advanceTimersByTime(STATS_DEBOUNCE_MS - 1);
		flushSync();
		expect(rated()).toBe(was);
		vi.advanceTimersByTime(1);
		flushSync();
		expect(rated()).toBeGreaterThan(was!);
	});

	it('reruns the predicate when sorting by rating, and moves the row', () => {
		page.setFilters({ sortField: 'rating', sortDir: 'desc' });
		flushSync();
		const top = page.read().filtered[0];
		predicate.calls = 0;
		page.edit(1, (r) => rate(r, 0));
		page.edit(2, (r) => rate(r, 5));
		flushSync();
		expect(predicate.calls).toBe(N);
		expect(page.read().filtered[0].id).toBe('shot:000002');
		expect(top).toBeDefined();
	});
});

describe('the other change kinds', () => {
	it('a note runs no predicate without a search, and the whole pass with one', () => {
		page.read();
		predicate.calls = 0;
		page.edit(7, (r) => note(r, 'chocolate'));
		flushSync();
		page.read();
		expect(predicate.calls).toBe(0);

		page.setQuery('chocolate');
		flushSync();
		vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
		flushSync();
		expect(page.read().filtered.map((s) => s.id)).toEqual(['shot:000007']);
		predicate.calls = 0;
		page.edit(9, (r) => note(r, 'chocolate again'));
		flushSync();
		expect(page.read().filtered.map((s) => s.id)).toEqual(['shot:000007', 'shot:000009']);
		expect(predicate.calls).toBe(N);
		// A star tap during the search still runs none.
		predicate.calls = 0;
		page.edit(9, (r) => rate(r, 1));
		flushSync();
		page.read();
		expect(predicate.calls).toBe(0);
	});

	it('a facet field reruns the predicate and the facets', () => {
		const before = page.read();
		predicate.calls = 0;
		page.edit(3, (r) => ({ ...r, profileName: 'New profile' }));
		flushSync();
		const after = page.read();
		expect(predicate.calls).toBe(N);
		expect(after.profilesInUse).not.toBe(before.profilesInUse);
		expect(after.profilesInUse).toContain('New profile');
		expect(after.profileCounts.get('New profile')).toBe(1);
	});

	it('a remote version stamp moves nothing', () => {
		const before = page.read();
		const list = page.model.versions.list;
		page.edit(5, (r) => ({ ...r, visualizerUpdatedAt: 99 }));
		flushSync();
		expect(page.read().filtered).toBe(before.filtered);
		expect(page.model.versions.list).toBe(list);
	});

	it('the search applies 150 ms after the last keystroke', () => {
		page.read();
		predicate.calls = 0;
		for (const typed of ['n', 'no', 'not', 'note 12']) {
			page.setQuery(typed);
			flushSync();
			vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS - 10);
			flushSync();
			page.read();
		}
		expect(predicate.calls).toBe(0);
		vi.advanceTimersByTime(10);
		flushSync();
		const ids = page.read().filtered.map((s) => s.id);
		expect(predicate.calls).toBe(N);
		expect(ids).toContain('shot:000012');
		expect(ids.every((id) => /note 12/.test(page.read().filtered.find((s) => s.id === id)!.metadata.notes ?? ''))).toBe(true);
	});
});
