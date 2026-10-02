/**
 * `$lib/bean/catalogue.vitest` — the Visualizer catalogue search controller
 * (debounce, min length, last-query-wins) and the pick → bean autofill rule
 * (fill empty only / replace all, links always recorded) through the real wasm
 * core. Run: `pnpm test:vitest`.
 */

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '$lib/testing/test-init';
import { blankBean } from './model.ts';
import {
	autofillFromCatalogue,
	createCatalogueSearch,
	parseCataloguePage,
	type CatalogueCoffeeBag,
	type CataloguePage,
	type CatalogueSearchState
} from './catalogue.ts';

beforeAll(async () => {
	await initTestWasm();
});

const BODY = {
	data: [
		{
			id: 'cb-1',
			canonical_roaster_id: 'cr-1',
			canonical_roaster_name: 'Onyx Coffee Lab',
			name: 'Ethiopia Guji Hambela',
			roast_level: 'Light',
			country: 'Ethiopia',
			region: 'Guji',
			variety: 'Heirloom',
			processing: 'Washed',
			tasting_notes: 'Bergamot, peach',
			url: 'https://onyx.coffee/hambela',
			created_at: '2026-01-01T00:00:00Z',
			updated_at: '2026-01-01T00:00:00Z'
		}
	],
	paging: { count: 1, page: 1, limit: 10, pages: 1 }
};

function entry(): CatalogueCoffeeBag {
	return parseCataloguePage(BODY).entries[0];
}

describe('parseCataloguePage', () => {
	it('normalises rows with a country · process meta line', () => {
		const page = parseCataloguePage(BODY);
		expect(page.entries).toHaveLength(1);
		expect(page.entries[0]).toMatchObject({
			roasterName: 'Onyx Coffee Lab',
			canonicalRoasterId: 'cr-1',
			meta: 'Ethiopia · Washed'
		});
	});

	it('treats a garbage body as an empty page', () => {
		expect(parseCataloguePage(null).entries).toEqual([]);
		expect(parseCataloguePage({ data: 'nope' }).entries).toEqual([]);
	});
});

describe('autofillFromCatalogue', () => {
	it('fills every empty field, names the roaster and records both links', () => {
		const r = autofillFromCatalogue(blankBean('bean:1'), entry(), { roasterSet: false });
		expect(r.bean.name).toBe('Ethiopia Guji Hambela');
		expect(r.roasterName).toBe('Onyx Coffee Lab');
		expect(r.bean.origin).toMatchObject({
			country: 'Ethiopia',
			region: 'Guji',
			variety: 'Heirloom',
			processing: 'Washed'
		});
		expect(r.bean.roastLevel).toBe(2);
		expect(r.bean.tastingNotes).toBe('Bergamot, peach');
		expect(r.bean.url).toBe('https://onyx.coffee/hambela');
		expect(r.bean.canonicalCoffeeBagId).toBe('cb-1');
		expect(r.bean.canonicalRoasterId).toBe('cr-1');
		expect(r.filled.slice(0, 2)).toEqual(['name', 'roaster']);
	});

	it('does not clobber fields the user typed', () => {
		const bean = { ...blankBean('bean:1'), name: 'My Hambela', roastLevel: 8, tastingNotes: 'jammy' };
		bean.origin = { ...bean.origin, country: 'Kenya' };
		const r = autofillFromCatalogue(bean, entry(), { roasterSet: true });
		expect(r.bean.name).toBe('My Hambela');
		expect(r.roasterName).toBeNull();
		expect(r.bean.roastLevel).toBe(8);
		expect(r.bean.tastingNotes).toBe('jammy');
		expect(r.bean.origin.country).toBe('Kenya');
		// …but empty ones still fill, and the link is recorded.
		expect(r.bean.origin.region).toBe('Guji');
		expect(r.bean.canonicalCoffeeBagId).toBe('cb-1');
	});

	it('replaces filled fields on "replace all" (catalogue blanks keep user values)', () => {
		const bean = { ...blankBean('bean:1'), name: 'My Hambela', roastLevel: 8 };
		bean.origin = { ...bean.origin, country: 'Kenya', farmer: 'Tarekech' };
		const r = autofillFromCatalogue(bean, entry(), { roasterSet: true, replaceAll: true });
		expect(r.bean.name).toBe('Ethiopia Guji Hambela');
		expect(r.roasterName).toBe('Onyx Coffee Lab');
		expect(r.bean.roastLevel).toBe(2);
		expect(r.bean.origin.country).toBe('Ethiopia');
		expect(r.bean.origin.farmer).toBe('Tarekech');
	});
});

describe('createCatalogueSearch', () => {
	beforeEach(() => vi.useFakeTimers());
	afterEach(() => vi.useRealTimers());

	const page = (name: string): CataloguePage => ({
		entries: [{ ...entry(), name }],
		count: 1,
		page: 1,
		pages: 1
	});

	function harness(search: (q: string) => Promise<CataloguePage>) {
		const states: CatalogueSearchState[] = [];
		const s = createCatalogueSearch({ search, onState: (st) => states.push(st), debounceMs: 300 });
		return { s, states, last: () => states[states.length - 1] };
	}

	it('debounces keystrokes into one request for the final query', async () => {
		const search = vi.fn((q: string) => Promise.resolve(page(q)));
		const { s, last } = harness(search);
		s.setQuery('on');
		vi.advanceTimersByTime(100);
		s.setQuery('ony');
		vi.advanceTimersByTime(100);
		s.setQuery('onyx ');
		expect(last().loading).toBe(true);
		vi.advanceTimersByTime(299);
		expect(search).not.toHaveBeenCalled();
		await vi.advanceTimersByTimeAsync(1);
		expect(search).toHaveBeenCalledTimes(1);
		expect(search).toHaveBeenCalledWith('onyx');
		expect(last()).toMatchObject({ query: 'onyx', loading: false, error: null });
		expect(last().results[0].name).toBe('onyx');
	});

	it('skips queries shorter than the minimum and clears results', async () => {
		const search = vi.fn((q: string) => Promise.resolve(page(q)));
		const { s, last } = harness(search);
		s.setQuery('o');
		await vi.advanceTimersByTimeAsync(500);
		expect(search).not.toHaveBeenCalled();
		expect(last()).toMatchObject({ loading: false, results: [] });
	});

	it('drops a stale response that resolves after a newer query', async () => {
		let resolveSlow!: (p: CataloguePage) => void;
		const search = vi.fn((q: string) =>
			q === 'slow' ? new Promise<CataloguePage>((r) => (resolveSlow = r)) : Promise.resolve(page(q))
		);
		const { s, last } = harness(search);
		s.setQuery('slow');
		await vi.advanceTimersByTimeAsync(300);
		s.setQuery('fast');
		await vi.advanceTimersByTimeAsync(300);
		expect(last().results[0].name).toBe('fast');
		resolveSlow(page('slow'));
		await vi.advanceTimersByTimeAsync(0);
		expect(last().results[0].name).toBe('fast');
	});

	it('reports a failure message for the latest query', async () => {
		const { s, last } = harness(() => Promise.reject(new Error('Visualizer HTTP 500')));
		s.setQuery('onyx');
		await vi.advanceTimersByTimeAsync(300);
		expect(last()).toMatchObject({ loading: false, results: [], error: 'Visualizer HTTP 500' });
	});

	it('clear() cancels a pending search', async () => {
		const search = vi.fn((q: string) => Promise.resolve(page(q)));
		const { s, last } = harness(search);
		s.setQuery('onyx');
		s.clear();
		await vi.advanceTimersByTimeAsync(500);
		expect(search).not.toHaveBeenCalled();
		expect(last()).toMatchObject({ query: '', loading: false, results: [] });
	});
});
