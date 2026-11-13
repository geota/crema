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
 *
 * **Other tabs (#333).** Every store writes its whole blob, so a tab holding
 * an old copy used to put it back over another tab's change. A store that
 * {@link followOtherTabs} takes what another tab wrote: on the `storage` event,
 * and on coming back into view, where an event may have been missed. Taking
 * it writes nothing to the key (a row it can't read still goes to
 * `<key>.skipped`). A value this tab can't read, or a key that was removed,
 * is left alone, and this tab stops following that key until a reload: the
 * tab that removed it may have quarantined a damaged value and started empty,
 * and this tab's copy may be the only good one, which its next write puts
 * back. True simultaneous edits still go to the last write.
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
/** The user-facing name of each key {@link readStore} has read, where it has one. */
const whats = new Map<string, string>();
/** Keys a quota notice was already queued for this session. */
const quotaNoticed = new Set<string>();
let pending: StorageNotice[] = [];

/** A store's way to take a value another tab wrote to one of its keys. */
interface Follower {
	readonly valid: (v: unknown) => boolean;
	readonly adopt: (value: unknown) => void;
}
/** The keys stores follow across tabs, and who takes each. */
const followers = new Map<string, Set<Follower>>();
/** The value this tab last read or wrote for each followed key. */
const lastSeen = new Map<string, string | null>();
/**
 * Keys this tab stopped following: another tab removed one, or wrote a value
 * this tab can't take. That tab may have quarantined a damaged value and
 * started empty, so its next write would cost this tab the good copy it still
 * holds. Until a reload, this tab's own writes win again (#380 review M1).
 */
const unfollowed = new Set<string>();
/** The window and document listeners, once a store follows a key. */
let followingInstalled = false;
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

/**
 * What a backup made now is missing: the named stores whose read this session
 * came out damaged or unavailable, sorted.
 */
export function unreadableStores(): string[] {
	const names = new Set<string>();
	for (const [key, h] of health) {
		const what = whats.get(key);
		if (what && (h === 'damaged' || h === 'unavailable')) names.add(what);
	}
	return [...names].sort();
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
 * aside before the fallback is returned — see the module doc. A key that can't
 * be read at all is refused until a read works, named or not, and only a named
 * one shows in the banner.
 */
export function readJson<T>(key: string, fallback: T, opts: ReadJsonOptions = {}): T {
	const r = readStored(key);
	if (r.kind === 'absent') {
		unblock(key);
		return fallback;
	}
	if (r.kind === 'unavailable') {
		block(key, opts.what);
		return fallback;
	}
	if (r.kind === 'loaded') {
		if (!opts.valid || opts.valid(r.value)) {
			unblock(key);
			return r.value as T;
		}
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
	whats.set(key, opts.what);
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
	} catch (e) {
		block(key, what);
		noticeQuota(key, e);
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
		const raw = JSON.stringify(value);
		localStorage.setItem(key, raw);
		lastSeen.set(key, raw);
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

/**
 * Take what other tabs write to `key` (#333): `adopt` gets the parsed value
 * whenever another tab stores one that passes `valid`, from the `storage`
 * event or, for an event missed while this tab was hidden or frozen, when it
 * comes back into view. `adopt` must not write the key. A value that doesn't
 * parse or fails `valid`, and a removed key, are left alone, nothing is kept
 * aside, and the key isn't followed again until a reload; a key this tab can't
 * save to isn't followed at all. Returns a function that stops following.
 */
export function followOtherTabs(
	key: string,
	valid: (v: unknown) => boolean,
	adopt: (value: unknown) => void
): () => void {
	installFollowing();
	const follower: Follower = { valid, adopt };
	// What the store just loaded: a recheck takes only what differs from it.
	// Only the store's load, its writes and its adopts move this, never another
	// reader of the key (#380 review S3).
	try {
		if (typeof localStorage !== 'undefined') lastSeen.set(key, localStorage.getItem(key));
	} catch {
		// Unreadable: the store's load blocked the key, and nothing is taken.
	}
	let set = followers.get(key);
	if (!set) followers.set(key, (set = new Set()));
	set.add(follower);
	return () => {
		followers.get(key)?.delete(follower);
	};
}

function installFollowing(): void {
	if (followingInstalled || typeof window === 'undefined') return;
	followingInstalled = true;
	window.addEventListener('storage', (e) => {
		if (e.key == null) return; // A clear: Erase all data reloads every tab.
		if (e.storageArea != null && typeof localStorage !== 'undefined' && e.storageArea !== localStorage) return;
		takeFromOtherTab(e.key, true);
	});
	// A hidden or frozen tab can miss an event: look again on the way back.
	const recheck = (): void => {
		for (const key of followers.keys()) takeFromOtherTab(key, false);
	};
	document.addEventListener('visibilitychange', () => {
		if (document.visibilityState === 'visible') recheck();
	});
	window.addEventListener('pageshow', (e) => {
		if (e.persisted) recheck();
	});
}

/**
 * Hand `key`'s stored value to its followers. From an event it's another
 * tab's write; on a recheck only a value that differs from what this tab last
 * read or wrote is.
 */
function takeFromOtherTab(key: string, fromEvent: boolean): void {
	const set = followers.get(key);
	if (!set || set.size === 0 || blocked.has(key) || unfollowed.has(key)) return;
	let raw: string | null;
	try {
		raw = localStorage.getItem(key);
	} catch {
		return;
	}
	if (!fromEvent && raw === (lastSeen.get(key) ?? null)) return;
	lastSeen.set(key, raw);
	if (raw == null) {
		stopFollowing(key, 'removed it');
		return;
	}
	let value: unknown;
	try {
		value = JSON.parse(raw);
	} catch {
		stopFollowing(key, "wrote it in a form this tab can't read");
		return;
	}
	const takers = [...set];
	if (!takers.every((f) => f.valid(value))) {
		stopFollowing(key, "wrote it in a shape this tab can't take");
		return;
	}
	for (const f of takers) f.adopt(value);
}

function stopFollowing(key: string, what: string): void {
	unfollowed.add(key);
	console.warn(`[storage] another tab ${what} (${key}); this tab keeps its copy and stops following it until a reload`);
}

/** Test seam: forget blocked keys, health, queued notices and listeners. */
export function resetStorageStateForTest(): void {
	blocked.clear();
	refused.clear();
	health.clear();
	whats.clear();
	quotaNoticed.clear();
	pending = [];
	listeners.clear();
	refusedListeners.clear();
	followers.clear();
	lastSeen.clear();
	unfollowed.clear();
}
