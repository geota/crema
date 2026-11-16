/**
 * `#lib/history-db/marker.js` — stage 2's marker and done-record (#310 SPEC
 * §11 "Marker", "Done-record", M8, S9).
 *
 * - **The marker**, `crema.history.sqlite` in `localStorage`, says whether
 *   `history.db` is the truth, and which: the database generation (0 for
 *   `history.db`, `n` for `history-<n>.db`) and the pool generation. Before
 *   the flip passes it holds the failure count and the next try (the P3
 *   spec's backoff: the next two boots, then at most daily), the last error,
 *   and whether the index needs a full rebuild before the next try (M4).
 * - **The done-record**, `.crema-history-done.json` at the OPFS root, outside
 *   both pools, is a second record of `done` with the generations. It is
 *   written after `history.db` says `done` and before the marker, so losing
 *   the marker alone can't make a file that was the truth pass for a failed
 *   copy, and a marker that's missing is rebuilt from it.
 *
 * Reading never throws: an unreadable marker is `null`, and an unreadable
 * done-record is `unreadable`, which never allows a flip (it exists only
 * after a file said `done`).
 */
import { DB_FILE, DONE_RECORD_NAME, MARKER_KEY } from './names.js';

/** The backoff after a failed flip: the next two boots try again, then at most daily (SPEC §11). */
export const FLIP_RETRY_BOOTS = 2;
export const FLIP_RETRY_MS = 24 * 60 * 60 * 1000;

export interface DoneMarker {
	state: 'done';
	/** 0: `history.db`; `n`: `history-<n>.db`. */
	generation: number;
	/** The `crema-db` pool's repair generation (0: `.crema-history-db`). */
	poolGeneration: number;
	/** When the flip passed, or the marker was rebuilt. */
	at: number;
	/** Rebuilt from the done-record or a file, not written by the flip itself. */
	rebuilt?: 'doneRecord' | 'file';
}

export interface PendingMarker {
	state: 'pending';
	/** Failed flips so far. */
	failures: number;
	/** Not before this (ms); 0: the next boot. */
	nextTryAt: number;
	lastError: string | null;
	/** A failed verify: the next try rebuilds the index whole first (M4). */
	rebuildIndex: boolean;
	/** Why the last boot didn't flip, for Settings ("another window is open"). */
	waiting?: string | null;
}

export type Marker = DoneMarker | PendingMarker;

/** What a done-record holds. */
export interface DoneRecord {
	generation: number;
	poolGeneration: number;
	at: number;
}

export interface MarkerStorage {
	getItem(key: string): string | null;
	setItem(key: string, value: string): void;
}

/** The file of database generation `g`. */
export function dbFileName(g: number): string {
	return g === 0 ? DB_FILE : `history-${g}.db`;
}

/** The database generation of `name` (`/history.db`, `history-3.db`), or null for any other file. */
export function generationOf(name: string): number | null {
	const n = name.replace(/^\//, '');
	if (n === DB_FILE) return 0;
	const m = /^history-([1-9][0-9]{0,5})\.db$/.exec(n);
	return m ? Number(m[1]) : null;
}

function int(v: unknown): number | null {
	return typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 ? v : null;
}

/** The marker, or null when absent or unreadable. */
export function readMarker(storage: MarkerStorage): Marker | null {
	let raw: string | null;
	try {
		raw = storage.getItem(MARKER_KEY);
	} catch {
		return null;
	}
	if (raw == null) return null;
	let v: Record<string, unknown>;
	try {
		v = JSON.parse(raw);
	} catch {
		return null;
	}
	if (!v || typeof v !== 'object') return null;
	if (v.state === 'done') {
		const generation = int(v.generation);
		const poolGeneration = int(v.poolGeneration);
		if (generation === null || poolGeneration === null) return null;
		return {
			state: 'done',
			generation,
			poolGeneration,
			at: int(v.at) ?? 0,
			...(v.rebuilt === 'doneRecord' || v.rebuilt === 'file' ? { rebuilt: v.rebuilt } : {})
		};
	}
	if (v.state === 'pending') {
		return {
			state: 'pending',
			failures: int(v.failures) ?? 0,
			nextTryAt: int(v.nextTryAt) ?? 0,
			lastError: typeof v.lastError === 'string' ? v.lastError : null,
			rebuildIndex: v.rebuildIndex === true,
			waiting: typeof v.waiting === 'string' ? v.waiting : null
		};
	}
	return null;
}

/**
 * Writes `marker`. A `done` marker is never replaced by a `pending` one: once
 * `history.db` is the truth, nothing here can make it look like a failed copy
 * (M8). Throws when storage refuses.
 */
export function writeMarker(storage: MarkerStorage, marker: Marker): void {
	if (marker.state === 'pending' && readMarker(storage)?.state === 'done') return;
	storage.setItem(MARKER_KEY, JSON.stringify(marker));
}

/** The pending marker after a flip that failed with `error` at `now` (SPEC §11 "Failure"). */
export function afterFailure(prev: Marker | null, error: string, now: number, rebuildIndex: boolean): PendingMarker {
	const failures = (prev?.state === 'pending' ? prev.failures : 0) + 1;
	return {
		state: 'pending',
		failures,
		nextTryAt: failures <= FLIP_RETRY_BOOTS ? 0 : now + FLIP_RETRY_MS,
		lastError: error,
		rebuildIndex: rebuildIndex || (prev?.state === 'pending' && prev.rebuildIndex),
		waiting: null
	};
}

/** Whether a flip may be tried at `now` (the backoff). */
export function mayTry(marker: Marker | null, now: number): boolean {
	if (!marker) return true;
	if (marker.state === 'done') return false;
	return marker.nextTryAt <= now;
}

/** The done-record's text. */
export function doneRecordText(r: DoneRecord): string {
	return JSON.stringify({ v: 1, state: 'done', generation: r.generation, poolGeneration: r.poolGeneration, at: r.at });
}

/** A done-record's text parsed, or null when it isn't one. */
export function parseDoneRecord(text: string): DoneRecord | null {
	let v: Record<string, unknown>;
	try {
		v = JSON.parse(text);
	} catch {
		return null;
	}
	if (!v || typeof v !== 'object' || v.state !== 'done') return null;
	const generation = int(v.generation);
	const poolGeneration = int(v.poolGeneration);
	if (generation === null || poolGeneration === null) return null;
	return { generation, poolGeneration, at: int(v.at) ?? 0 };
}

/**
 * The done-record as the page finds it. `unreadable`: it's there but doesn't
 * parse (a write cut short), so some file said `done`. `readFailed`: the read
 * itself threw (a private mode, a transient OPFS error), which says nothing
 * about the record, as with no OPFS at all (REVIEW-473 B2).
 */
export type DoneRecordRead =
	| { kind: 'absent' }
	| { kind: 'unreadable' }
	| { kind: 'readFailed'; error: string }
	| { kind: 'done'; record: DoneRecord };

/** Where the page reads the done-record: the OPFS root. */
export interface DoneRecordReader {
	read(): Promise<string | null>;
}

/** Reads the done-record through `reader`: a read that throws is `readFailed`, a record that doesn't parse `unreadable`. */
export async function readDoneRecord(reader: DoneRecordReader | null): Promise<DoneRecordRead> {
	if (!reader) return { kind: 'absent' };
	let text: string | null;
	try {
		text = await reader.read();
	} catch (e) {
		return { kind: 'readFailed', error: (e as Error)?.name ?? String(e) };
	}
	if (text === null) return { kind: 'absent' };
	const record = parseDoneRecord(text);
	return record ? { kind: 'done', record } : { kind: 'unreadable' };
}

/** The OPFS root's done-record, read on the page (`getFile`, which every browser with OPFS has). */
export function opfsDoneRecordReader(): DoneRecordReader | null {
	if (typeof navigator === 'undefined' || !navigator.storage?.getDirectory) return null;
	return {
		async read() {
			const root = await navigator.storage.getDirectory();
			let handle: FileSystemFileHandle;
			try {
				handle = await root.getFileHandle(DONE_RECORD_NAME);
			} catch (e) {
				if ((e as DOMException)?.name === 'NotFoundError') return null;
				throw e;
			}
			return (await handle.getFile()).text();
		}
	};
}
