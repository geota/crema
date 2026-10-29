/**
 * `#lib/security/secret-box.js` — at-rest wrapping for the credentials the app
 * has to keep in browser storage (the Visualizer OAuth tokens, the Decent
 * account token).
 *
 * What it does: AES-GCM with a 256-bit key that Web Crypto generates as
 * NON-extractable and stores in IndexedDB (a `CryptoKey` is
 * structured-cloneable; page script can use it but never read its bytes).
 * The wrapped value is what goes into localStorage.
 *
 * What it protects against: a leak of localStorage ALONE — a storage dump
 * or export that does not include IndexedDB, a DevTools or extension view of
 * localStorage, a support screenshot. The ciphertext is useless without the
 * key.
 *
 * What it does NOT protect against:
 *  - A copy of the whole browser profile or disk. Browsers persist a
 *    non-extractable `CryptoKey` WITH its key material inside the profile's
 *    IndexedDB files (Chromium: the IndexedDB LevelDB), so a profile backup,
 *    sync of site data or a disk image holds both the ciphertext and the key.
 *  - Script running on this origin. It can call `unwrap` just like the app
 *    does. The defence for that is the CSP (`#lib/security/csp.js`) and
 *    keeping third-party script out of the page.
 * Treat it as one layer that keeps tokens out of casual storage dumps, not as
 * encryption at rest.
 *
 * No key, three cases (review 05 P3-6, final review 05 N-6):
 *  - This browser has never produced a key for this origin AND has no Web
 *    Crypto or IndexedDB at all (node tests, some private modes): `wrap`
 *    returns the plaintext so sign-in still works, logs a warning, and
 *    {@link credentialsStoredUnencrypted} lets the Settings UI say so.
 *  - Both APIs exist but getting the key failed (IndexedDB momentarily
 *    unavailable, even on the very first sign-in): `wrap` throws
 *    {@link SecretBoxUnavailableError}; a transient failure never means
 *    plaintext. The next call tries again.
 *  - A key existed before (the {@link KEYED_MARKER} is set) but cannot be
 *    reached now: `wrap` throws {@link SecretBoxUnavailableError} rather than
 *    silently writing plaintext; the caller does not save the credential.
 * `unwrap` passes plaintext through (a value written before this module
 * existed keeps working and is re-wrapped on the next write). A wrapped value
 * whose key is gone unwraps to `null`, which callers surface as "sign in
 * again" (the Decent account's `needsReauth`; Visualizer reads as
 * disconnected).
 */

const PREFIX = 'sb1:';
/**
 * Set in localStorage once a key has been obtained on this origin. While it is
 * set, a missing key is an error, not a reason to store plaintext. Removed with
 * every other `crema.*` key by a factory reset.
 */
export const KEYED_MARKER = 'crema.secretBox.keyed.v1';

/** A key existed on this origin but cannot be reached; the secret was NOT stored. */
export class SecretBoxUnavailableError extends Error {
	/** Effect-style tag, so callers can `catchTag` it like the app's other errors. */
	readonly _tag = 'SecretBoxUnavailableError' as const;
	override name = 'SecretBoxUnavailableError';
	constructor() {
		super(
			"Couldn't reach this browser's encryption key, so the sign-in was not saved. Try again; if it keeps failing, reload the page."
		);
	}
}

function storage(): Storage | null {
	try {
		return typeof localStorage === 'undefined' ? null : localStorage;
	} catch {
		return null;
	}
}

function keyedBefore(): boolean {
	return storage()?.getItem(KEYED_MARKER) === '1';
}

function markKeyed(): void {
	try {
		storage()?.setItem(KEYED_MARKER, '1');
	} catch {
		// Quota / disabled storage: the marker is a safety net, not required.
	}
}
export const DB_NAME = 'crema-keys';
const DB_VERSION = 1;
const STORE = 'keys';
const KEY_ID = 'secret-box-v1';

/**
 * The key source — the default persists a non-extractable AES key in
 * IndexedDB. `getKey` resolves to `null` only when this browser has no Web
 * Crypto or no IndexedDB at all; a failure to reach the key REJECTS, so the
 * box can tell "cannot encrypt here" from "could not encrypt just now".
 */
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
				// Both APIs exist, so this is a failure, not an absence: reject
				// (the box refuses to store plaintext) and let the next call retry.
				console.warn('[Crema] secret-box key unavailable:', e);
				throw e;
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
	/**
	 * Wrap a secret for storage. Returns it unchanged only when this origin
	 * never had a key and this browser has no Web Crypto / IndexedDB; throws
	 * {@link SecretBoxUnavailableError} when getting the key failed or a key
	 * existed but is unreachable.
	 */
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
	// than pinning the whole session to "no key". `'failed'` is a lookup that
	// threw — never a reason to store plaintext.
	let keyPromise: Promise<CryptoKey | null | 'failed'> | null = null;
	const key = (): Promise<CryptoKey | null | 'failed'> => {
		if (keyPromise) return keyPromise;
		const attempt: Promise<CryptoKey | null | 'failed'> = provider
			.getKey()
			.catch((): 'failed' => 'failed')
			.then((k) => {
				if ((k === null || k === 'failed') && keyPromise === attempt) keyPromise = null;
				return k;
			});
		keyPromise = attempt;
		return attempt;
	};
	return {
		async wrap(plain) {
			const k = await key();
			const s = subtle();
			if (k === 'failed') throw new SecretBoxUnavailableError();
			if (!k || !s) {
				if (keyedBefore()) throw new SecretBoxUnavailableError();
				console.warn(
					'[Crema] this browser offers no Web Crypto / IndexedDB key — storing the credential unencrypted.'
				);
				return plain;
			}
			markKeyed();
			const iv = crypto.getRandomValues(new Uint8Array(12));
			const ct = await s.encrypt({ name: 'AES-GCM', iv }, k, new TextEncoder().encode(plain));
			return `${PREFIX}${b64(iv)}:${b64(new Uint8Array(ct))}`;
		},
		async unwrap(stored) {
			if (!isWrapped(stored)) return stored;
			const k = await key();
			const s = subtle();
			if (!k || k === 'failed' || !s) return null;
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
