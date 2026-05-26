/**
 * `$lib/bean/image-storage` — IndexedDB-backed store for bean photo blobs.
 *
 * Bean records keep a small string pointer in `Bean.imageRef` (e.g.
 * `"idb:bean-image:bean:<uuid>"`); the actual JPEG/PNG/etc bytes live
 * here. Mirrors `$lib/capture/store` field-for-field — IndexedDB
 * because blobs can be MB-scale (one user's BC export shipped a 2.2 MB
 * JPG); `localStorage` would be exhausted by the first bag.
 *
 * Crash-safety: the store auto-opens on first use, never holds a
 * connection beyond a single read/write transaction, and is safe to
 * call from any tab — IndexedDB serialises concurrent writes per
 * object-store transaction.
 *
 * GC: orphan blobs (refs whose bean was deleted from the library)
 * accumulate until `pruneTo` is called with the live ref set; the
 * bean store's startup path can hook this once the storage pipeline
 * is wired end-to-end.
 */

const DB_NAME = 'crema-bean-images';
const DB_VERSION = 1;
const STORE = 'images';

/** Build the canonical ref string for a bean's image. */
export function refForBean(beanId: string): string {
	return `idb:bean-image:${beanId}`;
}

/** Open (or create / upgrade) the bean-images database. */
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

function reqp<T>(req: IDBRequest<T>): Promise<T> {
	return new Promise((resolve, reject) => {
		req.onsuccess = () => resolve(req.result);
		req.onerror = () => reject(req.error);
	});
}

function txp(tx: IDBTransaction): Promise<void> {
	return new Promise((resolve, reject) => {
		tx.oncomplete = () => resolve();
		tx.onerror = () => reject(tx.error);
		tx.onabort = () => reject(tx.error);
	});
}

/**
 * Per-bean image store. One instance per app — see
 * {@link getBeanImageStore}.
 */
export class BeanImageStore {
	private dbPromise: Promise<IDBDatabase> | undefined;

	private getDb(): Promise<IDBDatabase> {
		if (!this.dbPromise) this.dbPromise = openDb();
		return this.dbPromise;
	}

	/**
	 * Persist `blob` under `ref` (the [`refForBean`] convention),
	 * overwriting any previous image. Returns the same ref for
	 * caller-side convenience.
	 */
	async put(ref: string, blob: Blob): Promise<string> {
		const db = await this.getDb();
		const tx = db.transaction(STORE, 'readwrite');
		tx.objectStore(STORE).put(blob, ref);
		await txp(tx);
		return ref;
	}

	/** Fetch the image blob, or `null` if none stored. */
	async get(ref: string): Promise<Blob | null> {
		const db = await this.getDb();
		const tx = db.transaction(STORE, 'readonly');
		const v = await reqp<Blob | undefined>(tx.objectStore(STORE).get(ref));
		return v ?? null;
	}

	/** Drop one bean's image. */
	async delete(ref: string): Promise<void> {
		const db = await this.getDb();
		const tx = db.transaction(STORE, 'readwrite');
		tx.objectStore(STORE).delete(ref);
		await txp(tx);
	}

	/** Every stored ref. */
	async keys(): Promise<string[]> {
		const db = await this.getDb();
		const tx = db.transaction(STORE, 'readonly');
		return reqp(tx.objectStore(STORE).getAllKeys()) as Promise<string[]>;
	}

	/**
	 * Delete every image whose ref is not in `validRefs`. Run at app
	 * startup to GC blobs for beans that have been deleted from the
	 * library. Decoupling eviction this way keeps `BeanStore` ignorant
	 * of image bytes.
	 */
	async pruneTo(validRefs: ReadonlySet<string>): Promise<void> {
		const keys = await this.keys();
		const stale = keys.filter((k) => !validRefs.has(k));
		if (stale.length === 0) return;
		const db = await this.getDb();
		const tx = db.transaction(STORE, 'readwrite');
		const store = tx.objectStore(STORE);
		for (const k of stale) store.delete(k);
		await txp(tx);
	}
}

/** Process-wide singleton. */
let store: BeanImageStore | undefined;

/** Get the shared {@link BeanImageStore}, creating it on first call. */
export function getBeanImageStore(): BeanImageStore {
	if (!store) store = new BeanImageStore();
	return store;
}
