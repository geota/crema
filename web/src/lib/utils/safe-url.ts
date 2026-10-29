/**
 * `#lib/utils/safe-url.js` — gate for user- or import-supplied links before
 * they reach an `href`.
 *
 * Bean and roaster URLs come from typing, Beanconqueror imports, Visualizer
 * pulls and backup restores, so any string can arrive. Only `http:` and
 * `https:` links are rendered; `javascript:`, `data:`, `vbscript:`, `blob:`
 * and the rest are dropped. A bare host (`onyxcoffeelab.com/shop`) gets
 * `https://`, matching Android's `visitRoasterWebsite`, instead of becoming a
 * same-origin relative link. The CSP already refuses `javascript:`
 * navigations; this keeps that true if the CSP ever regresses.
 */

const HTTP_SCHEME = /^https?:\/\//i;
/** Anything that starts with a URI scheme (`name:`), e.g. `javascript:`, `mailto:`. */
const ANY_SCHEME = /^[a-z][a-z0-9+.-]*:/i;

/** An absolute `http(s)` URL for `raw`, or `null` when it is not safe to link. */
export function safeExternalUrl(raw: string | null | undefined): string | null {
	if (raw == null) return null;
	const trimmed = raw.trim();
	if (trimmed === '') return null;
	let candidate: string;
	if (HTTP_SCHEME.test(trimmed)) {
		candidate = trimmed;
	} else if (ANY_SCHEME.test(trimmed) && !/^[^/:]+:\d+(?:[/?#]|$)/.test(trimmed)) {
		// A real scheme (not `host:port`) that is not http(s).
		return null;
	} else {
		candidate = `https://${trimmed}`;
	}
	try {
		const url = new URL(candidate);
		if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
		if (!url.hostname) return null;
		return url.href;
	} catch {
		return null;
	}
}
