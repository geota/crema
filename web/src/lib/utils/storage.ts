/**
 * `#lib/utils/storage` — the shared `localStorage` JSON helpers.
 *
 * Crema's web shell is a static, client-only PWA — there is no server — so
 * `localStorage` is the home for every persisted store (`lib/profiles`,
 * `lib/history`, `lib/settings`). Each used to carry its own verbatim copy of
 * these helpers; they are extracted here so the read/parse and write/stringify
 * behaviour — including the SSR guard — is defined exactly once.
 *
 * Both helpers are SSR-safe: when `localStorage` is undefined (server-side
 * render, where there is no `window`), {@link readJson} returns the fallback
 * and {@link writeJson} is a no-op.
 *
 * **Crash safety.** `setItem` replaces one key atomically, so a killed tab
 * never leaves a half-written value — but a value that is unreadable for any
 * other reason (an older build's bug, a manual edit, storage damage) used to
 * read as the fallback, and the store's next save then overwrote it: the data
 * was gone for good. Now an unreadable value (it doesn't parse, or fails the
 * caller's shape check) is QUARANTINED first — copied verbatim to
 * `<key>.corrupt` with an ISO time at `<key>.corruptAt`, as #116 did for the
 * SAW model — and only then cleared. If the copy can't be made (quota), the
 * key is left in place and later writes to it are refused for the session, so
 * an empty state can never replace the only copy. Stores that name what they
 * hold (`what: 'shot history'`) also queue a one-time notice
 * ({@link takeStorageNotices}).
 *
 * **Quota.** A write that fails is no longer silent: it queues a one-time
 * notice too (per key, per session).
 *
 * **Load outcome.** A failure to READ is not damage: a throw from `getItem`
 * is `unavailable`, which keeps the value where it is and refuses saves to
 * that key until a reload reads it. Only a parse failure or a failed shape
 * check is `corrupt`. The stores that hold the user's data read through
 * {@link readStore}, which records each key's {@link StoreHealth}, so nothing
 * can take a store it couldn't read for an empty one. {@link readJson} stays
 * for caches and flags.
 *
 * **Rows.** A row a loader can't coerce goes to `<key>.skipped` through
 * {@link keepSkippedRows} before the next save drops it from the blob.
 */

/** What became of a stored value (the web twin of Android's `SafeLoad`). */
export type StoredRead =
	| { readonly kind: 'absent' }
	| { readonly kind: 'loaded'; readonly value: unknown }
	| { readonly kind: 'corrupt'; readonly raw: string; readonly error: string }
	| { readonly kind: 'unavailable'; readonly error: string };

/** What a store made of its key: {@link readStore}'s result. */
export type StoreRead<T> =
	| { readonly kind: 'absent' }
	| { readonly kind: 'loaded'; readonly value: T }
	| { readonly kind: 'corrupt'; readonly kept: boolean }
	| { readonly kind: 'unavailable'; readonly error: string };

/** How a store's load went. A key nothing has read yet is `loading`. */
export type StoreHealth = 'loading' | 'ok' | 'absent' | 'damaged' | 'unavailable';

/** What became of a checked write. */
export type StoreWrite = 'ok' | 'quota' | 'blocked' | 'failed';

/** A store whose saves are refused, for the banner. */
export interface RefusedStore {
	readonly key: string;
	readonly what: string;
}

/** One notice for the user: an unreadable store or row, or a failed save. */
export type StorageNotice =
	| { readonly kind: 'corrupt'; readonly key: string; readonly what: string; readonly kept: boolean }
	| { readonly kind: 'skipped'; readonly key: string; readonly what: string; readonly count: number }
	| { readonly kind: 'quota'; readonly key: string };

/** Options for {@link readJson}. */
export interface ReadJsonOptions {
	/** User-facing name ("shot history"): a corrupt value queues a notice. */
	readonly what?: string;
	/** Shape check on the parsed value; false counts as corrupt. */
	readonly valid?: (value: unknown) => boolean;
}

/** Options for {@link readStore}: the name is required, as a refusal shows it. */
export interface ReadStoreOptions {
	/** User-facing name ("shot history"). */
	readonly what: string;
	/** Shape check on the parsed value; false counts as corrupt. */
	readonly valid?: (value: unknown) => boolean;
}

/** A `valid` check for stores persisted as one JSON object. */
export function isJsonObject(v: unknown): v is Record<string, unknown> {
	return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** A `valid` check for an optional id (`string | null`). */
export function isIdOrNull(v: unknown): v is string | null {
	return v === null || typeof v === 'string';
}

/** Where an unreadable value of `key` is kept aside (newest capture wins). */
export const corruptKeyOf = (key: string): string => `${key}.corrupt`;
/** ISO-8601 time the kept-aside value was captured. */
export const corruptAtKeyOf = (key: string): string => `${key}.corruptAt`;
/** Where the rows a loader of `key` couldn't coerce are kept. */
export const skippedKeyOf = (key: string): string => `${key}.skipped`;

/** Keys that couldn't be read or kept aside: never overwrite them. */
const blocked = new Set<string>();
/** The named keys among {@link blocked}, by key, for the banner. */
const refused = new Map<string, string>();
/** Each key {@link readStore} has read, and how it went. */
const health = new Map<string, StoreHealth>();
/** Keys a quota notice was already queued for this session. */
const quotaNoticed = new Set<string>();
let pending: StorageNotice[] = [];
const listeners = new Set<(n: StorageNotice) => void>();
const refusedListeners = new Set<() => void>();

function notify(n: StorageNotice): void {
	if (listeners.size === 0) pending = [...pending, n];
	else for (const l of listeners) l(n);
}

/** Notices queued before anyone listened (stores load before the layout mounts). */
export function takeStorageNotices(): StorageNotice[] {
	const out = pending;
	pending = [];
	return out;
}

/** Receive notices as they happen; returns the unsubscribe. */
export function onStorageNotice(listener: (n: StorageNotice) => void): () => void {
	listeners.add(listener);
	return () => {
		listeners.delete(listener);
	};
}

/** How `key`'s store loaded. Keys that use {@link readJson} stay `loading`. */
export function storeHealth(key: string): StoreHealth {
	return health.get(key) ?? 'loading';
}

/** The stores whose saves are being refused right now. */
export function refusedStores(): RefusedStore[] {
	return [...refused].map(([key, what]) => ({ key, what }));
}

/** Hear when {@link refusedStores} changes; returns the unsubscribe. */
export function onRefusedStoresChange(listener: () => void): () => void {
	refusedListeners.add(listener);
	return () => {
		refusedListeners.delete(listener);
	};
}

/** Refuse saves to `key`; a named store also shows in the banner. */
function block(key: string, what: string | undefined): void {
	blocked.add(key);
	if (what === undefined || refused.has(key)) return;
	refused.set(key, what);
	for (const l of refusedListeners) l();
}

/** Lift `key`'s refusal: it has just been read. */
function unblock(key: string): void {
	blocked.delete(key);
	if (refused.delete(key)) for (const l of refusedListeners) l();
}

/** The toast line for an unreadable store or a failed save. */
export function storageNoticeMessage(n: StorageNotice): string {
	if (n.kind === 'quota') {
		return 'Browser storage is full — your latest changes couldn’t be saved. Free up space or export a backup.';
	}
	if (n.kind === 'skipped') {
		return n.count === 1
			? `One item in your ${n.what} couldn’t be read and was kept aside.`
			: `${n.count} items in your ${n.what} couldn’t be read and were kept aside.`;
	}
	return n.kept
		? `Couldn’t read your ${n.what}; the damaged data was kept aside.`
		: `Couldn’t read your ${n.what}; it was left in place and won’t be overwritten.`;
}

const errorText = (e: unknown): string => (e instanceof Error ? e.message : String(e));

/**
 * Read and parse `key` with the four explicit outcomes. Only a parse failure
 * is `corrupt`: a throw from storage, or anything else `JSON.parse` throws
 * (running out of memory), says nothing about the value, so it must not get
 * the value quarantined.
 */
export function readStored(key: string): StoredRead {
	let raw: string | null;
	try {
		if (typeof localStorage === 'undefined') return { kind: 'absent' };
		raw = localStorage.getItem(key);
	} catch (e) {
		return { kind: 'unavailable', error: errorText(e) };
	}
	if (raw == null) return { kind: 'absent' };
	try {
		return { kind: 'loaded', value: JSON.parse(raw) as unknown };
	} catch (e) {
		if (e instanceof SyntaxError) return { kind: 'corrupt', raw, error: e.message };
		return { kind: 'unavailable', error: errorText(e) };
	}
}

/**
 * Keep `raw` aside under {@link corruptKeyOf}, then clear `key` — only once
 * the copy is safe. When the copy fails the key is blocked instead (left in
 * place, writes refused). `what` queues a user notice. Returns whether kept.
 */
export function quarantineStored(key: string, raw: string, error: string, what?: string): boolean {
	let kept = false;
	try {
		localStorage.setItem(corruptKeyOf(key), raw);
		localStorage.setItem(corruptAtKeyOf(key), new Date().toISOString());
		localStorage.removeItem(key);
		kept = true;
		console.warn(
			`[storage] ${key} unreadable (${error}); ${raw.length} chars kept at ${corruptKeyOf(key)}`
		);
	} catch {
		block(key, what);
		console.warn(`[storage] ${key} unreadable (${error}); couldn't keep it aside, saving disabled`);
	}
	if (what) notify({ kind: 'corrupt', key, what, kept });
	return kept;
}

/**
 * Read + parse a localStorage value, falling back to `fallback` when absent.
 * An unreadable value (parse failure, or `opts.valid` rejects it) is kept
 * aside before the fallback is returned — see the module doc.
 */
export function readJson<T>(key: string, fallback: T, opts: ReadJsonOptions = {}): T {
	const r = readStored(key);
	if (r.kind === 'absent') return fallback;
	if (r.kind === 'unavailable') {
		if (opts.what !== undefined) block(key, opts.what);
		return fallback;
	}
	if (r.kind === 'loaded') {
		if (!opts.valid || opts.valid(r.value)) return r.value as T;
		quarantineStored(key, JSON.stringify(r.value), 'unexpected shape', opts.what);
		return fallback;
	}
	quarantineStored(key, r.raw, r.error, opts.what);
	return fallback;
}

/**
 * Read a store that holds the user's data. The caller has to say what it does
 * with each outcome, and the read has already dealt with the storage side:
 * `unavailable` leaves the value in place and refuses saves to `key`, and
 * `corrupt` keeps the value aside first. Records {@link storeHealth}.
 */
export function readStore<T>(key: string, opts: ReadStoreOptions): StoreRead<T> {
	const r = readStored(key);
	if (r.kind === 'unavailable') {
		console.warn(`[storage] ${key} couldn't be read (${r.error}); saving disabled`);
		health.set(key, 'unavailable');
		block(key, opts.what);
		return r;
	}
	if (r.kind === 'corrupt' || (r.kind === 'loaded' && opts.valid && !opts.valid(r.value))) {
		health.set(key, 'damaged');
		const kept =
			r.kind === 'corrupt'
				? quarantineStored(key, r.raw, r.error, opts.what)
				: quarantineStored(key, JSON.stringify(r.value), 'unexpected shape', opts.what);
		return { kind: 'corrupt', kept };
	}
	health.set(key, r.kind === 'loaded' ? 'ok' : 'absent');
	unblock(key);
	return r.kind === 'loaded' ? { kind: 'loaded', value: r.value as T } : r;
}

/** One row kept in `<key>.skipped`. */
interface SkippedRow {
	readonly at: string;
	readonly row: unknown;
}

/** The rows already kept for `key`. A list that won't parse stays, as a row. */
function readSkipped(key: string): SkippedRow[] {
	const raw = localStorage.getItem(skippedKeyOf(key));
	if (raw == null) return [];
	try {
		const list: unknown = JSON.parse(raw);
		if (Array.isArray(list)) return list as SkippedRow[];
	} catch {
		// Falls through: the list is a row of its own.
	}
	return [{ at: new Date().toISOString(), row: raw }];
}

/**
 * Keep the rows a loader of `key` couldn't coerce, in `<key>.skipped`, as the
 * next save drops them from the blob. `<key>.corrupt` holds a single value, so
 * it can't be reused. The blob still holds a row until that save, so a row
 * already kept isn't added or announced again. If the rows can't be kept, the
 * key is blocked, so no save can drop them. Returns whether they're safe.
 */
export function keepSkippedRows(key: string, rows: readonly unknown[], what: string): boolean {
	if (rows.length === 0) return true;
	if (health.get(key) === 'ok') health.set(key, 'damaged');
	try {
		const kept = readSkipped(key);
		const seen = new Set(kept.map((k) => JSON.stringify(k.row)));
		const at = new Date().toISOString();
		const fresh = rows.filter((row) => !seen.has(JSON.stringify(row)));
		if (fresh.length === 0) return true;
		localStorage.setItem(
			skippedKeyOf(key),
			JSON.stringify([...kept, ...fresh.map((row): SkippedRow => ({ at, row }))])
		);
		console.warn(`[storage] ${key}: ${fresh.length} unreadable rows kept at ${skippedKeyOf(key)}`);
		notify({ kind: 'skipped', key, what, count: fresh.length });
		return true;
	} catch {
		block(key, what);
		console.warn(`[storage] ${key}: couldn't keep ${rows.length} unreadable rows, saving disabled`);
		return false;
	}
}

function isQuota(e: unknown): boolean {
	return (
		e instanceof DOMException &&
		(e.name === 'QuotaExceededError' || e.name === 'NS_ERROR_DOM_QUOTA_REACHED' || e.code === 22)
	);
}

/**
 * Write `key` and say what happened, so a caller can tell a full disk from a
 * refused key: the history store evicts for one (review #27) and must never
 * for the other. A quota failure queues the one-time notice unless
 * `notice: false` (a caller that shows its own message).
 */
export function writeStore(key: string, value: unknown, notice = true): StoreWrite {
	if (typeof localStorage === 'undefined') return 'ok';
	if (blocked.has(key)) return 'blocked';
	try {
		localStorage.setItem(key, JSON.stringify(value));
		return 'ok';
	} catch (e) {
		if (notice) noticeQuota(key, e);
		return isQuota(e) ? 'quota' : 'failed';
	}
}

/** {@link writeStore} for callers that only need to know whether it landed. */
export function writeJsonChecked(key: string, value: unknown, notice = true): boolean {
	return writeStore(key, value, notice) === 'ok';
}

function noticeQuota(key: string, e: unknown): void {
	if (isQuota(e) && !quotaNoticed.has(key)) {
		quotaNoticed.add(key);
		notify({ kind: 'quota', key });
	}
}

/**
 * Write a JSON value to localStorage. A failure keeps the live in-memory state
 * for this session; a quota failure also queues a one-time notice per key, so
 * data is never silently left unsaved.
 */
export function writeJson(key: string, value: unknown): void {
	writeJsonChecked(key, value);
}

/** Test seam: forget blocked keys, health, queued notices and listeners. */
export function resetStorageStateForTest(): void {
	blocked.clear();
	refused.clear();
	health.clear();
	quotaNoticed.clear();
	pending = [];
	listeners.clear();
	refusedListeners.clear();
}
