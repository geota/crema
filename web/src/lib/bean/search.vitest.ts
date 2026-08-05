/**
 * `$lib/bean/search.vitest` — the web side of the core bean matcher
 * (issue 62). The ranking itself is covered by the Rust unit tests in
 * `de1_domain::bean_search`; what needs proving here is that the wasm
 * bridge round-trips, that the corpus memo does not go stale, and that a
 * broken call degrades to "no search" instead of an empty library.
 * Run: `pnpm test:vitest`.
 */

import { beforeAll, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '$lib/testing/test-init';
import { blankBean, blankRoaster, type Bean, type Roaster } from './model.ts';
import {
	SearchField,
	explanatoryHit,
	hitForField,
	scoreMap,
	searchBeans,
	searchRoasters
} from './search.ts';

beforeAll(async () => {
	await initTestWasm();
});

function bean(name: string, patch: Partial<Bean> = {}): Bean {
	return { ...blankBean(), name, ...patch };
}

describe('searchBeans', () => {
	it('is inactive for a blank query, so the caller keeps its own order', () => {
		const res = searchBeans([bean('Yirgacheffe')], [], '   ');
		expect(res.active).toBe(false);
		expect(res.byId.size).toBe(0);
	});

	it('matches the fields the old substring chain never reached', () => {
		const b = bean('Lot 42', {
			origin: { ...blankBean().origin, processing: 'Anaerobic Natural' },
			notes: 'bought at the airport',
			grinder: 'Niche Zero'
		});
		for (const q of ['anaerobic', 'airport', 'niche']) {
			expect(searchBeans([b], [], q).byId.has(b.id), `query ${q}`).toBe(true);
		}
	});

	it('tolerates a typo', () => {
		const b = bean('Lot 42', {
			origin: { ...blankBean().origin, region: 'Yirgacheffe' }
		});
		expect(searchBeans([b], [], 'yirgacheff').byId.has(b.id)).toBe(true);
	});

	it('matches a bag through its roastery name', () => {
		const r: Roaster = blankRoaster('Onyx Coffee Lab');
		const b = bean('Lot 42', { roasterId: r.id });
		const hit = searchBeans([b], [r], 'onyx').byId.get(b.id);
		expect(hit?.fields[0].field).toBe(SearchField.Roaster);
	});

	it('scores a name hit above a tasting-note hit', () => {
		const named = bean('Jasmine Lot');
		const noted = bean('Lot 9', { tastingNotes: 'peach, jasmine, syrupy' });
		const res = searchBeans([noted, named], [], 'jasmine');
		expect(res.score(named.id)).toBeGreaterThan(res.score(noted.id));
	});

	it('scores a non-match as -1 so a sort comparator sinks it', () => {
		const b = bean('Yirgacheffe');
		expect(searchBeans([b], [], 'kenya').score(b.id)).toBe(-1);
	});

	it('re-runs against an edited library rather than a stale corpus', () => {
		const first = [bean('Yirgacheffe')];
		expect(searchBeans(first, [], 'kenya').byId.size).toBe(0);
		// A new array identity — what the store hands out after an edit.
		const second = [...first, bean('Kenya Nyeri')];
		expect(searchBeans(second, [], 'kenya').byId.size).toBe(1);
	});

	it('falls back to "no search" if the bridge throws, never to an empty library', () => {
		const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
		// A circular object cannot be stringified — the corpus build throws.
		const b = bean('Yirgacheffe') as Bean & { self?: unknown };
		b.self = b;
		const res = searchBeans([b], [], 'yirg');
		expect(res.active).toBe(false);
		expect(spy).toHaveBeenCalled();
		spy.mockRestore();
	});
});

describe('searchRoasters', () => {
	it('searches name, city, country and notes', () => {
		const r: Roaster = {
			...blankRoaster('Onyx Coffee Lab'),
			city: 'Rogers',
			country: 'USA',
			notes: 'subscription every fortnight'
		};
		for (const q of ['onyx', 'rogers', 'usa', 'fortnight']) {
			expect(searchRoasters([r], q).byId.has(r.id), `query ${q}`).toBe(true);
		}
		expect(searchRoasters([r], 'kalita').byId.size).toBe(0);
	});
});

describe('hit helpers', () => {
	it('suppresses name and roaster hits from the explanatory line', () => {
		const b = bean('Yirgacheffe');
		const hit = searchBeans([b], [], 'yirg').byId.get(b.id);
		expect(hitForField(hit, SearchField.Name)).not.toBeNull();
		expect(explanatoryHit(hit)).toBeNull();
	});

	it('surfaces a hidden-field hit as the explanatory line', () => {
		const b = bean('Lot 9', { tastingNotes: 'peach, jasmine, syrupy' });
		const hit = searchBeans([b], [], 'jasmine').byId.get(b.id);
		const why = explanatoryHit(hit);
		expect(why?.field).toBe(SearchField.TastingNotes);
		expect(why?.label).toBe('Tasting notes');
		expect(why?.snippet.filter((s) => s.hit).map((s) => s.text)).toEqual(['jasmine']);
	});

	it('builds the picker score map only while a query is running', () => {
		const b = bean('Yirgacheffe');
		expect(scoreMap(searchBeans([b], [], ''))).toBeNull();
		expect(scoreMap(searchBeans([b], [], 'yirg'))?.get(b.id)).toBeGreaterThan(0);
	});
});
