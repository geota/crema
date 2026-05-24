/**
 * `$lib/visualizer/migrate-basic-auth` — one-shot clean-up of the
 * pre-OAuth credential blob.
 *
 * Prior versions of Crema stored a Visualizer email + cleartext password
 * under `crema.beans.sync.v1`. With the move to OAuth (coordinator
 * direction, 2026-05-24) HTTP-Basic is gone, so we want any lingering
 * credentials out of `localStorage`. Runs once at app boot via the
 * shell's onMount.
 *
 * Behaviour:
 *   - Read the existing sync blob.
 *   - If it has any `username`/`password` field set, rewrite it without
 *     those fields (preserving the other sync metadata such as
 *     `lastSyncAt` and `premium`).
 *   - Idempotent — safe to call on every load; no-op if already clean.
 *
 * Deliberately does **not** notify the user. The settings screen presents
 * the OAuth "Sign in with Visualizer" button as if it's first-run; that
 * is the right nudge.
 */

const SYNC_KEY = 'crema.beans.sync.v1';

interface LegacyBlob {
	username?: unknown;
	password?: unknown;
	[k: string]: unknown;
}

export function migrateLegacyBasicAuth(): void {
	if (typeof localStorage === 'undefined') return;
	let raw: string | null;
	try {
		raw = localStorage.getItem(SYNC_KEY);
	} catch {
		return;
	}
	if (!raw) return;
	let parsed: LegacyBlob;
	try {
		parsed = JSON.parse(raw) as LegacyBlob;
	} catch {
		return;
	}
	if (!parsed || typeof parsed !== 'object') return;
	const hasUser = typeof parsed.username === 'string' && parsed.username.length > 0;
	const hasPass = typeof parsed.password === 'string' && parsed.password.length > 0;
	const hasKey = 'username' in parsed || 'password' in parsed;
	if (!hasUser && !hasPass && !hasKey) return;
	// Strip the credential fields, preserve the rest.
	const cleaned: Record<string, unknown> = {};
	for (const [k, v] of Object.entries(parsed)) {
		if (k === 'username' || k === 'password') continue;
		cleaned[k] = v;
	}
	try {
		localStorage.setItem(SYNC_KEY, JSON.stringify(cleaned));
	} catch {
		// Quota / availability — nothing we can do.
	}
}
