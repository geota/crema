/**
 * `$lib/bean/filter.vitest` — the web side of the core bean facet filter
 * (geota/crema#124). The composition rules are covered by the Rust tests in
 * `de1_domain::bean_filter`; here we prove the wasm bridge round-trips the
 * page's facets + search hits, and that a failing call never hides the
 * library. Run: `pnpm test:vitest`.
 */

import { beforeAll, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '$lib/testing/test-init';
import { blankBean, type Bean } from './model.ts';
import { BeanStatusFilter, filterBeans, toQuery, type BeanFacets } from './filter.ts';
import { noSearch, searchBeans } from './search.ts';

beforeAll(async () => {
	await initTestWasm();
});

let n = 0;
function bean(name: string, patch: Partial<Bean> = {}): Bean {
	n += 1;
	return { ...blankBean(), id: `bean:${n}`, name, ...patch };
}

const facets = (patch: Partial<BeanFacets> = {}): BeanFacets => ({
	status: BeanStatusFilter.All,
	includeArchived: false,
	roast: null,
	tags: [],
	roasterId: null,
	...patch
});

const lightLive = bean('Light live', { roastLevel: 2 });
const darkLive = bean('Dark live', { roastLevel: 8 });
const lightOld = bean('Light archived', { roastLevel: 2, archivedAt: 1 });
const darkOld = bean('Ethiopia archived', { roastLevel: 8, archivedAt: 1 });
const library = [lightLive, darkLive, lightOld, darkOld];

describe('filterBeans', () => {
	it('keeps archived bags hidden by default', () => {
		const r = filterBeans(library, facets(), noSearch());
		expect(r.ids).toEqual([lightLive.id, darkLive.id]);
		expect(r.archivedHidden).toBe(2);
		expect(r.statusCounts.archived).toBe(2);
	});

	it('filters the archive by roast level (#124)', () => {
		const r = filterBeans(
			library,
			facets({ status: BeanStatusFilter.Archived, roast: 'light' }),
			noSearch()
		);
		expect(r.ids).toEqual([lightOld.id]);
		expect(r.roastCounts).toEqual({ light: 1, medium: 0, dark: 1 });
	});

	it('searches within the archive', () => {
		const hits = searchBeans(library, [], 'ethiopia');
		const r = filterBeans(library, facets({ status: BeanStatusFilter.Archived }), hits);
		expect(r.ids).toEqual([darkOld.id]);
		expect(r.statusCounts.archived).toBe(1);
	});

	it('include-archived mixes them in and the counts follow', () => {
		const r = filterBeans(library, facets({ includeArchived: true, roast: 'dark' }), noSearch());
		expect(r.ids).toEqual([darkLive.id, darkOld.id]);
		expect(r.showingArchived).toBe(true);
		expect(r.statusCounts.all).toBe(2);
		expect(r.roastCounts).toEqual({ light: 2, medium: 0, dark: 2 });
	});

	it('maps the page facets onto the core query', () => {
		const q = toQuery(facets({ roast: 'medium', roasterId: 'r1', tags: ['washed'] }), noSearch());
		expect(q).toEqual({
			status: 'all',
			includeArchived: false,
			roast: 'medium',
			tags: ['washed'],
			roasterId: 'r1',
			matchIds: undefined
		});
	});

	it('falls back to the whole library when the core call fails', () => {
		const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
		const broken = [{ ...lightLive, roastLevel: 'nope' as unknown as number }];
		const r = filterBeans(broken, facets(), noSearch());
		expect(r.ids).toEqual([lightLive.id]);
		expect(spy).toHaveBeenCalled();
		spy.mockRestore();
	});
});
