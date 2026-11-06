/**
 * `#lib/bean/picker.vitest.js` — the web side of the core bean picker
 * (geota/crema#301). The rules are covered by the Rust tests in
 * `de1_domain::bean_picker`; what needs proving here is that they reach the
 * page through wasm, and that a broken call leaves the library on screen.
 * Run: `pnpm test:vitest`.
 */

import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { blankBean, blankRoaster, type Bean } from './model.ts';
import { beanPickerRows, rankBeansForPicker } from './picker.ts';

vi.mock('#lib/wasm/de1_wasm.js', async (original) => {
	const real = await original<typeof import('#lib/wasm/de1_wasm.js')>();
	return { ...real, beanPicker: vi.fn(real.beanPicker) };
});

beforeAll(async () => {
	await initTestWasm();
});

afterEach(() => {
	vi.restoreAllMocks();
});

function bag(id: string, createdAt: number, patch: Partial<Bean> = {}): Bean {
	return { ...blankBean(), id, name: id, createdAt, updatedAt: createdAt, ...patch };
}

const off = { currentId: null, includeArchived: false, search: '' };
const ids = (rows: { beans: Bean[] }) => rows.beans.map((b) => b.id);

describe('beanPickerRows', () => {
	it('leads with the current bag, then favourites, then the oldest', () => {
		const beans = [bag('new', 3), bag('fav', 4, { favourite: true }), bag('old', 1), bag('cur', 2)];
		expect(ids(beanPickerRows(beans, [], { ...off, currentId: 'cur' }))).toEqual([
			'cur',
			'fav',
			'old',
			'new'
		]);
	});

	it('ignores how recently a bag was updated', () => {
		const beans = [bag('old', 1, { updatedAt: 99 }), bag('new', 2)];
		expect(ids(beanPickerRows(beans, [], off))).toEqual(['old', 'new']);
	});

	it('hides archived bags until the toggle', () => {
		const beans = [bag('live', 1), bag('done', 2, { archivedAt: 5 })];
		const hidden = beanPickerRows(beans, [], off);
		expect(ids(hidden)).toEqual(['live']);
		expect(hidden.hasArchived).toBe(true);
		expect(ids(beanPickerRows(beans, [], { ...off, includeArchived: true }))).toEqual([
			'live',
			'done'
		]);
	});

	it('keeps an archived bag the shot is bound to', () => {
		const beans = [bag('live', 1), bag('done', 2, { archivedAt: 5 })];
		const rows = beanPickerRows(beans, [], { ...off, currentId: 'done' });
		expect(ids(rows)).toEqual(['done', 'live']);
		expect(rows.hasArchived).toBe(false);
	});

	it('never lists a deleted bag', () => {
		const beans = [bag('live', 1), bag('gone', 2, { deletedAt: 5 })];
		expect(ids(beanPickerRows(beans, [], { ...off, includeArchived: true }))).toEqual(['live']);
	});

	it('searches through the core: past a typo, and fields beyond name and roaster', () => {
		const beans = [
			bag('Kenya', 1),
			bag('House', 2, { origin: { ...blankBean().origin, country: 'Ethiopia' } })
		];
		expect(ids(beanPickerRows(beans, [], { ...off, search: 'kenia' }))).toEqual(['Kenya']);
		expect(ids(beanPickerRows(beans, [], { ...off, search: 'ethiopia' }))).toEqual(['House']);
	});

	it('finds a bag by its roaster', () => {
		const roaster = blankRoaster('Wendelboe', 'r:1');
		const beans = [bag('Kenya', 1, { roasterId: 'r:1' }), bag('Peru', 2)];
		expect(ids(beanPickerRows(beans, [roaster], { ...off, search: 'wendelboe' }))).toEqual([
			'Kenya'
		]);
	});

	it('shows the library, without archived bags, when the core fails', async () => {
		const wasm = await import('#lib/wasm/de1_wasm.js');
		vi.mocked(wasm.beanPicker).mockImplementationOnce(() => {
			throw new Error('bad');
		});
		const log = vi.spyOn(console, 'error').mockImplementation(() => {});
		const beans = [bag('live', 1), bag('done', 2, { archivedAt: 5 })];
		expect(ids(beanPickerRows(beans, [], off))).toEqual(['live']);
		expect(log).toHaveBeenCalled();
	});
});

describe('rankBeansForPicker', () => {
	it('orders the list it was given, whatever order the store holds', () => {
		const beans = [bag('b', 2), bag('a', 1, { favourite: true }), bag('c', 3)];
		const ranked = rankBeansForPicker(beans, 'c');
		expect(ranked.map((b) => b.id)).toEqual(['c', 'a', 'b']);
		expect(rankBeansForPicker([...beans].reverse(), 'c')).toEqual(ranked);
	});
});
