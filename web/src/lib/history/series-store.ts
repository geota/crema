/**
 * `#lib/history/series-store.js` — IndexedDB-backed per-shot telemetry series.
 *
 * A `StoredShot`'s sample series is ~100–500 KB of JSON; keeping it inside
 * the localStorage history blob exhausted the ~5 MB quota after ~15–25
 * shots, at which point EVERY history save (new shots, ratings, notes)
 * failed silently forever (review #27). The series now lives here — same
 * IndexedDB posture as `#lib/capture/store.js` — while the history blob keeps
 * only the small summary/metadata rows, so the 300-shot cap is actually
 * reachable.
 *
 * Boot is two-phase: the history store loads its summaries synchronously
 * from localStorage (the list renders instantly), then hydrates each
 * shot's series from here in the background. Writes are fire-and-forget
 * with an error surface, mirroring the capture store.
 */

import type { TimedSample } from '#lib/core/index.js';

/** IndexedDB database name. */
export const DB_NAME = 'crema-history-series';
/** Schema version — bump when the object-store layout changes. */
const DB_VERSION = 1;
/** The single object store: shot-id → TimedSample[]. */
const STORE = 'series';

/** Open (or create / upgrade) the series database. */
function openDb(): Promise<IDBDatabase> {
	return new Promise((resolve, reject) => {
		const req = indexedDB.open(DB_NAME, DB_VERSION);
		req.onupgradeneeded = () => {
			const db = req.result;
			if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
		};
		req.onsuccess = () => resolve(req.result);
		req.onerror = () => reject(req.error);
	});
}

/** Adapt an `IDBRequest` to a Promise. */
function reqp<T>(req: IDBRequest<T>): Promise<T> {
	return new Promise((resolve, reject) => {
		req.onsuccess = () => resolve(req.result);
		req.onerror = () => reject(req.error);
	});
}

/** Store (or replace) one shot's series. */
export async function putSeries(id: string, samples: readonly TimedSample[]): Promise<void> {
	const db = await openDb();
	try {
		const tx = db.transaction(STORE, 'readwrite');
		tx.objectStore(STORE).put(samples as TimedSample[], id);
		await new Promise<void>((resolve, reject) => {
			tx.oncomplete = () => resolve();
			tx.onerror = () => reject(tx.error);
			tx.onabort = () => reject(tx.error);
		});
	} finally {
		db.close();
	}
}

/**
 * Store many series on one connection, in strict transactions of `batch`
 * rows each: a restore's 300 shots in 12 commits, not 300 connections.
 * Throws at the first batch that fails; the ones before it stand.
 */
export async function putManySeries(
	rows: readonly { id: string; samples: readonly TimedSample[] }[],
	batch = 25
): Promise<void> {
	if (rows.length === 0) return;
	const db = await openDb();
	try {
		for (let i = 0; i < rows.length; i += batch) {
			const tx = db.transaction(STORE, 'readwrite', { durability: 'strict' });
			const store = tx.objectStore(STORE);
			for (const r of rows.slice(i, i + batch)) store.put(r.samples as TimedSample[], r.id);
			await new Promise<void>((resolve, reject) => {
				tx.oncomplete = () => resolve();
				tx.onerror = () => reject(tx.error);
				tx.onabort = () => reject(tx.error);
			});
		}
	} finally {
		db.close();
	}
}

/** Load every stored series, keyed by shot id. */
export async function getAllSeries(): Promise<Map<string, TimedSample[]>> {
	const db = await openDb();
	try {
		const store = db.transaction(STORE, 'readonly').objectStore(STORE);
		const [keys, values] = await Promise.all([
			reqp(store.getAllKeys()),
			reqp(store.getAll() as IDBRequest<TimedSample[][]>)
		]);
		const out = new Map<string, TimedSample[]>();
		keys.forEach((k, i) => {
			const v = values[i];
			if (typeof k === 'string' && Array.isArray(v)) out.set(k, v);
		});
		return out;
	} finally {
		db.close();
	}
}

/** Load the series of each of `ids` that has one, keyed by shot id. */
export async function getSeriesOf(ids: readonly string[]): Promise<Map<string, TimedSample[]>> {
	const out = new Map<string, TimedSample[]>();
	if (ids.length === 0) return out;
	const db = await openDb();
	try {
		const store = db.transaction(STORE, 'readonly').objectStore(STORE);
		const values = await Promise.all(ids.map((id) => reqp(store.get(id) as IDBRequest<unknown>)));
		ids.forEach((id, i) => {
			const v = values[i];
			if (Array.isArray(v)) out.set(id, v as TimedSample[]);
		});
		return out;
	} finally {
		db.close();
	}
}

/** Delete one shot's series (a removed / hard-deleted shot). */
export async function deleteSeries(id: string): Promise<void> {
	const db = await openDb();
	try {
		const tx = db.transaction(STORE, 'readwrite');
		tx.objectStore(STORE).delete(id);
		await new Promise<void>((resolve, reject) => {
			tx.oncomplete = () => resolve();
			tx.onerror = () => reject(tx.error);
			tx.onabort = () => reject(tx.error);
		});
	} finally {
		db.close();
	}
}

/** Wipe the whole series store (a "replace from backup" restore). */
export async function clearAllSeries(): Promise<void> {
	const db = await openDb();
	try {
		const tx = db.transaction(STORE, 'readwrite');
		tx.objectStore(STORE).clear();
		await new Promise<void>((resolve, reject) => {
			tx.oncomplete = () => resolve();
			tx.onerror = () => reject(tx.error);
			tx.onabort = () => reject(tx.error);
		});
	} finally {
		db.close();
	}
}
