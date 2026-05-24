/**
 * `$lib/bean/roaster-favicon` — best-effort logo lookup for a roaster,
 * using Google's public favicon service.
 *
 * The {@link Roaster} type already has an `imageUrl` field for a uploaded
 * logo or Visualizer-supplied hero image. When that's empty, we can pull
 * a *favicon* from the roaster's `website` via Google's S2 service —
 * useful for the long-tail of roasters where the user only ever filled
 * in the URL.
 *
 * Endpoint chosen:
 *
 *   https://t0.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON
 *     &fallback_opts=TYPE,SIZE,URL&url=<u>&size=<n>
 *
 * vs. the older `s2/favicons?domain=…&sz=…` endpoint:
 *
 * - `faviconV2` returns **404** when no favicon of the requested size is
 *   available, so an `<img>`'s `onerror` is a reliable "we have nothing
 *   good" signal — exactly what the user asked for ("only use icons of
 *   sufficient size").
 * - `s2/favicons` always succeeds with a 16×16 grey-globe placeholder
 *   even when there is no real favicon, which would force us to fetch
 *   the bytes to detect "is this the fallback?". That's worse.
 *
 * Privacy note: the favicon request goes to Google, not Crema (Crema has
 * no servers). It's the same network call the user's browser makes when
 * visiting the roaster's website itself, so we don't gate it behind a
 * preference. The roaster's website domain is sent to Google; nothing
 * else about the user.
 */

import type { Roaster } from './model';

/**
 * Extract a hostname from a free-form URL — tolerates inputs with or
 * without a scheme (`example.com`, `www.example.com/coffee`,
 * `https://example.com/`). Returns `null` if the input doesn't look
 * like a URL at all.
 */
function hostnameFromUrl(raw: string | null | undefined): string | null {
	if (raw == null) return null;
	const trimmed = raw.trim();
	if (trimmed === '') return null;
	const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
	try {
		const u = new URL(withScheme);
		const host = u.hostname.trim();
		return host || null;
	} catch {
		return null;
	}
}

/**
 * Build the Google `faviconV2` URL for a roaster's website, at the
 * requested size in CSS pixels. Returns `null` when there's no website
 * to look up — the caller should fall back to the deterministic mark.
 *
 * The endpoint returns 404 when no icon of the requested size exists,
 * so the caller can hook `onerror` on the resulting `<img>` and swap in
 * the mark cleanly.
 */
export function roasterFaviconUrl(
	roaster: Roaster | null,
	size = 64
): string | null {
	if (!roaster) return null;
	const host = hostnameFromUrl(roaster.website);
	if (!host) return null;
	const url = `https://${host}/`;
	const params = new URLSearchParams({
		client: 'SOCIAL',
		type: 'FAVICON',
		fallback_opts: 'TYPE,SIZE,URL',
		url,
		size: String(size)
	});
	return `https://t0.gstatic.com/faviconV2?${params.toString()}`;
}
