/**
 * `$lib/security/secret-box` — at-rest wrapping for the credentials the app
 * has to keep in browser storage (the Visualizer OAuth tokens, the Decent
 * account token).
 *
 * What it does: AES-GCM with a 256-bit key that Web Crypto generates as
 * NON-extractable and that lives only in IndexedDB (a `CryptoKey` is
 * structured-cloneable; the key material never exists as bytes JavaScript
 * can read). The wrapped value is what goes into localStorage.
 *
 * What it protects against: reading the token out of a storage dump — a
 * browser profile backup or sync, a DevTools / extension with storage
 * access, disk forensics on a shared machine. The ciphertext is useless
 * without the key, and the key cannot leave this origin's IndexedDB.
 *
 * What it does NOT protect against: script running on this origin. Any
 * such script can call `unwrap` just like the app does. The defence for
 * that is the CSP (`web/svelte.config.js`) and keeping third-party script
 * out of the page — this module is the second layer, not the first.
 *
 * Degrades honestly: with no Web Crypto or no IndexedDB (node tests, some
 * private modes) `wrap` returns the plaintext unchanged and `unwrap`
 * passes plaintext through, so a value written before this module
 * existed keeps working and is re-wrapped on the next write. A wrapped
 * value whose key is gone (IndexedDB cleared, localStorage kept) unwraps
 * to `null` — callers surface that as "sign in again" (the Decent
 * account's `needsReauth`; Visualizer reads as disconnected).
 */

const PREFIX = 'sb1:';
const DB_NAME = 'crema-keys';
const DB_VERSION = 1;
const STORE = 'keys';
const KEY_ID = 'secret-box-v1';

/** The key source — the default persists a non-extractable AES key in IndexedDB. */
export interface KeyProvider {
	getKey(): Promise<CryptoKey | null>;
}

function subtle(): SubtleCrypto | null {
	return typeof crypto !== 'undefined' && crypto.subtle ? crypto.subtle : null;
}

function openDb(idb: IDBFactory): Promise<IDBDatabase> {
	return new Promise((resolve, reject) => {
		const req = idb.open(DB_NAME, DB_VERSION);
		req.onupgradeneeded = () => {
			const db = req.result;
			if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
		};
		req.onsuccess = () => resolve(req.result);
		req.onerror = () => reject(req.error);
	});
}

function isCryptoKey(v: unknown): v is CryptoKey {
	return typeof v === 'object' && v !== null && 'algorithm' in v && 'usages' in v;
}

function isConstraintError(e: unknown): boolean {
	return typeof e === 'object' && e !== null && 'name' in e && e.name === 'ConstraintError';
}

/** The stored key, or `null` when none has been written yet. */
function readKey(db: IDBDatabase): Promise<CryptoKey | null> {
	return new Promise((resolve, reject) => {
		const req = db.transaction(STORE, 'readonly').objectStore(STORE).get(KEY_ID);
		req.onsuccess = () => resolve(isCryptoKey(req.result) ? req.result : null);
		req.onerror = () => reject(req.error);
	});
}

/**
 * Get-or-add in ONE readwrite transaction: if another tab stored a key since
 * our read, that key wins and `candidate` is discarded. IndexedDB serialises
 * readwrite transactions on a store, so two tabs cannot both add; `add` (not
 * `put`) turns any residual overlap into a `ConstraintError` rather than a
 * silent overwrite. Resolves on transaction commit.
 */
function getOrAddKey(db: IDBDatabase, candidate: CryptoKey): Promise<CryptoKey> {
	return new Promise((resolve, reject) => {
		const tx = db.transaction(STORE, 'readwrite');
		const store = tx.objectStore(STORE);
		let chosen: CryptoKey = candidate;
		const get = store.get(KEY_ID);
		get.onsuccess = () => {
			if (isCryptoKey(get.result)) chosen = get.result;
			else store.add(candidate, KEY_ID);
		};
		tx.oncomplete = () => resolve(chosen);
		tx.onabort = () => reject(tx.error);
	});
}

/**
 * The IndexedDB key source. Generate-once, then reuse: the key is created on
 * first use and never rotated. Safe across tabs — see {@link getOrAddKey};
 * a `ConstraintError` (a racing tab added first) re-reads the winner.
 * `factory` is injectable for tests (jsdom has no IndexedDB).
 */
export function createIndexedDbKeyProvider(
	factory: () => IDBFactory | undefined = () =>
		typeof indexedDB === 'undefined' ? undefined : indexedDB
): KeyProvider {
	return {
		async getKey() {
			const s = subtle();
			const idb = factory();
			if (!s || !idb) return null;
			try {
				const db = await openDb(idb);
				try {
					const existing = await readKey(db);
					if (existing) return existing;
					const candidate = await s.generateKey({ name: 'AES-GCM', length: 256 }, false, [
						'encrypt',
						'decrypt'
					]);
					try {
						return await getOrAddKey(db, candidate);
					} catch (e) {
						if (!isConstraintError(e)) throw e;
						const winner = await readKey(db);
						if (winner) return winner;
						throw e;
					}
				} finally {
					db.close();
				}
			} catch (e) {
				console.warn('[Crema] secret-box key unavailable — storing credentials unwrapped:', e);
				return null;
			}
		}
	};
}

/** The app's key source: a non-extractable AES key persisted in IndexedDB. */
export const indexedDbKeyProvider: KeyProvider = createIndexedDbKeyProvider();

function b64(bytes: Uint8Array): string {
	let bin = '';
	for (const b of bytes) bin += String.fromCharCode(b);
	return btoa(bin);
}
function unb64(s: string): Uint8Array<ArrayBuffer> {
	const bin = atob(s);
	// Backed by a plain ArrayBuffer so it satisfies `BufferSource` for Web Crypto.
	const out = new Uint8Array(new ArrayBuffer(bin.length));
	for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
	return out;
}

export function isWrapped(value: string): boolean {
	return value.startsWith(PREFIX);
}

export interface SecretBox {
	/** Wrap a secret for storage; returns it unchanged when wrapping is unavailable. */
	wrap(plain: string): Promise<string>;
	/**
	 * Recover a stored secret. Plaintext (pre-wrapping) passes through;
	 * a wrapped value the key can no longer open resolves to `null`.
	 */
	unwrap(stored: string): Promise<string | null>;
}

export function createSecretBox(provider: KeyProvider = indexedDbKeyProvider): SecretBox {
	// Cache only a key that was actually obtained: a failed or `null` lookup
	// (IndexedDB momentarily unavailable) is retried on the next call rather
	// than pinning the whole session to "no key".
	let keyPromise: Promise<CryptoKey | null> | null = null;
	const key = (): Promise<CryptoKey | null> => {
		if (keyPromise) return keyPromise;
		const attempt: Promise<CryptoKey | null> = provider
			.getKey()
			.catch(() => null)
			.then((k) => {
				if (k === null && keyPromise === attempt) keyPromise = null;
				return k;
			});
		keyPromise = attempt;
		return attempt;
	};
	return {
		async wrap(plain) {
			const k = await key();
			const s = subtle();
			if (!k || !s) return plain;
			const iv = crypto.getRandomValues(new Uint8Array(12));
			const ct = await s.encrypt({ name: 'AES-GCM', iv }, k, new TextEncoder().encode(plain));
			return `${PREFIX}${b64(iv)}:${b64(new Uint8Array(ct))}`;
		},
		async unwrap(stored) {
			if (!isWrapped(stored)) return stored;
			const k = await key();
			const s = subtle();
			if (!k || !s) return null;
			try {
				const [ivB64, ctB64] = stored.slice(PREFIX.length).split(':');
				const pt = await s.decrypt({ name: 'AES-GCM', iv: unb64(ivB64) }, k, unb64(ctB64));
				return new TextDecoder().decode(pt);
			} catch {
				return null;
			}
		}
	};
}

/** The app-wide box (one key per origin). */
export const secretBox: SecretBox = createSecretBox();
