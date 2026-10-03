/**
 * `$lib/utils/storage` — the shared `localStorage` JSON helpers.
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
 */

/** What became of a stored value (the web twin of Android's `SafeLoad`). */
export type StoredRead =
	| { readonly kind: 'absent' }
	| { readonly kind: 'loaded'; readonly value: unknown }
	| { readonly kind: 'corrupt'; readonly raw: string; readonly error: string };

/** One notice for the user: an unreadable store kept aside, or a failed save. */
export type StorageNotice =
	| { readonly kind: 'corrupt'; readonly key: string; readonly what: string; readonly kept: boolean }
	| { readonly kind: 'quota'; readonly key: string };

/** Options for {@link readJson}. */
export interface ReadJsonOptions {
	/** User-facing name ("shot history"): a corrupt value queues a notice. */
	readonly what?: string;
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

/** Keys whose unreadable value couldn't be kept aside: never overwrite them. */
const blocked = new Set<string>();
/** Keys a quota notice was already queued for this session. */
const quotaNoticed = new Set<string>();
let pending: StorageNotice[] = [];
const listeners = new Set<(n: StorageNotice) => void>();

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

/** The toast line for an unreadable store or a failed save. */
export function storageNoticeMessage(n: StorageNotice): string {
	if (n.kind === 'quota') {
		return 'Browser storage is full — your latest changes couldn’t be saved. Free up space or export a backup.';
	}
	return n.kept
		? `Couldn’t read your ${n.what}; the damaged data was kept aside.`
		: `Couldn’t read your ${n.what}; it was left in place and won’t be overwritten.`;
}

/** Read and parse `key` with the three explicit outcomes. */
export function readStored(key: string): StoredRead {
	if (typeof localStorage === 'undefined') return { kind: 'absent' };
	let raw: string | null;
	try {
		raw = localStorage.getItem(key);
	} catch {
		return { kind: 'absent' }; // storage unavailable: nothing to protect
	}
	if (raw == null) return { kind: 'absent' };
	try {
		return { kind: 'loaded', value: JSON.parse(raw) as unknown };
	} catch (e) {
		return { kind: 'corrupt', raw, error: e instanceof Error ? e.message : String(e) };
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
		blocked.add(key);
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
	if (r.kind === 'loaded') {
		if (!opts.valid || opts.valid(r.value)) return r.value as T;
		quarantineStored(key, JSON.stringify(r.value), 'unexpected shape', opts.what);
		return fallback;
	}
	quarantineStored(key, r.raw, r.error, opts.what);
	return fallback;
}

function isQuota(e: unknown): boolean {
	return (
		e instanceof DOMException &&
		(e.name === 'QuotaExceededError' || e.name === 'NS_ERROR_DOM_QUOTA_REACHED' || e.code === 22)
	);
}

/**
 * Like {@link writeJson} but REPORTS failure — for callers that must react
 * to a quota error (the history store evicts and warns; review #27). A quota
 * failure still queues the one-time notice unless `notice: false` (a caller
 * that shows its own message).
 */
export function writeJsonChecked(key: string, value: unknown, notice = true): boolean {
	if (typeof localStorage === 'undefined') return true;
	if (blocked.has(key)) return false;
	try {
		localStorage.setItem(key, JSON.stringify(value));
		return true;
	} catch (e) {
		if (notice) noticeQuota(key, e);
		return false;
	}
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

/** Test seam: forget blocked keys, queued notices and listeners. */
export function resetStorageStateForTest(): void {
	blocked.clear();
	quotaNoticed.clear();
	pending = [];
	listeners.clear();
}
