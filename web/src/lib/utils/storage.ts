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
 */

/** Read + parse a localStorage value, falling back to `fallback` on any error. */
export function readJson<T>(key: string, fallback: T): T {
	if (typeof localStorage === 'undefined') return fallback;
	try {
		const raw = localStorage.getItem(key);
		return raw == null ? fallback : (JSON.parse(raw) as T);
	} catch {
		return fallback;
	}
}

/** Write a JSON value to localStorage, swallowing quota / availability errors. */
export function writeJson(key: string, value: unknown): void {
	if (typeof localStorage === 'undefined') return;
	try {
		localStorage.setItem(key, JSON.stringify(value));
	} catch {
		// Static PWA with no server — a write failure is non-fatal; the live
		// in-memory state still reflects the change for this session.
	}
}
