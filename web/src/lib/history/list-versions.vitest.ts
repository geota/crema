/**
 * #310 P5w: which version a change to a row moves. A field this module
 * doesn't name moves `list`, so an unknown change reruns everything rather
 * than nothing.
 */
import { describe, expect, it } from 'vitest';
import { listChange, rowChange } from './list-versions.svelte';
import type { ShotRow } from './model';

const base = {
	id: 'shot:1',
	completedAt: 1,
	profileName: 'A',
	metadata: { rating: 3, notes: 'n', nextPlan: null, barista: 'Sam', grinderSetting: '2' },
	record: { duration: 30_000 },
	summary: { v: 1, sampleCount: 10, brewSampleCount: 0, peakWeight: 1, finalWeight: 1, peakPressure: 9, peakTemp: 93, spark: 'x' },
	tags: ['a']
} as unknown as ShotRow;
const meta = (m: Record<string, unknown>) => ({ ...base, metadata: { ...base.metadata, ...m } }) as ShotRow;
const top = (t: Record<string, unknown>) => ({ ...base, ...t }) as ShotRow;

describe('rowChange', () => {
	it.each([
		['rating', meta({ rating: 5 }), { list: false, rating: true, text: false }],
		['notes', meta({ notes: 'x' }), { list: false, rating: false, text: true }],
		['plan', meta({ nextPlan: 'finer' }), { list: false, rating: false, text: true }],
		['grinder model', top({ grinderModel: 'Niche' }), { list: false, rating: false, text: true }],
		['remote stamp', top({ visualizerUpdatedAt: 9 }), { list: false, rating: false, text: false }],
		['privacy', top({ privacy: 'private' }), { list: false, rating: false, text: false }],
		['grind setting', meta({ grinderSetting: '3' }), { list: false, rating: false, text: false }],
		['bag debit', top({ bagDebit: 18 }), { list: false, rating: false, text: false }],
		['date', top({ completedAt: 2 }), { list: true, rating: false, text: false }],
		['profile', top({ profileName: 'B' }), { list: true, rating: false, text: false }],
		['barista', meta({ barista: 'Kim' }), { list: true, rating: false, text: false }],
		['pinned', top({ pinned: true }), { list: true, rating: false, text: false }],
		['deleted', top({ deletedAt: 5 }), { list: true, rating: false, text: false }],
		['visualizer id', top({ visualizerId: 'v' }), { list: true, rating: false, text: false }],
		['tags', top({ tags: ['b'] }), { list: true, rating: false, text: false }],
		['dose (a stat)', meta({ dose: 20 }), { list: true, rating: false, text: false }],
		['a field nobody named', top({ somethingNew: 1 }), { list: true, rating: false, text: false }],
		['duration', top({ record: { duration: 31_000 } }), { list: true, rating: false, text: false }],
		['summary yield', top({ summary: { ...base.summary, finalWeight: 2 } }), { list: true, rating: false, text: false }]
	])('%s', (_name, next, want) => {
		expect(rowChange(base, next)).toEqual(want);
	});

	it('a rebuilt but equal record or summary moves nothing', () => {
		expect(rowChange(base, top({ record: { duration: 30_000 }, summary: { ...base.summary } }))).toEqual({
			list: false,
			rating: false,
			text: false
		});
	});
});

describe('listChange', () => {
	const b = { ...base, id: 'shot:2' } as ShotRow;
	it('a new set or order moves everything', () => {
		expect(listChange([base, b], [b, base]).list).toBe(true);
		expect(listChange([base], [base, b]).list).toBe(true);
	});
	it('the same list moves nothing; one edited row moves its kind', () => {
		const rows = [base, b];
		expect(listChange(rows, rows)).toEqual({ list: false, rating: false, text: false });
		expect(listChange(rows, [meta({ rating: 1 }), b])).toEqual({ list: false, rating: true, text: false });
	});
});
