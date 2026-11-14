/**
 * `#lib/history/history-db.js` — the history's own IndexedDB database,
 * `crema-history` (#310 P3w, SPEC §2).
 *
 * | Store     | Key  | Holds                                                  |
 * | --------- | ---- | ------------------------------------------------------ |
 * | `rows`    | `id` | a row: everything but the heavy parts, plus `summary`  |
 * | `details` | `id` | `{ id, samples, profile, brewSeries }`                 |
 * | `meta`    | key  | the migration record and `writeSeq`                    |
 *
 * No indexes: filters and dates run in memory, as they read live bean names.
 *
 * One cached connection, as the capture store keeps: it closes on
 * `versionchange`, so a factory reset isn't blocked, and a connection the
 * browser closes (`onclose`) is dropped and opened again on the next use.
 *
 * **Never empty by surprise.** With `expectExisting`, the store was migrated
 * once, so a database that isn't there has been lost: the upgrade that would
 * create it is aborted, so no empty database appears, and the open fails
 * with {@link HistoryDbLost}. The caller shows the lost banner, and never
 * migrates silently (SPEC §6).
 *
 * **Heavy parts are never thinned.** A details write keeps what the store
 * holds wherever the incoming value is empty: samples, a profile or a brew
 * series once stored are only ever replaced by real ones, so a row read
 * light, or a backup row with no curve, can't cost a shot its curve.
 *
 * Every write bumps `meta.writeSeq` in the same transaction, so a tab that was
 * frozen can tell it missed something.
 */
import type { TimedSample } from '#lib/core/index.js';
import type { BrewSeries, ShotSummary } from '#lib/core/crema-core.js';
import { HISTORY_DB_NAME } from './store-names';
import type { StoredShot } from './model';

/** Schema version. Changes are additive only. */
export const HISTORY_DB_VERSION = 1;
export const ROWS = 'rows';
export const DETAILS = 'details';
export const META = 'meta';

/** The key of the migration record in `meta`. */
export const META_MIGRATION = 'migration';
/** The key of the write counter in `meta`. */
export const META_WRITE_SEQ = 'writeSeq';

/** A stored row: the shot without its heavy parts, with its summary. */
export type RowRecord = Omit<StoredShot, 'profile' | 'brewSeries' | 'record'> & {
	record: { duration: number; samples: [] };
	summary: ShotSummary;
};

/** A stored shot's heavy parts. */
export interface DetailsRecord {
	id: string;
	samples: TimedSample[];
	profile: unknown | null;
	brewSeries: BrewSeries | null;
}

/** The migration record in `meta` (SPEC §6). */
export interface MigrationRecord {
	state: 'copying' | 'done';
	/** A hash of the old blob as it was copied. */
	sourceHash: string;
	/** Every id the old blob held, after migration and each late row since. */
	sourceIds: string[];
	/** Late rows that arrived with 0 samples: looked for again each boot. */
	lateIds?: string[];
	/** When the copy finished. */
	doneAt?: number;
}

/** The database isn't there, though the marker says it was made. */
export class HistoryDbLost extends Error {
	constructor() {
		super('The shot history database is missing.');
		this.name = 'HistoryDbLost';
	}
}

/** A shot split into what `rows` and `details` hold. */
export interface SplitShot {
	row: RowRecord;
	details: DetailsRecord;
}

/** The heavy parts of `shot`, apart. */
export function detailsOf(shot: StoredShot): DetailsRecord {
	return {
		id: shot.id,
		samples: shot.record.samples as TimedSample[],
		profile: shot.profile ?? null,
		brewSeries: shot.brewSeries ?? null
	};
}

/** `shot` without its heavy parts: what a light row in memory is. */
export function lightOf(shot: StoredShot): StoredShot {
	const { brewSeries: _b, ...rest } = shot;
	return { ...rest, profile: null, record: { duration: shot.record.duration, samples: [] } };
}

/** The `rows` record for `shot`, with its summary. */
export function rowOf(shot: StoredShot, summary: ShotSummary): RowRecord {
	const { brewSeries: _b, profile: _p, ...rest } = shot;
	return { ...rest, record: { duration: shot.record.duration, samples: [] }, summary };
}

/** A stored row as a light shot, and its summary. */
export function shotOfRow(row: RowRecord): { shot: StoredShot; summary: ShotSummary } {
	const { summary, ...rest } = row;
	return { shot: { ...rest, profile: null, record: { duration: row.record.duration, samples: [] } }, summary };
}

/** `row` with `details` put back. */
export function withDetails(shot: StoredShot, details: DetailsRecord | undefined): StoredShot {
	if (!details) return shot;
	const { brewSeries: _b, ...rest } = shot;
	return {
		...rest,
		profile: details.profile ?? null,
		record: { duration: shot.record.duration, samples: details.samples ?? [] },
		...(details.brewSeries ? { brewSeries: details.brewSeries } : {})
	};
}

/** What a details write keeps: the incoming parts, and the stored ones where those are empty. */
export function keepHeavy(incoming: DetailsRecord, stored: DetailsRecord | undefined): DetailsRecord {
	if (!stored) return incoming;
	return {
		id: incoming.id,
		samples: incoming.samples.length > 0 ? incoming.samples : (stored.samples ?? []),
		profile: incoming.profile ?? stored.profile ?? null,
		brewSeries:
			incoming.brewSeries && incoming.brewSeries.samples.length > 0
				? incoming.brewSeries
				: (stored.brewSeries ?? incoming.brewSeries ?? null)
	};
}

/**
 * A test seam: called before each transaction this wrapper opens, by kind,
 * and may throw (a full disk, a crash) to stop it there.
 */
export type HistoryDbFault = (op: string) => void;

export interface HistoryDbOptions {
	/** The IndexedDB factory; the global one by default. */
	idb?: () => IDBFactory | undefined;
	name?: string;
	/** The store was made before: a missing database is lost, never re-created. */
	expectExisting: () => boolean;
	fault?: HistoryDbFault;
}

function reqp<T>(req: IDBRequest<T>): Promise<T> {
	return new Promise((resolve, reject) => {
		req.onsuccess = () => resolve(req.result);
		req.onerror = () => reject(req.error);
	});
}

function txp(tx: IDBTransaction): Promise<void> {
	return new Promise((resolve, reject) => {
		tx.oncomplete = () => resolve();
		tx.onerror = () => reject(tx.error ?? new Error('The transaction failed.'));
		tx.onabort = () => reject(tx.error ?? new DOMException('The transaction was aborted.', 'AbortError'));
	});
}

/** What a shot write does with a row's details. */
export interface ShotWrite {
	/** The row's fields; its summary is worked out from the details as stored. */
	shot: StoredShot;
	/** Also write the heavy parts (never thinning what's stored). */
	details: boolean;
	/** The summary to store with a row-only write. */
	summary?: ShotSummary;
}

/** The details as they ended up, for rows whose heavy parts were written. */
export type WrittenDetails = Map<string, { details: DetailsRecord; summary: ShotSummary }>;

/** The `crema-history` database. */
export class HistoryDb {
	private opening: Promise<IDBDatabase> | undefined;
	private readonly idb: () => IDBFactory | undefined;
	private readonly name: string;

	constructor(private readonly opts: HistoryDbOptions) {
		this.idb = opts.idb ?? (() => (typeof indexedDB === 'undefined' ? undefined : indexedDB));
		this.name = opts.name ?? HISTORY_DB_NAME;
	}

	private fault(op: string): void {
		this.opts.fault?.(op);
	}

	/** The cached connection, opened on first use. */
	open(): Promise<IDBDatabase> {
		if (!this.opening) {
			const opening = this.openFresh().then((db) => {
				const drop = () => {
					if (this.opening === opening) this.opening = undefined;
				};
				db.onversionchange = () => {
					db.close();
					drop();
				};
				db.onclose = drop;
				return db;
			});
			opening.catch(() => {
				if (this.opening === opening) this.opening = undefined;
			});
			this.opening = opening;
		}
		return this.opening;
	}

	/** Close the cached connection, if any. */
	close(): void {
		const opening = this.opening;
		this.opening = undefined;
		void opening?.then((db) => db.close()).catch(() => {});
	}

	private openFresh(): Promise<IDBDatabase> {
		const idb = this.idb();
		if (!idb) return Promise.reject(new Error('IndexedDB is not available.'));
		return new Promise((resolve, reject) => {
			let lost = false;
			let req: IDBOpenDBRequest;
			try {
				req = idb.open(this.name, HISTORY_DB_VERSION);
			} catch (e) {
				reject(e);
				return;
			}
			req.onupgradeneeded = (event) => {
				const db = req.result;
				if (event.oldVersion === 0 && this.opts.expectExisting()) {
					// Lost: don't let an empty database appear in its place.
					lost = true;
					req.transaction?.abort();
					return;
				}
				if (!db.objectStoreNames.contains(ROWS)) db.createObjectStore(ROWS, { keyPath: 'id' });
				if (!db.objectStoreNames.contains(DETAILS)) db.createObjectStore(DETAILS, { keyPath: 'id' });
				if (!db.objectStoreNames.contains(META)) db.createObjectStore(META);
			};
			req.onsuccess = () => {
				if (lost) {
					req.result.close();
					reject(new HistoryDbLost());
					return;
				}
				resolve(req.result);
			};
			req.onerror = () => reject(lost ? new HistoryDbLost() : (req.error ?? new Error('IndexedDB open failed.')));
			req.onblocked = () => {
				// Another tab holds an older version open: wait for it, as the
				// upgrade completes once it closes.
			};
		});
	}

	/** Every stored row. */
	async allRows(): Promise<RowRecord[]> {
		const db = await this.open();
		this.fault('read-rows');
		return reqp(db.transaction(ROWS, 'readonly').objectStore(ROWS).getAll() as IDBRequest<RowRecord[]>);
	}

	/** The rows of `ids` that exist. */
	async rowsOf(ids: readonly string[]): Promise<Map<string, RowRecord>> {
		const out = new Map<string, RowRecord>();
		if (ids.length === 0) return out;
		const db = await this.open();
		this.fault('read-rows');
		const store = db.transaction(ROWS, 'readonly').objectStore(ROWS);
		const got = await Promise.all(ids.map((id) => reqp(store.get(id) as IDBRequest<RowRecord | undefined>)));
		got.forEach((r, i) => {
			if (r) out.set(ids[i], r);
		});
		return out;
	}

	/** Every stored id in `details`. */
	async detailIds(): Promise<string[]> {
		const db = await this.open();
		this.fault('read-details');
		return (await reqp(db.transaction(DETAILS, 'readonly').objectStore(DETAILS).getAllKeys())) as string[];
	}

	/** The details of `ids` that exist. */
	async detailsOf(ids: readonly string[]): Promise<Map<string, DetailsRecord>> {
		const out = new Map<string, DetailsRecord>();
		if (ids.length === 0) return out;
		const db = await this.open();
		this.fault('read-details');
		const store = db.transaction(DETAILS, 'readonly').objectStore(DETAILS);
		const got = await Promise.all(ids.map((id) => reqp(store.get(id) as IDBRequest<DetailsRecord | undefined>)));
		got.forEach((d, i) => {
			if (d) out.set(ids[i], d);
		});
		return out;
	}

	/** One `meta` value. */
	async meta<T>(key: string): Promise<T | undefined> {
		const db = await this.open();
		this.fault('read-meta');
		return reqp(db.transaction(META, 'readonly').objectStore(META).get(key) as IDBRequest<T | undefined>);
	}

	/** Set one `meta` value, strictly. */
	async setMeta(key: string, value: unknown): Promise<void> {
		const db = await this.open();
		this.fault('write-meta');
		const tx = db.transaction(META, 'readwrite', { durability: 'strict' });
		tx.objectStore(META).put(value, key);
		await txp(tx);
	}

	/** Empty all three stores, in one transaction. */
	async clearAll(): Promise<void> {
		const db = await this.open();
		this.fault('clear');
		const tx = db.transaction([ROWS, DETAILS, META], 'readwrite', { durability: 'strict' });
		tx.objectStore(ROWS).clear();
		tx.objectStore(DETAILS).clear();
		tx.objectStore(META).clear();
		await txp(tx);
	}

	/**
	 * Write `shots` in one strict transaction, with the write counter, and
	 * return the new counter and the details as stored for each row written
	 * with them. Upsert only. `summarize` works out a summary from a whole
	 * shot; it runs inside the transaction, on the details as they end up.
	 */
	async putShots(
		shots: readonly ShotWrite[],
		summarize: (shot: StoredShot) => ShotSummary
	): Promise<{ seq: number; written: WrittenDetails }> {
		const db = await this.open();
		this.fault('write');
		const tx = db.transaction([ROWS, DETAILS, META], 'readwrite', { durability: 'strict' });
		const rows = tx.objectStore(ROWS);
		const details = tx.objectStore(DETAILS);
		const meta = tx.objectStore(META);
		const written: WrittenDetails = new Map();
		let failure: unknown = null;
		for (const w of shots) {
			if (!w.details) {
				if (!w.summary) throw new Error(`No summary for a row-only write of ${w.shot.id}`);
				rows.put(rowOf(w.shot, w.summary));
				continue;
			}
			const incoming = detailsOf(w.shot);
			const look = details.get(w.shot.id) as IDBRequest<DetailsRecord | undefined>;
			look.onsuccess = () => {
				try {
					const kept = keepHeavy(incoming, look.result);
					const summary = summarize(withDetails(w.shot, kept));
					details.put(kept);
					rows.put(rowOf(w.shot, summary));
					written.set(w.shot.id, { details: kept, summary });
				} catch (e) {
					failure = e;
					tx.abort();
				}
			};
		}
		const seqReq = meta.get(META_WRITE_SEQ) as IDBRequest<number | undefined>;
		let seq = 0;
		seqReq.onsuccess = () => {
			seq = (typeof seqReq.result === 'number' ? seqReq.result : 0) + 1;
			meta.put(seq, META_WRITE_SEQ);
		};
		try {
			await txp(tx);
		} catch (e) {
			throw failure ?? e;
		}
		return { seq, written };
	}

	/** Delete the rows and details of `ids`, in one strict transaction. */
	async delete(ids: readonly string[]): Promise<number> {
		const db = await this.open();
		this.fault('delete');
		const tx = db.transaction([ROWS, DETAILS, META], 'readwrite', { durability: 'strict' });
		for (const id of ids) {
			tx.objectStore(ROWS).delete(id);
			tx.objectStore(DETAILS).delete(id);
		}
		const meta = tx.objectStore(META);
		const seqReq = meta.get(META_WRITE_SEQ) as IDBRequest<number | undefined>;
		let seq = 0;
		seqReq.onsuccess = () => {
			seq = (typeof seqReq.result === 'number' ? seqReq.result : 0) + 1;
			meta.put(seq, META_WRITE_SEQ);
		};
		await txp(tx);
		return seq;
	}

	/** The write counter. */
	async writeSeq(): Promise<number> {
		const v = await this.meta<number>(META_WRITE_SEQ);
		return typeof v === 'number' ? v : 0;
	}
}
