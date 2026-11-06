import { describe, expect, it } from 'vitest';
import { filterScopeKey, liveShot, selectedShot } from './selection';

const a = { id: 'a' };
const b = { id: 'b' };
const c = { id: 'c' };

describe('selectedShot', () => {
	it('keeps the picked shot while the filtered list still shows it', () => {
		expect(selectedShot([a, b, c], 'b')).toBe(b);
	});

	it('falls back to the first filtered shot when the pick is filtered out', () => {
		// A search for "mia" hid shot c; the pane must not keep showing it.
		expect(selectedShot([a, b], 'c')).toBe(a);
	});

	it('shows the first filtered shot when nothing is picked', () => {
		expect(selectedShot([b, c], null)).toBe(b);
	});

	it('shows nothing when the filter matches nothing', () => {
		expect(selectedShot([], 'a')).toBeUndefined();
	});
});

describe('selectedShot with an explicit selection (cohort review 06 #1)', () => {
	const all = [a, b, c];
	const lookup = (id: string) => all.find((s) => s.id === id);
	const remembered = filterScopeKey({ q: '', profile: 'all', bean: 'all', method: 'espresso', tags: [], range: '30d' });

	it('a deep-linked or just-saved shot wins over a remembered filter that hides it', () => {
		// History reopened with "Last 30 days" + espresso; `?shot=c` (a V60 log) is filtered out.
		const pin = { id: 'c', scope: remembered };
		expect(selectedShot([a, b], 'c', { pin, scope: remembered, lookup })).toBe(c);
	});

	it('changing any filter afterwards ends the pin: back to the first match', () => {
		const pin = { id: 'c', scope: remembered };
		const changed = filterScopeKey({ q: 'mia', profile: 'all', bean: 'all', method: 'espresso', tags: [], range: '30d' });
		expect(selectedShot([a, b], 'c', { pin, scope: changed, lookup })).toBe(a);
	});

	it('a merely browsed pick still falls back when a filter hides it', () => {
		expect(selectedShot([a, b], 'c', { pin: null, scope: remembered, lookup })).toBe(a);
	});

	it('a pin for another shot does not resurrect the browsed one', () => {
		const pin = { id: 'b', scope: remembered };
		expect(selectedShot([a], 'c', { pin, scope: remembered, lookup })).toBe(a);
	});

	it('a pinned shot that no longer exists falls back too', () => {
		const pin = { id: 'gone', scope: remembered };
		expect(selectedShot([a, b], 'gone', { pin, scope: remembered, lookup })).toBe(a);
	});

	it('the scope key ignores surrounding whitespace in the search', () => {
		const x = filterScopeKey({ q: ' mia ', profile: 'p', bean: 'b', method: 'm', tags: ['t'], range: 'all' });
		const y = filterScopeKey({ q: 'mia', profile: 'p', bean: 'b', method: 'm', tags: ['t'], range: 'all' });
		expect(x).toBe(y);
	});
});

describe('liveShot', () => {
	const live = { id: 'live', deletedAt: null };
	const tombstone = { id: 'gone', deletedAt: 1_790_000_000_000 };
	const scope = filterScopeKey({ q: '', profile: 'all', bean: 'all', method: 'all', tags: [], range: 'all' });
	const lookup = (id: string) => liveShot([live, tombstone].find((s) => s.id === id));

	it('passes a live shot and a legacy one with no stamp', () => {
		expect(liveShot(live)).toBe(live);
		expect(liveShot({ id: 'old' })).toEqual({ id: 'old' });
		expect(liveShot(undefined)).toBeUndefined();
	});

	it('a pin on a deleted shot falls back to the list, not to the tombstone', () => {
		const pin = { id: 'gone', scope };
		expect(selectedShot([live], 'gone', { pin, scope, lookup })).toBe(live);
		expect(selectedShot([], 'gone', { pin, scope, lookup })).toBeUndefined();
	});
});
