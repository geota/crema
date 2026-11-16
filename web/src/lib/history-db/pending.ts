/**
 * `#lib/history-db/pending.js` — stage 2's pending journal (#310 SPEC §8, M5):
 * a new shot's row, kept in `localStorage` while the owner can't take it, or
 * until its write commits.
 *
 * - **One key per entry**, `crema.history.sqlite.pending.<shot id>`, written
 *   whole by the tab that captured the shot, so two tabs never rewrite one key.
 *   P3's single `crema.history.pending` key stays P3's.
 * - **An entry names its write** (REVIEW-456 F2): a capture journals its row
 *   with the tab and write seq its write was given. The owner's drain then
 *   tells a capture whose write already landed, which it never inserts again,
 *   even if the shot was deleted or is held by an export since, from one that
 *   never did. An entry journaled without a write (while read-only, or after
 *   its write was given up) has no seq, and is inserted only if its id is absent.
 * - **The owner tab's main thread drains it** (a Worker has no
 *   `localStorage`): when its Worker gets the lock, when a client says hello,
 *   on a `storage` event for the prefix, and before `ready`.
 * - **An entry goes only after its own commit**, and only if it still holds
 *   what the drain read: a newer write of the same key waits for the next one.
 * - An entry that isn't JSON stays, and each drain reports it (N5).
 */
import { PENDING_KEY_PREFIX } from './names.js';
import type { Drained, JournalEntry } from './protocol.js';

export interface JournalStorage {
	getItem(key: string): string | null;
	setItem(key: string, value: string): void;
	removeItem(key: string): void;
	readonly length: number;
	key(index: number): string | null;
}

/** The row's key. */
export const pendingKey = (id: string): string => PENDING_KEY_PREFIX + id;

const idOf = (rowJson: string): string => String(JSON.parse(rowJson).id);

/** The text an entry is kept as: `{v: 1, row, tab?, wseq?}`. */
export function entryText(entry: JournalEntry): string {
	return JSON.stringify({ v: 1, ...entry });
}

/**
 * An entry's text, read back: this build's `{v: 1, row, …}`, or a bare row
 * (W1's format). Null when it isn't one.
 */
export function parseEntry(text: string): JournalEntry | null {
	try {
		const v = JSON.parse(text);
		if (v && typeof v === 'object' && v.v === 1 && typeof v.row === 'string') {
			const e: JournalEntry = { row: v.row };
			if (typeof v.tab === 'string' && Number.isInteger(v.wseq) && v.wseq > 0) {
				e.tab = v.tab;
				e.wseq = v.wseq;
			}
			idOf(e.row);
			return e;
		}
		if (v && typeof v === 'object' && 'id' in v) return { row: text };
	} catch {
		// Not JSON: reported by the drain.
	}
	return null;
}

/**
 * Keeps `rowJson` (a shot row with an `id`) until an owner takes it, with the
 * write it was given, if any. Throws when storage refuses it. Returns the key
 * and the text it holds, for {@link removeIfUnchanged}.
 */
export function appendPending(
	storage: JournalStorage,
	rowJson: string,
	write?: { tab: string; wseq: number }
): { id: string; key: string; text: string } {
	const id = idOf(rowJson);
	const text = entryText(write ? { row: rowJson, ...write } : { row: rowJson });
	storage.setItem(pendingKey(id), text);
	return { id, key: pendingKey(id), text };
}

/** Every entry now: its key and the text it holds. */
export function pendingEntries(storage: JournalStorage): { key: string; value: string }[] {
	const out: { key: string; value: string }[] = [];
	for (let i = 0; i < storage.length; i++) {
		const key = storage.key(i);
		if (!key?.startsWith(PENDING_KEY_PREFIX)) continue;
		const value = storage.getItem(key);
		if (value !== null) out.push({ key, value });
	}
	return out;
}

/** Replaces `key`'s text with `next` only if it still holds `prev`. */
export function replaceIfUnchanged(storage: JournalStorage, key: string, prev: string, next: string): boolean {
	if (storage.getItem(key) !== prev) return false;
	storage.setItem(key, next);
	return true;
}

/** Removes `key` only if it still holds `value`. */
export function removeIfUnchanged(storage: JournalStorage, key: string, value: string): boolean {
	if (storage.getItem(key) !== value) return false;
	storage.removeItem(key);
	return true;
}

export const NOTHING_DRAINED: Drained = { inserted: [], present: [], applied: [], waiting: [], refused: [] };

/**
 * One drain: send every readable entry to `insert` (the owner's drain), then
 * remove each entry it took (inserted, already there, or already applied)
 * that still holds what was read. An entry the owner kept (`waiting`,
 * `refused`) or gave no answer for stays. `unreadable` hears of each entry
 * that isn't JSON; it stays too.
 */
export async function drainPending(
	storage: JournalStorage,
	insert: (entries: JournalEntry[]) => Promise<Drained>,
	unreadable: (key: string) => void = () => {}
): Promise<Drained> {
	const read: { key: string; value: string; entry: JournalEntry }[] = [];
	for (const e of pendingEntries(storage)) {
		const entry = parseEntry(e.value);
		if (entry && pendingKey(idOf(entry.row)) === e.key) read.push({ ...e, entry });
		else unreadable(e.key);
	}
	if (read.length === 0) return NOTHING_DRAINED;
	const done = await insert(read.map((r) => r.entry));
	const took = new Set([...done.inserted, ...done.present, ...done.applied]);
	for (const r of read) {
		if (took.has(idOf(r.entry.row))) removeIfUnchanged(storage, r.key, r.value);
	}
	return done;
}
