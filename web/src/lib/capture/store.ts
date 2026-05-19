/**
 * `$lib/capture/store` — IndexedDB-backed per-shot capture store.
 *
 * Keyed by `ShotRecord.id` — one capture per shot. IndexedDB rather than
 * `localStorage` because a raw capture is ~300–400 KB / shot, easily exceeding
 * the ~5–10 MB total localStorage quota after a few shots; the IndexedDB quota
 * is typically a large share of free disk, so hundreds of captures fit
 * comfortably.
 *
 * Captures are *not* deleted when a `ShotRecord` is evicted from history (the
 * shell does not couple the stores in the hot path). Instead the orchestrator
 * calls {@link CaptureStore.pruneTo} once at startup, dropping any capture
 * whose `ShotRecord` no longer exists — a simple lazy GC.
 */

import type { CaptureEntry } from './recorder';

/** IndexedDB database name. */
const DB_NAME = 'crema-captures';
/** Schema version — bump when the object-store layout changes. */
const DB_VERSION = 1;
/** The single object store: shot-id → CaptureEntry[]. */
const STORE = 'captures';

/** Open (or create / upgrade) the captures database. */
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

/** Adapt an `IDBTransaction`'s completion to a Promise. */
function txp(tx: IDBTransaction): Promise<void> {
	return new Promise((resolve, reject) => {
		tx.oncomplete = () => resolve();
		tx.onerror = () => reject(tx.error);
		tx.onabort = () => reject(tx.error);
	});
}

/**
 * Persistent per-shot capture store, IndexedDB-backed. One instance per app —
 * see {@link getCaptureStore}.
 */
export class CaptureStore {
	private dbPromise: Promise<IDBDatabase> | undefined;

	/** Lazy DB open — the connection is opened on the first call. */
	private getDb(): Promise<IDBDatabase> {
		if (!this.dbPromise) this.dbPromise = openDb();
		return this.dbPromise;
	}

	/** Persist `entries` under `shotId`, overwriting any previous capture. */
	async put(shotId: string, entries: readonly CaptureEntry[]): Promise<void> {
		const db = await this.getDb();
		const tx = db.transaction(STORE, 'readwrite');
		tx.objectStore(STORE).put(entries, shotId);
		await txp(tx);
	}

	/** Read a shot's capture, or `undefined` if none. */
	async get(shotId: string): Promise<readonly CaptureEntry[] | undefined> {
		const db = await this.getDb();
		const tx = db.transaction(STORE, 'readonly');
		return reqp<CaptureEntry[] | undefined>(tx.objectStore(STORE).get(shotId));
	}

	/** True if a capture is stored for `shotId`. Cheap key-only check. */
	async has(shotId: string): Promise<boolean> {
		const db = await this.getDb();
		const tx = db.transaction(STORE, 'readonly');
		const count = await reqp(tx.objectStore(STORE).count(shotId));
		return count > 0;
	}

	/** Drop one shot's capture. */
	async delete(shotId: string): Promise<void> {
		const db = await this.getDb();
		const tx = db.transaction(STORE, 'readwrite');
		tx.objectStore(STORE).delete(shotId);
		await txp(tx);
	}

	/** Every stored shot id. */
	async keys(): Promise<string[]> {
		const db = await this.getDb();
		const tx = db.transaction(STORE, 'readonly');
		return reqp(tx.objectStore(STORE).getAllKeys()) as Promise<string[]>;
	}

	/**
	 * Delete every capture whose key is not in `validIds`. Run at app startup
	 * to garbage-collect captures for shots that have been evicted from the
	 * `HistoryStore`'s capped list (or deleted by the user). Decoupling
	 * eviction this way keeps `HistoryStore` ignorant of captures.
	 */
	async pruneTo(validIds: ReadonlySet<string>): Promise<void> {
		const keys = await this.keys();
		const stale = keys.filter((k) => !validIds.has(k));
		if (stale.length === 0) return;
		const db = await this.getDb();
		const tx = db.transaction(STORE, 'readwrite');
		const store = tx.objectStore(STORE);
		for (const k of stale) store.delete(k);
		await txp(tx);
	}
}

/** Process-wide capture-store singleton. */
let store: CaptureStore | undefined;
export function getCaptureStore(): CaptureStore {
	if (!store) store = new CaptureStore();
	return store;
}
