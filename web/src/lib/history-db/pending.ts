/**
 * `#lib/history-db/pending.js` — stage 2's pending journal (#310 SPEC §8, M5):
 * a new shot's row, kept in `localStorage` while the owner can't take it.
 *
 * - **One key per entry**, `crema.history.sqlite.pending.<shot id>`, written
 *   whole by the tab that captured the shot, so two tabs never rewrite one key.
 *   P3's single `crema.history.pending` key stays P3's.
 * - **The owner tab's main thread drains it** (a Worker has no
 *   `localStorage`): when its Worker gets the lock, when a client says hello,
 *   on a `storage` event for the prefix, and before `ready`.
 * - **A drain inserts a row only if its id is absent** (the owner's `drain`
 *   call): a present id means a resend already landed, and the row may have
 *   been edited since.
 * - **An entry goes only after its own commit**, and only if it still holds
 *   what the drain read: a newer write of the same key waits for the next one.
 */
import { PENDING_KEY_PREFIX } from './names.js';
import type { Drained } from './protocol.js';

export interface JournalStorage {
	getItem(key: string): string | null;
	setItem(key: string, value: string): void;
	removeItem(key: string): void;
	readonly length: number;
	key(index: number): string | null;
}

/** The row's key. */
export const pendingKey = (id: string): string => PENDING_KEY_PREFIX + id;

/** Keeps `rowJson` (a shot row with an `id`) until an owner takes it. Throws when storage refuses it. */
export function appendPending(storage: JournalStorage, rowJson: string): string {
	const id = String(JSON.parse(rowJson).id);
	storage.setItem(pendingKey(id), rowJson);
	return id;
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

/** Removes `key` only if it still holds `value`. */
export function removeIfUnchanged(storage: JournalStorage, key: string, value: string): boolean {
	if (storage.getItem(key) !== value) return false;
	storage.removeItem(key);
	return true;
}

/**
 * One drain: send every entry to `insert` (the owner's insert-if-absent), then
 * remove each entry it took, inserted or already there, that still holds what
 * was read. An entry `insert` didn't answer for stays.
 */
export async function drainPending(
	storage: JournalStorage,
	insert: (rows: string[]) => Promise<Drained>
): Promise<Drained> {
	const entries = pendingEntries(storage);
	if (entries.length === 0) return { inserted: [], present: [] };
	const done = await insert(entries.map((e) => e.value));
	const took = new Set([...done.inserted, ...done.present]);
	for (const e of entries) {
		const id = e.key.slice(PENDING_KEY_PREFIX.length);
		if (took.has(id)) removeIfUnchanged(storage, e.key, e.value);
	}
	return done;
}
