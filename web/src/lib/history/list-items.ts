/**
 * `#lib/history/list-items.js` — what the windowed History list draws (#310
 * P5w): the rows, and at phone width under the date sort the day headings
 * between them, each with one height per kind and layout, so the list can
 * place any of them by arithmetic. The heights are measured from hidden
 * sample items (#423), since text scaling grows the rows; {@link ROW_HEIGHTS}
 * stands in until the first measurement.
 */

import type { ShotRow } from './model';
import { groupShots } from './phone-list';
import { SPARK_POINTS } from './spark';

export type ListItem =
	| { readonly kind: 'head'; readonly key: string; readonly label: string }
	| {
			readonly kind: 'row';
			/** The shot id: also the row's `data-scroll-key`. */
			readonly key: string;
			/** The row as the list was built; draw the current one by id. */
			readonly row: ShotRow;
			/** Whether the row has its third line, a note or a plan. */
			readonly note: boolean;
			/** 1-based place among the rows, headings not counted. */
			readonly pos: number;
	  }
	/**
	 * A row whose page hasn't loaded (#310 W2, SPEC §7 rule 2): drawn as a
	 * placeholder of the row's height, keyed by its place.
	 */
	| {
			readonly kind: 'skeleton';
			readonly key: string;
			/** The row had its note line when it was last loaded: keep its height. */
			readonly note: boolean;
			readonly pos: number;
	  };

/** An item's height in px for each kind and layout, the gap below it included. */
export interface RowHeights {
	wide: { row: number; note: number };
	phone: { row: number; note: number; head: number };
}

/**
 * The heights at the default text size, for before the samples are measured
 * (and where nothing can be measured).
 */
export const ROW_HEIGHTS: Readonly<{ wide: Readonly<RowHeights['wide']>; phone: Readonly<RowHeights['phone']> }> = {
	wide: { row: 60, note: 78 },
	phone: { row: 78, note: 98, head: 34 }
};

/** A fresh, writable copy of {@link ROW_HEIGHTS}, for a page to measure into. */
export function fallbackRowHeights(): RowHeights {
	return { wide: { ...ROW_HEIGHTS.wide }, phone: { ...ROW_HEIGHTS.phone } };
}

/** The gap below each item, inside its height. */
export const ROW_GAP = 2;

/**
 * Below this list width at the default text size, the wide rows go on two
 * lines (#423): one line needs about 400 px besides the name, so text scaled
 * up, or a narrow list, would squeeze the name to nothing. The 480 px list
 * at 1280 px wide (462 px inside) stays on one line at the default size.
 */
export const STACK_BELOW = 440;

/** The one-line note a row shows: the plan when set, else the tasting notes. */
export function rowNote(shot: ShotRow): string {
	return (shot.metadata.nextPlan?.trim() || shot.metadata.notes?.trim() || '').replace(/\s+/g, ' ');
}

/**
 * The list's items for `rows`, in order. `headings` puts the day headings in
 * (phone, date sort). `current` gives each row as it is now, for its note.
 */
export function listItems(
	rows: readonly ShotRow[],
	opts: { headings: boolean; now: Date; current: (row: ShotRow) => ShotRow }
): ListItem[] {
	const out: ListItem[] = [];
	let pos = 0;
	const push = (row: ShotRow): void => {
		pos += 1;
		out.push({ kind: 'row', key: row.id, row, note: rowNote(opts.current(row)) !== '', pos });
	};
	if (!opts.headings) {
		for (const row of rows) push(row);
		return out;
	}
	for (const group of groupShots(rows, true, opts.now)) {
		if (group.label) out.push({ kind: 'head', key: `head:${group.label}`, label: group.label });
		for (const row of group.shots) push(row);
	}
	return out;
}

/** An item's height in the layout. */
export function rowHeight(item: ListItem, phone: boolean, heights: RowHeights = ROW_HEIGHTS): number {
	if (item.kind === 'head') return heights.phone.head;
	const h = phone ? heights.phone : heights.wide;
	return item.note ? h.note : h.row;
}

/** Record a measured sample: `px` becomes the height of every item of `sample`'s kind in the layout. */
export function setRowHeight(heights: RowHeights, phone: boolean, sample: ListItem, px: number): void {
	if (sample.kind === 'head') heights.phone.head = px;
	else if (phone) heights.phone[sample.note ? 'note' : 'row'] = px;
	else heights.wide[sample.note ? 'note' : 'row'] = px;
}

/** A spark the sample rows draw: a flat line, since only the chart's box matters. */
const SAMPLE_SPARK = btoa(String.fromCharCode(1, 1, 0, ...new Array<number>(3 * SPARK_POINTS).fill(127)));

/** A row with every line a row can show, the chart included. */
function sampleRow(id: string, note: string | null): ShotRow {
	return {
		formatVersion: 3,
		id,
		completedAt: 0,
		profileName: 'Sample profile',
		stopReason: null,
		metadata: { dose: 18, yieldOut: 36, grinderSetting: '2.5', rating: 4, notes: note },
		bean: { name: 'Sample bean', roasterName: 'Sample roaster', grinderSetting: '2.5' },
		record: { duration: 30_000 },
		summary: { v: 1, sampleCount: 1, brewSampleCount: 0, peakPressure: 9, peakTemp: 92, spark: SAMPLE_SPARK }
	};
}

/**
 * One item of each kind the layout draws, to render hidden and measure:
 * a row, a row with its note, and at phone width a day heading. The keys
 * name the layout, so a layout change draws (and measures) them afresh.
 */
export function rowSamples(phone: boolean): ListItem[] {
	const layout = phone ? 'phone' : 'wide';
	const row = (note: boolean): ListItem => {
		const key = `sample:${layout}:${note ? 'note' : 'row'}`;
		return { kind: 'row', key, row: sampleRow(key, note ? 'A sample note' : null), note, pos: 0 };
	};
	const out = [row(false), row(true)];
	if (phone) out.push({ kind: 'head', key: 'sample:phone:head', label: 'Today' });
	return out;
}
