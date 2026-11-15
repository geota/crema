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
 *     delete; another open Crema tab can, which the result reports. Then
 *     the history's rows snapshot in the origin private file system, which a
 *     newer build writes (#310).
 *  3. **The SQLite history's files.** Every origin-private entry whose name
 *     starts with `.crema-history-` (both pools, any repair generations, the
 *     done-record last), which a newer build writes (SQLite history H0). This
 *     build writes none of them; it removes them so a reset after a rollback
 *     still takes everything. If one stays — another tab's Worker can hold a
 *     pool — the reset reports it with the databases, and keeps the
 *     done-record and the `crema.history.sqlite*` keys (the marker and the
 *     pending journal), so the next boot can't build a new marker over an
 *     erased history (S10).
 *  4. **Web storage.** Every `crema.*` key in localStorage and sessionStorage
 *     (preferences, library, history summaries, the wrapped Visualizer and
 *     Decent tokens, the Drive session token, the OAuth handshake).
 *
 * Between steps 1 and 2 the caller's `beforeWipe` runs — the shell freezes
 * every Crema write there (`#lib/security/reset-coordinator`), so nothing the
 * live app does can recreate a database or a key mid-wipe. The caller reloads
 * the page straight afterwards, with no dialog in between. The DE1 itself is
 * not touched.
 */

import { DB_NAME as CAPTURES_DB } from '#lib/capture/store.js';
import { DB_NAME as SERIES_DB } from '#lib/history/series-store.js';
import { DB_NAME as BEAN_IMAGES_DB } from '#lib/bean/image-storage.js';
import { DB_NAME as KEYS_DB } from '#lib/security/secret-box.js';
import { HISTORY_CURVES_NAME, HISTORY_DB_NAME, HISTORY_SNAPSHOT_NAME } from '#lib/history/store-names.js';
import { stopArchiveWrites } from '#lib/history/archive-writes.js';
import { withTimeout } from '#lib/utils/timeout.js';
import { FROZEN_SHOTS_KEY } from '#lib/restore/tab-safety.js';
import {
	DB_POOL_DIR,
	DONE_RECORD_NAME,
	HISTORY_SQLITE_OPFS_PREFIX,
	INDEX_POOL_DIR,
	MARKER_KEY,
	PENDING_KEY_PREFIX
} from '#lib/history-db/names.js';


/**
 * Every IndexedDB database the web app creates, and `crema-history`, which a
 * newer build does: a reset after a rollback must take it too, even where the
 * browser can't list databases (#310 P0).
 */
export const CREMA_DATABASES: readonly string[] = [
	CAPTURES_DB,
	SERIES_DB,
	BEAN_IMAGES_DB,
	KEYS_DB,
	HISTORY_DB_NAME
];

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
	/** Runs after the revocations and before the first delete (freeze writers here). */
	beforeWipe?: () => void;
	/** The origin private file system's root, or undefined where there is none. */
	privateFiles?: () => Promise<FileSystemDirectoryHandle | undefined>;
}

export interface FactoryResetResult {
	/** Databases that were deleted (or did not exist). */
	databasesDeleted: string[];
	/** Databases the browser did not delete in time — usually another open Crema tab — and the history's private files that stayed. */
	databasesFailed: string[];
	/** `crema.*` keys removed from localStorage + sessionStorage. */
	storageKeysRemoved: number;
	/** Revocations that threw or timed out. The local wipe ran regardless. */
	revokeFailures: number;
}

/** The origin private file system's root, where the browser has one. */
async function privateFilesRoot(): Promise<FileSystemDirectoryHandle | undefined> {
	if (typeof navigator === 'undefined' || typeof navigator.storage?.getDirectory !== 'function') {
		return undefined;
	}
	return navigator.storage.getDirectory();
}

/** Remove one of the history's private-file directories (the rows snapshots, the curve archive): false only when it's there and stays. */
async function removeSnapshot(
	root: () => Promise<FileSystemDirectoryHandle | undefined>,
	name: string = HISTORY_SNAPSHOT_NAME
): Promise<boolean> {
	try {
		const dir = await root();
		await dir?.removeEntry(name, { recursive: true });
		return true;
	} catch (e) {
		// None to remove, or no private file system at all.
		return e instanceof DOMException && (e.name === 'NotFoundError' || e.name === 'SecurityError');
	}
}

/** A missing entry, or no private file system at all: nothing to remove. */
function isNothingThere(e: unknown): boolean {
	return e instanceof DOMException && (e.name === 'NotFoundError' || e.name === 'SecurityError');
}

/**
 * Remove every private-file entry the SQLite history keeps (module comment,
 * step 3): the pools and anything else named `.crema-history-…`, then the
 * done-record, only once all of them are gone. Returns the names that stayed;
 * the prefix itself when the root can't be read or listed.
 */
async function removeSqliteHistory(root: () => Promise<FileSystemDirectoryHandle | undefined>): Promise<string[]> {
	let found: FileSystemDirectoryHandle | undefined;
	try {
		found = await root();
	} catch (e) {
		return isNothingThere(e) ? [] : [HISTORY_SQLITE_OPFS_PREFIX];
	}
	if (!found) return [];
	const dir = found;
	// The known names even where the root can't be listed; every other one it lists.
	const names = new Set<string>([INDEX_POOL_DIR, DB_POOL_DIR]);
	const listable = dir as unknown as { keys?: () => AsyncIterable<string> };
	if (typeof listable.keys === 'function') {
		try {
			for await (const name of listable.keys()) {
				if (name.startsWith(HISTORY_SQLITE_OPFS_PREFIX)) names.add(name);
			}
		} catch {
			return [HISTORY_SQLITE_OPFS_PREFIX];
		}
	}
	names.delete(DONE_RECORD_NAME);
	const stayed: string[] = [];
	const remove = async (name: string): Promise<void> => {
		try {
			await dir.removeEntry(name, { recursive: true });
		} catch (e) {
			if (!(e instanceof DOMException && e.name === 'NotFoundError')) stayed.push(name);
		}
	};
	for (const name of names) await remove(name);
	if (stayed.length === 0) await remove(DONE_RECORD_NAME);
	return stayed;
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

/** The SQLite history's marker and pending journal, kept when one of its files stays (S10). */
function isSqliteHistoryKey(key: string): boolean {
	return key === MARKER_KEY || key.startsWith(PENDING_KEY_PREFIX);
}

function clearPrefixed(storage: Storage | undefined, keep: (key: string) => boolean = () => false): number {
	if (!storage) return 0;
	const keys: string[] = [];
	for (let i = 0; i < storage.length; i++) {
		const k = storage.key(i);
		if (k && k.startsWith(STORAGE_PREFIX) && !keep(k)) keys.push(k);
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

	deps.beforeWipe?.();

	const databasesDeleted: string[] = [];
	const databasesFailed: string[] = [];
	if (idb) {
		for (const name of await cremaDatabaseNames(idb)) {
			if (await deleteDatabase(idb, name, deleteTimeoutMs)) databasesDeleted.push(name);
			else databasesFailed.push(name);
		}
	}

	// Another tab holding the file is the usual reason it stays, as for a
	// database, so it's reported with them.
	if (!(await removeSnapshot(deps.privateFiles ?? privateFilesRoot))) databasesFailed.push(HISTORY_SNAPSHOT_NAME);
	// No archive write may land after the directory goes: stop them, and wait
	// for the ones in flight, first.
	await stopArchiveWrites();
	if (!(await removeSnapshot(deps.privateFiles ?? privateFilesRoot, HISTORY_CURVES_NAME))) databasesFailed.push(HISTORY_CURVES_NAME);

	// Before any `crema.*` key goes: the marker must outlive every file it names (S10).
	const sqliteStayed = await removeSqliteHistory(deps.privateFiles ?? privateFilesRoot);
	databasesFailed.push(...sqliteStayed);
	const keep = sqliteStayed.length > 0 ? isSqliteHistoryKey : undefined;

	const storageKeysRemoved = clearPrefixed(local, keep) + clearPrefixed(session, keep);
	// Shots a frozen tab kept: not a `crema.` key, so a freeze can't drop them.
	try {
		local?.removeItem(FROZEN_SHOTS_KEY);
	} catch {
		// Nothing to protect: the reset still happened.
	}
	return { databasesDeleted, databasesFailed, storageKeysRemoved, revokeFailures };
}
