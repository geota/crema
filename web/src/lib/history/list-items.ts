/**
 * `#lib/history/list-items.js` — what the windowed History list draws (#310
 * P5w): the rows, and at phone width under the date sort the day headings
 * between them, each with a height fixed by its kind and the layout, so the
 * list can place any of them by arithmetic.
 */

import type { ShotRow } from './model';
import { groupShots } from './phone-list';

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
	  };

/** Heights in px, the 2 px gap below each item included. Kept in step with the rows' CSS. */
export const ROW_HEIGHTS = {
	wide: { row: 60, note: 78 },
	phone: { row: 78, note: 98, head: 34 }
} as const;

/** The gap below each item, inside its height. */
export const ROW_GAP = 2;

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
export function rowHeight(item: ListItem, phone: boolean): number {
	if (item.kind === 'head') return ROW_HEIGHTS.phone.head;
	const h = phone ? ROW_HEIGHTS.phone : ROW_HEIGHTS.wide;
	return item.note ? h.note : h.row;
}
