/** #310 P5w: what the windowed History list draws, and each item's height. */
import { describe, expect, it } from 'vitest';
import { fallbackRowHeights, listItems, rowHeight, ROW_HEIGHTS, rowSamples, setRowHeight } from './list-items';
import type { ShotRow } from './model';

const now = new Date(2026, 10, 14, 12, 0);
const row = (id: string, at: number, notes = ''): ShotRow =>
	({ id, completedAt: at, metadata: { notes, nextPlan: null } }) as unknown as ShotRow;
const rows = [
	row('a', now.getTime() - 3_600_000, 'bright'),
	row('b', now.getTime() - 26 * 3_600_000),
	row('c', now.getTime() - 30 * 86_400_000)
];

describe('listItems', () => {
	it('wide: rows only, numbered', () => {
		const items = listItems(rows, { headings: false, now, current: (r) => r });
		expect(items.map((i) => (i.kind === 'row' ? `${i.key}:${i.pos}:${i.note}` : i.key))).toEqual([
			'a:1:true',
			'b:2:false',
			'c:3:false'
		]);
	});

	it('phone, date sort: day headings between, not counted', () => {
		const items = listItems(rows, { headings: true, now, current: (r) => r });
		expect(items.map((i) => (i.kind === 'head' ? i.label : `${i.key}:${i.pos}`))).toEqual([
			'Today',
			'a:1',
			'Yesterday',
			'b:2',
			'Earlier',
			'c:3'
		]);
	});

	it('takes the note from the current row', () => {
		const items = listItems(rows, { headings: false, now, current: (r) => (r.id === 'b' ? row('b', 0, 'now noted') : r) });
		expect(items[1].kind === 'row' && items[1].note).toBe(true);
	});

	it('heights by kind and layout', () => {
		const [head, a] = listItems(rows, { headings: true, now, current: (r) => r });
		const b = listItems(rows, { headings: false, now, current: (r) => r })[1];
		expect(rowHeight(head, true)).toBe(ROW_HEIGHTS.phone.head);
		expect(rowHeight(a, true)).toBe(ROW_HEIGHTS.phone.note);
		expect(rowHeight(a, false)).toBe(ROW_HEIGHTS.wide.note);
		expect(rowHeight(b, false)).toBe(ROW_HEIGHTS.wide.row);
		expect(rowHeight(b, true)).toBe(ROW_HEIGHTS.phone.row);
	});
});

describe('measured heights (#423)', () => {
	it('starts from the default-size heights, in a copy of its own', () => {
		const h = fallbackRowHeights();
		expect(h).toEqual(ROW_HEIGHTS);
		h.wide.row = 1;
		expect(ROW_HEIGHTS.wide.row).toBe(60);
	});

	it('one sample of each kind the layout draws, keyed by layout', () => {
		expect(rowSamples(false).map((i) => i.key)).toEqual(['sample:wide:row', 'sample:wide:note']);
		expect(rowSamples(true).map((i) => i.key)).toEqual(['sample:phone:row', 'sample:phone:note', 'sample:phone:head']);
		const [plain, noted] = rowSamples(false);
		expect(plain.kind === 'row' && plain.note).toBe(false);
		expect(noted.kind === 'row' && noted.note).toBe(true);
	});

	it('a measured sample sets the height of its kind in its layout, and only that', () => {
		const h = fallbackRowHeights();
		const [wideRow, wideNote] = rowSamples(false);
		const [, phoneNote, phoneHead] = rowSamples(true);
		setRowHeight(h, false, wideRow, 117);
		setRowHeight(h, false, wideNote, 128);
		setRowHeight(h, true, phoneNote, 168);
		setRowHeight(h, true, phoneHead, 49);
		expect(h).toEqual({ wide: { row: 117, note: 128 }, phone: { row: 78, note: 168, head: 49 } });

		const [head, a] = listItems(rows, { headings: true, now, current: (r) => r });
		const b = listItems(rows, { headings: false, now, current: (r) => r })[1];
		expect(rowHeight(head, true, h)).toBe(49);
		expect(rowHeight(a, true, h)).toBe(168);
		expect(rowHeight(a, false, h)).toBe(128);
		expect(rowHeight(b, false, h)).toBe(117);
		expect(rowHeight(b, true, h)).toBe(78);
	});
});
