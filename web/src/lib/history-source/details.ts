/**
 * `#lib/history-source/details.js` — the heavy parts of shots whose rows live
 * in `history.db` (#310 SPEC §2, R1w): IndexedDB `crema-history` / `details`,
 * where P3 put them. Rows move to SQLite; details stay.
 *
 * - **Never thinned.** A write keeps what's stored wherever the incoming part
 *   is empty ({@link keepHeavy}), or only fills ({@link fillHeavy}), as P3's
 *   own writes do.
 * - **Deletes name ids.** Nothing here clears the store.
 * - By default every write bumps `meta.writeSeq` in its own transaction, as
 *   every P3 write does, so a P3 tab that was frozen sees something changed.
 *   After the flip (W5) the app's writes pass `bump: false`: `meta` and
 *   `rows` are the old copy then, and stay as the flip left them (rule 3),
 *   and a moved `writeSeq` means an older build wrote (step 8).
 */
import {
	DETAILS,
	META,
	META_WRITE_SEQ,
	fillHeavy,
	keepHeavy,
	type DetailsRecord,
	type HistoryDb
} from '#lib/history/history-db.js';

export interface DetailsStore {
	/** The details of `ids` that exist. */
	get(ids: readonly string[]): Promise<Map<string, DetailsRecord>>;
	/** Write each, keeping (`keep`) or only filling (`fill`) what's stored; the details as they end up. */
	put(details: readonly DetailsRecord[], how: 'keep' | 'fill'): Promise<Map<string, DetailsRecord>>;
	/** Delete the details of `ids`. */
	delete(ids: readonly string[]): Promise<void>;
}

function txp(tx: IDBTransaction): Promise<void> {
	return new Promise((resolve, reject) => {
		tx.oncomplete = () => resolve();
		tx.onerror = () => reject(tx.error ?? new Error('The transaction failed.'));
		tx.onabort = () => reject(tx.error ?? new DOMException('The transaction was aborted.', 'AbortError'));
	});
}

function bumpSeq(tx: IDBTransaction): void {
	const meta = tx.objectStore(META);
	const req = meta.get(META_WRITE_SEQ) as IDBRequest<number | undefined>;
	req.onsuccess = () => meta.put((typeof req.result === 'number' ? req.result : 0) + 1, META_WRITE_SEQ);
}

/** `crema-history`'s `details`, through `db`'s connection (opened with `expectExisting`, so never recreated). */
export function idbDetails(db: HistoryDb, o: { bump?: boolean } = {}): DetailsStore {
	const bump = o.bump ?? true;
	const stores = bump ? [DETAILS, META] : [DETAILS];
	return {
		get: (ids) => db.detailsOf(ids),
		async put(details, how) {
			const out = new Map<string, DetailsRecord>();
			if (details.length === 0) return out;
			const idb = await db.open();
			const tx = idb.transaction(stores, 'readwrite', { durability: 'strict' });
			const store = tx.objectStore(DETAILS);
			for (const d of details) {
				const look = store.get(d.id) as IDBRequest<DetailsRecord | undefined>;
				look.onsuccess = () => {
					const kept = how === 'fill' ? fillHeavy(d, look.result) : keepHeavy(d, look.result);
					store.put(kept);
					out.set(d.id, kept);
				};
			}
			if (bump) bumpSeq(tx);
			await txp(tx);
			return out;
		},
		async delete(ids) {
			if (ids.length === 0) return;
			const idb = await db.open();
			const tx = idb.transaction(stores, 'readwrite', { durability: 'strict' });
			for (const id of ids) tx.objectStore(DETAILS).delete(id);
			if (bump) bumpSeq(tx);
			await txp(tx);
		}
	};
}
