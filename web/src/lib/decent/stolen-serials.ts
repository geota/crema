/**
 * `$lib/decent/stolen-serials` — Decent's stolen-machine serial list.
 *
 * Decent publishes the serials of DE1s stolen from customers (or "lost" by a
 * carrier) as `stolen_serials.json` in the de1app repo, for other apps to
 * check; de1app tells the owner to contact Decent support. Crema's UX (user
 * decision, 2026-10): a non-blocking one-line notice in Settings → Machine,
 * the list fetched at most once a day and cached in localStorage, every
 * failure ignored silently — a missing list just means no notice.
 *
 * The rules (list parsing, the match, the refresh cadence) live in core
 * (`de1_domain::stolen_serials`); this module only fetches and caches. The
 * CSP's `connect-src` allows any `https:` host, so the fetch to
 * raw.githubusercontent.com is permitted (and that host serves CORS `*`).
 */

import {
	serialOnStolenList,
	stolenSerialsListIsValid,
	stolenSerialsRefreshDue,
	stolenSerialsUrl
} from '$lib/wasm/de1_wasm';
import { readJson, writeJson } from '$lib/utils/storage';

const CACHE_KEY = 'crema.stolenSerials.v1';

/** The cached list body and when it was fetched (epoch ms). */
interface StolenSerialsCache {
	fetchedAt: number;
	body: string;
}

/** Injectable I/O, for tests. */
export interface StolenSerialsDeps {
	fetch: (url: string) => Promise<{ ok: boolean; text(): Promise<string> }>;
	now: () => number;
	read: () => StolenSerialsCache | null;
	write: (cache: StolenSerialsCache) => void;
}

const defaultDeps: StolenSerialsDeps = {
	fetch: (url) => fetch(url, { cache: 'no-store' }),
	now: () => Date.now(),
	read: () => readJson<StolenSerialsCache | null>(CACHE_KEY, null),
	write: (cache) => writeJson(CACHE_KEY, cache)
};

/** In-flight refresh, so a burst of serial reads shares one fetch. */
let inflight: Promise<void> | null = null;

async function refreshIfDue(deps: StolenSerialsDeps): Promise<void> {
	const cached = deps.read();
	if (!stolenSerialsRefreshDue(cached?.fetchedAt, deps.now())) return;
	try {
		const res = await deps.fetch(stolenSerialsUrl());
		if (!res.ok) return;
		const body = await res.text();
		// Only a real list replaces the cache — never an error page.
		if (stolenSerialsListIsValid(body)) deps.write({ fetchedAt: deps.now(), body });
	} catch {
		// Offline, blocked, … — silently keep whatever is cached.
	}
}

/**
 * Whether the DE1 with this serial (MMR `SerialNumber`) is on Decent's
 * stolen-machine list. Refreshes the cached list first when it is more than a
 * day old; never throws — any failure answers from the cache, or `false`.
 */
export async function isSerialStolen(
	serial: number,
	deps: StolenSerialsDeps = defaultDeps
): Promise<boolean> {
	try {
		if (deps === defaultDeps) {
			inflight ??= refreshIfDue(deps).finally(() => {
				inflight = null;
			});
			await inflight;
		} else {
			await refreshIfDue(deps);
		}
		const body = deps.read()?.body;
		return body != null && serialOnStolenList(serial, body);
	} catch {
		return false;
	}
}
