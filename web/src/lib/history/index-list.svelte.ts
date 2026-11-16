/**
 * `#lib/history/index-list.svelte.js` — what the History list draws when it
 * reads the stage-1 index (#310 W2, SPEC §7): the window's rows by position,
 * skeletons where a page hasn't loaded, and at phone width under the date sort
 * the day headings, placed by arithmetic from `group_counts`, so nothing scans
 * the rows.
 *
 * Each place remembers whether its row had a note line when last loaded, until
 * the list moves (a relist or a new count), so a page dropped from the window
 * and drawn as skeletons again keeps its height.
 */
import { IndexWindow, type HistoryQuery, type IndexReads, type WindowAnswer } from '#lib/history-db/window.svelte.js';
import type { VirtualItems } from '#lib/components/history/virtual-window.js';
import { shotOfRow, type RowRecord } from './history-db';
import type { ShotRow } from './model';
import { rowNote, type ListItem } from './list-items';
import { DAY_LABELS } from './index-query';

/** A stored row as the list draws it. */
export function decodeRow(rowJson: string): ShotRow {
	const rec = JSON.parse(rowJson) as RowRecord;
	const { shot, summary } = shotOfRow(rec);
	const { record, ...rest } = shot;
	return { ...rest, record: { duration: record.duration }, summary } as ShotRow;
}

/**
 * The rows and their day headings, as list items (Android's `DayLayout`).
 * `counts` are rows per group, newest group first; the groups go in list
 * order, newest first when `descending`, each non-empty one under a heading.
 */
export class DayLayout {
	/** Each non-empty group in list order: its label, its first row, its rows. */
	readonly #groups: { label: number; start: number; n: number }[];
	readonly size: number;

	constructor(counts: readonly number[] | null, rows: number, descending: boolean) {
		const groups: { label: number; start: number; n: number }[] = [];
		if (counts) {
			const order = counts.map((_, i) => i);
			if (!descending) order.reverse();
			let row = 0;
			for (const i of order) {
				const n = counts[i];
				if (n === 0) continue;
				groups.push({ label: i, start: row, n });
				row += n;
			}
			// Counted under another state of the list: no headings until they agree.
			if (row !== rows) groups.length = 0;
		}
		this.#groups = groups;
		this.size = rows + groups.length;
	}

	/** What item `item` is: a heading (its label) or a row (its position). */
	itemAt(item: number): { head: string } | { row: number } {
		let at = 0;
		for (const g of this.#groups) {
			if (item === at) return { head: DAY_LABELS[g.label] };
			if (item <= at + g.n) return { row: g.start + (item - at - 1) };
			at += g.n + 1;
		}
		return { row: item - this.#groups.length };
	}

	/** The item that shows row `index`. */
	itemOfRow(index: number): number {
		let heads = 0;
		for (const g of this.#groups) if (g.start <= index) heads++;
		return index + heads;
	}
}

export interface IndexListOptions {
	query: HistoryQuery;
	reads: IndexReads;
	/** Day headings: phone width, under the date sort. */
	headings: boolean;
	/** The day groups' edges, newest first, when `headings`. */
	edges: readonly number[] | null;
	/** The current copy of a row, for its note: an edit may be newer than the page. */
	current(row: ShotRow): ShotRow;
	onAnswer?(a: WindowAnswer<ShotRow>): void;
}

/** The History list over the index. */
export class IndexList {
	readonly window: IndexWindow<ShotRow>;
	readonly #o: IndexListOptions;
	/** Note lines by place, for the skeletons; cleared when the list moves. */
	#notes = new Map<number, boolean>();
	#notesFor = '';
	/** Each id's `index_of`, as it answers: `undefined` while asked. */
	readonly #found = new Map<string, number | null | undefined>();
	#foundFor = -1;
	/** Moves when an `index_of` answers, so whatever asked looks again. */
	#foundTick = $state(0);

	constructor(o: IndexListOptions) {
		this.#o = o;
		this.window = new IndexWindow<ShotRow>({
			query: o.query,
			reads: o.reads,
			decode: decodeRow,
			idOf: (r) => r.id,
			edges: o.headings ? o.edges : null,
			onAnswer: o.onAnswer
		});
	}

	destroy(): void {
		this.window.destroy();
	}

	/** Rows the query lists; null until it has answered. */
	get count(): number | null {
		return this.window.count;
	}

	/** Where the headings go. */
	readonly layout = $derived.by(
		() => new DayLayout(this.#o.headings ? this.window.groups : null, this.window.count ?? 0, this.#o.query.sort.dir === 'desc')
	);

	/** The list's items: rows, skeletons and headings. */
	readonly items = $derived.by((): VirtualItems<ListItem> => {
		void this.window.version;
		const layout = this.layout;
		const key = `${this.window.count}:${this.window.listGen}`;
		if (key !== this.#notesFor) {
			this.#notes = new Map();
			this.#notesFor = key;
		}
		const notes = this.#notes;
		const current = this.#o.current;
		return {
			count: layout.size,
			at: (i) => {
				const at = layout.itemAt(i);
				if ('head' in at) return { kind: 'head', key: `head:${at.head}`, label: at.head };
				const row = this.window.rowAt(at.row);
				if (!row) return { kind: 'skeleton', key: `skeleton:${at.row}`, note: notes.get(at.row) ?? false, pos: at.row + 1 };
				const note = rowNote(current(row)) !== '';
				notes.set(at.row, note);
				return { kind: 'row', key: row.id, row, note, pos: at.row + 1 };
			}
		};
	});

	/** Items `[start, end)` are on screen: load their rows' pages. */
	visibleItems(start: number, end: number): void {
		const layout = this.layout;
		if (layout.size === 0) return;
		/** The row item `i` shows, or the nearest one to a heading. */
		const rowNear = (i: number): number => {
			const at = layout.itemAt(Math.min(Math.max(0, i), layout.size - 1));
			if ('row' in at) return at.row;
			const next = i + 1 < layout.size ? layout.itemAt(i + 1) : null;
			return next && 'row' in next ? next.row : Math.max(0, (this.window.count ?? 1) - 1);
		};
		const lo = rowNear(start);
		this.window.visible(lo, Math.max(lo, rowNear(end - 1)));
	}

	/**
	 * Where the item for shot `id` is: a loaded row's at once, else through
	 * `index_of` (`'pending'` until it answers; `null` when the query doesn't
	 * list it).
	 */
	locate(id: string): number | null | 'pending' {
		if (id.startsWith('head:') || id.startsWith('skeleton:')) return null;
		const gen = this.window.listGen;
		void this.#foundTick;
		if (gen !== this.#foundFor) {
			this.#found.clear();
			this.#foundFor = gen;
		}
		const loaded = this.window.loadedIndexOf(id);
		if (loaded !== null) return this.layout.itemOfRow(loaded);
		if (!this.#found.has(id)) {
			this.#found.set(id, undefined);
			void this.window.indexOf(id).then((i) => {
				if (this.#foundFor !== gen) return;
				this.#found.set(id, i);
				this.#foundTick++;
			});
		}
		const i = this.#found.get(id);
		if (i === undefined) return 'pending';
		return i === null ? null : this.layout.itemOfRow(i);
	}

	/** Shot `id`'s place in the list (rows only), or null when it isn't listed; {@link locate} knows it after. */
	async indexOf(id: string): Promise<number | null> {
		const gen = this.window.listGen;
		const i = await this.window.indexOf(id);
		if (this.window.listGen === gen) {
			if (gen !== this.#foundFor) {
				this.#found.clear();
				this.#foundFor = gen;
			}
			this.#found.set(id, i);
			this.#foundTick++;
		}
		return i;
	}

	/** The first row, if its page has loaded. */
	get first(): ShotRow | null {
		return this.window.rowAt(0);
	}
}
