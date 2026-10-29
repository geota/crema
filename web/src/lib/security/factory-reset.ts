/**
 * `#lib/security/factory-reset.js` — erase everything the web app keeps on
 * this device (Settings → Advanced → Factory reset).
 *
 * What a reset removes, in this order:
 *
 *  1. **Server-side grants.** Each `revoke` callback runs first, while the
 *     tokens can still be read (they are wrapped by the secret box, whose key
 *     is deleted in step 2). Revocation is best-effort: a callback that throws
 *     or hangs past `revokeTimeoutMs` is counted and skipped, and never blocks
 *     the local wipe. Decent has no revoke endpoint, so only Visualizer and
 *     Google Drive are revoked.
 *  2. **IndexedDB.** Every database the app creates — per-shot captures, the
 *     history telemetry series, bean bag photos, the secret-box key — plus any
 *     other `crema-*` database the browser lists. The stores that cache a
 *     connection close it on `versionchange`, so this tab never blocks its own
 *     delete; another open Crema tab can, which the result reports.
 *  3. **Web storage.** Every `crema.*` key in localStorage and sessionStorage
 *     (preferences, library, history summaries, the wrapped Visualizer and
 *     Decent tokens, the Drive session token, the OAuth handshake).
 *
 * The caller reloads the page afterwards so no in-memory store writes stale
 * state back. The DE1 itself is not touched.
 */

import { DB_NAME as CAPTURES_DB } from '#lib/capture/store.js';
import { DB_NAME as SERIES_DB } from '#lib/history/series-store.js';
import { DB_NAME as BEAN_IMAGES_DB } from '#lib/bean/image-storage.js';
import { DB_NAME as KEYS_DB } from '#lib/security/secret-box.js';
import { withTimeout } from '#lib/utils/timeout.js';

/** Every IndexedDB database the web app creates. */
export const CREMA_DATABASES: readonly string[] = [CAPTURES_DB, SERIES_DB, BEAN_IMAGES_DB, KEYS_DB];

/** Prefix of every Crema web-storage key. */
const STORAGE_PREFIX = 'crema.';
/** Prefix of every Crema IndexedDB database name. */
const DATABASE_PREFIX = 'crema-';

export interface FactoryResetDeps {
	localStorage?: Storage;
	sessionStorage?: Storage;
	indexedDB?: IDBFactory;
	/** Server-side revocations, each best-effort. */
	revoke?: ReadonlyArray<() => Promise<void>>;
	/** Give up on one revocation after this long. */
	revokeTimeoutMs?: number;
	/** Give up on one database delete after this long (e.g. blocked by another tab). */
	deleteTimeoutMs?: number;
}

export interface FactoryResetResult {
	/** Databases that were deleted (or did not exist). */
	databasesDeleted: string[];
	/** Databases the browser did not delete in time — usually another open Crema tab. */
	databasesFailed: string[];
	/** `crema.*` keys removed from localStorage + sessionStorage. */
	storageKeysRemoved: number;
	/** Revocations that threw or timed out. The local wipe ran regardless. */
	revokeFailures: number;
}

function deleteDatabase(idb: IDBFactory, name: string, timeoutMs: number): Promise<boolean> {
	return new Promise<boolean>((resolve) => {
		let settled = false;
		const finish = (ok: boolean): void => {
			if (settled) return;
			settled = true;
			clearTimeout(timer);
			resolve(ok);
		};
		const timer = setTimeout(() => finish(false), timeoutMs);
		let req: IDBOpenDBRequest;
		try {
			req = idb.deleteDatabase(name);
		} catch {
			finish(false);
			return;
		}
		req.onsuccess = () => finish(true);
		req.onerror = () => finish(false);
		// `onblocked`: another connection (another tab) has not closed yet. The
		// request stays queued and completes when it does; we wait until the
		// timeout and report it, so the user can close the other tab and retry.
	});
}

async function cremaDatabaseNames(idb: IDBFactory): Promise<string[]> {
	const names = new Set<string>(CREMA_DATABASES);
	try {
		const listed = typeof idb.databases === 'function' ? await idb.databases() : [];
		for (const info of listed) {
			if (info.name && info.name.startsWith(DATABASE_PREFIX)) names.add(info.name);
		}
	} catch {
		// `databases()` is missing or failed: the known list still covers every
		// database this build creates.
	}
	return [...names];
}

function clearPrefixed(storage: Storage | undefined): number {
	if (!storage) return 0;
	const keys: string[] = [];
	for (let i = 0; i < storage.length; i++) {
		const k = storage.key(i);
		if (k && k.startsWith(STORAGE_PREFIX)) keys.push(k);
	}
	for (const k of keys) storage.removeItem(k);
	return keys.length;
}

/** Erase all Crema data on this device. See the module comment for the order. */
export async function factoryResetLocalData(deps: FactoryResetDeps = {}): Promise<FactoryResetResult> {
	const local = deps.localStorage ?? (typeof localStorage === 'undefined' ? undefined : localStorage);
	const session =
		deps.sessionStorage ?? (typeof sessionStorage === 'undefined' ? undefined : sessionStorage);
	const idb = deps.indexedDB ?? (typeof indexedDB === 'undefined' ? undefined : indexedDB);
	const revokeTimeoutMs = deps.revokeTimeoutMs ?? 5_000;
	const deleteTimeoutMs = deps.deleteTimeoutMs ?? 5_000;

	let revokeFailures = 0;
	const revocations = await Promise.allSettled(
		(deps.revoke ?? []).map((revoke) => withTimeout(Promise.resolve().then(revoke), revokeTimeoutMs))
	);
	for (const r of revocations) if (r.status === 'rejected') revokeFailures++;

	const databasesDeleted: string[] = [];
	const databasesFailed: string[] = [];
	if (idb) {
		for (const name of await cremaDatabaseNames(idb)) {
			if (await deleteDatabase(idb, name, deleteTimeoutMs)) databasesDeleted.push(name);
			else databasesFailed.push(name);
		}
	}

	const storageKeysRemoved = clearPrefixed(local) + clearPrefixed(session);
	return { databasesDeleted, databasesFailed, storageKeysRemoved, revokeFailures };
}
