/**
 * `$lib/utils/country-detect` — lazy ISO-3166 country lookup via IP.
 *
 * The detection is **advisory only** — used by `MainsConfirmModal` to
 * surface "expected 230 V, you're setting 120 V" warnings when committing
 * the heater-voltage or AC-mains-frequency override. The user is always
 * the source of truth (they're standing next to the wall outlet); we just
 * give them a hint when the value they're about to type doesn't match
 * what their location implies.
 *
 * ## Lazy / cached
 *
 * - The fetch only runs the first time `detectCountry()` is called in a
 *   session. The modal calls it on mount, not at app boot, so users who
 *   never open the modal pay nothing.
 * - Result is cached in-memory for the rest of the session and persisted
 *   to `localStorage` (key `crema.country.v1`) for subsequent sessions —
 *   ipinfo.io is rate-limited and a country code is stable per IP.
 * - On any failure (network down, ipinfo rate-limited, browser offline)
 *   the call resolves to `null`; the modal then hides the mismatch
 *   banner and shows the type-to-confirm prompt unconditionally.
 *
 * ## Trust
 *
 * ipinfo.io's free `/country` endpoint returns just the two-letter code
 * with no CORS issues and no token required. We treat the response as
 * untrusted text — uppercase it, trim, and ignore anything that doesn't
 * look like an ISO-3166 alpha-2 code.
 */

/** Cached detection state (in-memory mirror of the localStorage entry). */
interface CountryCache {
	country: string | null;
	at: number;
}

const STORAGE_KEY = 'crema.country.v1';
const ENDPOINT = 'https://ipinfo.io/country';
const FETCH_TIMEOUT_MS = 3_000;

/** In-memory cache so repeated calls in one session skip even the localStorage read. */
let cached: CountryCache | null = null;

/**
 * Best-effort ISO-3166 alpha-2 country code for the device. Resolves to
 * `null` if detection fails for any reason (offline, CORS, rate-limited,
 * unsupported environment).
 *
 * Safe to call repeatedly — only the first call hits the network.
 */
export async function detectCountry(): Promise<string | null> {
	if (cached) return cached.country;

	// Restore from localStorage if present. We don't expire entries:
	// country-by-IP is stable enough for our use case and a stale value
	// is fine for an advisory hint.
	if (typeof localStorage !== 'undefined') {
		try {
			const raw = localStorage.getItem(STORAGE_KEY);
			if (raw) {
				const parsed = JSON.parse(raw) as CountryCache;
				if (parsed && typeof parsed === 'object') {
					cached = parsed;
					return parsed.country;
				}
			}
		} catch {
			// Corrupt storage — ignore and fall through to a fresh fetch.
		}
	}

	try {
		const controller = AbortSignal.timeout(FETCH_TIMEOUT_MS);
		const response = await fetch(ENDPOINT, { signal: controller });
		if (!response.ok) throw new Error(`ipinfo non-200: ${response.status}`);

		const raw = (await response.text()).trim().toUpperCase();
		// Sanity check — ipinfo's /country returns a bare alpha-2 code with
		// a trailing newline. Anything that doesn't fit treat as a miss.
		const country = /^[A-Z]{2}$/.test(raw) ? raw : null;

		cached = { country, at: Date.now() };

		if (typeof localStorage !== 'undefined') {
			try {
				localStorage.setItem(STORAGE_KEY, JSON.stringify(cached));
			} catch {
				// Quota / private-mode failures are non-fatal.
			}
		}
		return country;
	} catch {
		// Network down, CORS, abort, parse — all map to "no hint".
		// Don't cache the failure: the next modal open should be free to
		// retry (e.g. the user reconnected).
		return null;
	}
}

/**
 * Test-only: clear the in-memory + localStorage cache so a test or dev
 * tool can force a re-fetch. The shell does not call this in production.
 */
export function clearCountryCacheForTests(): void {
	cached = null;
	if (typeof localStorage !== 'undefined') {
		try {
			localStorage.removeItem(STORAGE_KEY);
		} catch {
			// ignore
		}
	}
}
