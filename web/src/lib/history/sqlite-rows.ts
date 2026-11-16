/**
 * `#lib/history/sqlite-rows.js` — the history store's rows in `history.db`
 * after the flip (#310 SPEC §2, §9, §11 stage 2, PLAN W5): what the store
 * asks of `crema-history` (`HistoryDb`), answered by a stage-2
 * `HistoryDbClient` for the rows and IndexedDB `details` for the heavy parts.
 *
 * - **Writes go only to `history.db` and `details`** (SPEC §11). Details
 *   first, then the row, as every writer does (SPEC §2). The details writes
 *   never touch `crema-history`'s `rows` or `meta`: those are the old copy,
 *   and stay byte for byte as the flip left them (rule 3).
 * - **A delete** removes the rows through the store's `delete`, then the
 *   details of exactly the ids that have no row now: a row an export walk
 *   holds keeps its details until the hold's release (S6), and an id made
 *   again meanwhile keeps its new ones (REVIEW-468 W5-2).
 * - **`meta.migration`** is the P3 store's own late-row bookkeeping, which
 *   lives in `history.db` after the flip (`p3_migration`, S13), so the pre-P3
 *   late-row step goes on there. No other `meta` key is written.
 * - **The pending journal** (M5) is stage 2's: one `localStorage` key per
 *   shot (`crema.history.sqlite.pending.<id>`), drained by the owner tab. P3's
 *   `crema.history.pending` is read once as late rows, never written.
 */
import type { ShotSummary } from '#lib/core/crema-core.js';
import type { HistoryDbClient } from '#lib/history-db/client.js';
import { appendPending, pendingKey, type JournalStorage } from '#lib/history-db/pending.js';
import type { Applied } from '#lib/history-db/protocol.js';
import type { DetailsStore } from '#lib/history-source/details.js';
import {
	HistoryDbLost,
	META_MIGRATION,
	META_WRITE_SEQ,
	detailsOf,
	rowOf,
	withDetails,
	type DetailsRecord,
	type RowRecord,
	type ShotWrite,
	type WrittenDetails
} from './history-db';
import type { StoredShot } from './model';

/** What the adapter uses of a stage-2 client. */
export type SqliteClient = Pick<HistoryDbClient, 'read' | 'write' | 'status' | 'onStatus' | 'onChange' | 'importLate'>;

/** How long `open` waits for the owner to answer before the store is `unavailable`. */
export const OPEN_WAIT_MS = 15_000;

/** The `history.db` truth file was written by a newer build (`too_new`). */
export class HistoryDbTooNew extends Error {
	constructor() {
		super('Shot history was saved by a newer version of Crema. Update the app to open it.');
		this.name = 'HistoryDbTooNew';
	}
}

/** The reason a stage-2 boot whose truth couldn't be told gives (`StageChoice.unavailable`). */
export const STAGE_UNKNOWN = 'stageUnknown';

/**
 * The client of a stage-2 boot that couldn't tell which file is the truth
 * (REVIEW-473): always `unavailable`, every call refused. The store is
 * `unavailable` at once, new shots go to the pending journal, and the next
 * boot looks again.
 */
export function unavailableClient(why: string): SqliteClient {
	const refuse = () => Promise.reject(new Error(`Shot history isn't available this time (${why}). Reload to try again.`));
	return {
		status: { health: 'unavailable', reason: STAGE_UNKNOWN, message: `Shot history isn't available this time (${why}).`, owner: null, ownerHere: false },
		onStatus: () => () => {},
		onChange: () => () => {},
		read: refuse,
		write: refuse,
		importLate: refuse
	} as SqliteClient;
}

/** Rows per `get`. */
const GET_ROWS = 200;

/**
 * The subset of `HistoryDb` the store uses, over `history.db`. Throws
 * {@link HistoryDbLost} when the file is `lost`.
 */
export class SqliteRows {
	readonly #c: SqliteClient;
	readonly #details: DetailsStore;

	constructor(client: SqliteClient, details: DetailsStore) {
		this.#c = client;
		this.#details = details;
	}

	/** Resolves once the owner serves `history.db`; throws as the store's open would. */
	async open(ms = OPEN_WAIT_MS): Promise<void> {
		const check = (): 'ok' | Error | null => {
			const s = this.#c.status;
			if (s.health === 'ok') return 'ok';
			if (s.health === 'lost') return new HistoryDbLost();
			if (s.health === 'too_new') return new HistoryDbTooNew();
			if (s.health === 'unavailable' && s.reason === STAGE_UNKNOWN) return new Error(s.message ?? 'Shot history is not available.');
			return null;
		};
		const now = check();
		if (now === 'ok') return;
		if (now) throw now;
		await new Promise<void>((resolve, reject) => {
			const t = setTimeout(() => {
				off();
				reject(new Error(this.#c.status.message ?? 'The shot history did not answer.'));
			}, ms);
			const off = this.#c.onStatus(() => {
				const v = check();
				if (v === null) return;
				clearTimeout(t);
				off();
				if (v === 'ok') resolve();
				else reject(v);
			});
		});
	}

	close(): void {
		// The client is the app's; it closes with the tab.
	}

	async #read<T>(name: Parameters<SqliteClient['read']>[0], arg?: unknown): Promise<T> {
		try {
			return await this.#c.read<T>(name, arg);
		} catch (e) {
			if (this.#c.status.health === 'lost') throw new HistoryDbLost();
			throw e;
		}
	}

	async meta<T>(key: string): Promise<T | undefined> {
		if (key === META_WRITE_SEQ) return (await this.writeSeq()) as T;
		if (key !== META_MIGRATION) return undefined;
		const text = await this.#read<string | null>('metaValue', 'p3_migration');
		if (typeof text !== 'string') return undefined;
		try {
			return JSON.parse(text) as T;
		} catch {
			return undefined;
		}
	}

	/** Only the P3 store's own migration record, into `history.db` (S13). */
	async setMeta(key: string, value: unknown): Promise<void> {
		if (key !== META_MIGRATION) throw new Error(`history.db keeps no ${key}`);
		this.#check(await this.#c.write({ kind: 'p3Migration', text: JSON.stringify(value) }));
	}

	async writeSeq(): Promise<number> {
		return Number(await this.#read('writeSeq')) || 0;
	}

	/** Every row, tombstones too; rows an export holds are deleted to the user, so not here. */
	async allRows(): Promise<RowRecord[]> {
		const out: RowRecord[] = [];
		let after = 0;
		for (;;) {
			const page = await this.#read<{ rows: { rid: number; id: string; held: boolean }[] }>('syncRows', { afterRid: after, limit: 1000 });
			if (page.rows.length === 0) break;
			const ids = page.rows.filter((r) => !r.held).map((r) => r.id);
			for (const r of (await this.rowsOf(ids)).values()) out.push(r);
			after = page.rows[page.rows.length - 1].rid;
		}
		return out;
	}

	async rowsOf(ids: readonly string[]): Promise<Map<string, RowRecord>> {
		const out = new Map<string, RowRecord>();
		for (let i = 0; i < ids.length; i += GET_ROWS) {
			const got = await this.#read<{ id: string; rowJson: string }[]>('get', ids.slice(i, i + GET_ROWS));
			for (const g of got) {
				try {
					out.set(g.id, JSON.parse(g.rowJson) as RowRecord);
				} catch {
					// Not a row object: the core stores only JSON objects, so never here.
				}
			}
		}
		return out;
	}

	detailsOf(ids: readonly string[]): Promise<Map<string, DetailsRecord>> {
		return this.#details.get(ids);
	}

	/** Details first, then the rows, in this tab's write order (M11). */
	async putShots(shots: readonly ShotWrite[], summarize: (shot: StoredShot) => ShotSummary): Promise<{ seq: number; written: WrittenDetails }> {
		const written: WrittenDetails = new Map();
		const keep = shots.filter((w) => w.details && !w.fill).map((w) => detailsOf(w.shot));
		const fill = shots.filter((w) => w.details && w.fill).map((w) => detailsOf(w.shot));
		const kept = new Map<string, DetailsRecord>();
		for (const [part, how] of [
			[keep, 'keep'],
			[fill, 'fill']
		] as const) {
			if (part.length === 0) continue;
			for (const [id, d] of await this.#details.put(part, how)) kept.set(id, d);
		}
		const rows: { rowJson: string }[] = [];
		for (const w of shots) {
			let summary: ShotSummary;
			if (w.details) {
				const d = kept.get(w.shot.id);
				if (!d) throw new Error(`The details of ${w.shot.id} weren't written`);
				summary = summarize(withDetails(w.shot, d));
				written.set(w.shot.id, { details: d, summary });
			} else {
				if (!w.summary) throw new Error(`No summary for a row-only write of ${w.shot.id}`);
				summary = w.summary;
			}
			rows.push({ rowJson: JSON.stringify(rowOf(w.shot, summary)) });
		}
		if (rows.length === 0) return { seq: await this.writeSeq(), written };
		const a = await this.#c.write({ kind: 'upsert', rows });
		return { seq: this.#check(a) ?? (await this.writeSeq()), written };
	}

	/** The rows, then the details of the ids no row has now (S6, REVIEW-468 W5-2). */
	async delete(ids: readonly string[]): Promise<number> {
		if (ids.length === 0) return this.writeSeq();
		const seq = this.#check(await this.#c.write({ kind: 'delete', ids: [...ids] }));
		const still = new Set(await this.#read<string[]>('existing', [...ids]));
		const gone = ids.filter((id) => !still.has(id));
		await this.#details.delete(gone);
		return seq ?? (await this.writeSeq());
	}

	/** Never on stage 2: a recovery writes a new file (S9). */
	async clearAll(): Promise<number> {
		throw new Error('history.db is never cleared');
	}

	/**
	 * Late rows by the id rule (SPEC §11 step 8): inserted only if neither
	 * `migration_source` nor `history.db` has the id, a pulled duplicate
	 * skipped; every id then joins `migration_source`, so each imports once.
	 */
	importLate(rows: readonly RowRecord[]): Promise<{ imported: string[]; known: string[]; duplicates: string[] }> {
		return this.#c.importLate(rows.map((r) => ({ rowJson: JSON.stringify(r) })));
	}

	/** `done`'s seq, or null; `refused` throws. */
	#check(a: Applied): number | null {
		if (a.outcome === 'done') return a.written.change.seq;
		if (a.outcome === 'refused') throw new Error(`history.db refused the write: ${a.reason}`);
		return null;
	}
}

/** The store's pending journal, as a backend keeps it. */
export interface RowJournal {
	/** Keep `shot`'s row until the store holds it; whether it was kept. */
	add(shot: StoredShot, summary: ShotSummary): boolean;
	/** The store holds them now (or deleted them): their entries go. */
	drop(ids: readonly string[]): void;
}

/** Stage 2's journal (M5): one entry per shot, with no write seq, for the owner's insert-if-absent. */
export function sqliteJournal(storage: JournalStorage): RowJournal {
	return {
		add(shot, summary) {
			try {
				appendPending(storage, JSON.stringify(rowOf(shot, summary)));
				return true;
			} catch {
				return false;
			}
		},
		drop(ids) {
			for (const id of ids) {
				try {
					storage.removeItem(pendingKey(id));
				} catch {
					// It stays; the drain finds the row there and leaves it.
				}
			}
		}
	};
}
