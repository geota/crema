/**
 * `#lib/security/csp.js` — the app shell's Content-Security-Policy directives.
 *
 * Consumed by `vite.config.ts` (SvelteKit's `csp` option, `mode: 'hash'`):
 * SvelteKit emits them as a `<meta http-equiv>` tag in the static fallback
 * page and appends the sha256 of its own inline bootstrap script to
 * `script-src` on every build. Kept in its own module so a test can pin the
 * policy (`csp.vitest.ts`).
 */

import type { CspDirectives } from '@sveltejs/kit';

/** The GIS client library, loaded on demand by `#lib/drive/oauth.js`. */
export const GIS_CLIENT_SCRIPT = 'https://accounts.google.com/gsi/client';

export const CSP_DIRECTIVES: CspDirectives = {
	'default-src': ['self'],
	// SvelteKit appends the inline-bootstrap hash here automatically.
	// The Google Identity Services (GIS) client library used by the Drive
	// backup OAuth (token model) is allowed by its exact path, as Google's GIS
	// CSP guide recommends — NOT the whole accounts.google.com host, which
	// serves JSONP-style endpoints that would let an injected <script> bypass
	// the allowlist (review 05 P3-4).
	'script-src': ['self', 'wasm-unsafe-eval', GIS_CLIENT_SCRIPT],
	// GIS opens a popup AND (for the silent re-grant) a hidden iframe to
	// accounts.google.com; frame-src otherwise inherits default-src 'self'
	// and would block it. Drive backup only.
	'frame-src': ['self', 'https://accounts.google.com'],
	// 'unsafe-inline' covers static style="" attributes (e.g. the
	// `display: contents` body wrapper); googleapis serves the
	// Newsreader/Hanken/JetBrains @font-face stylesheet (see app.html).
	'style-src': ['self', 'unsafe-inline', 'https://fonts.googleapis.com'],
	// 'self' → self-hosted Phosphor icon fonts; gstatic → Google Fonts files.
	'font-src': ['self', 'https://fonts.gstatic.com'],
	// `blob:` is required for bean bag photos: the bytes live in
	// IndexedDB and are rendered through `URL.createObjectURL`
	// (`#lib/components/beans/BeanImage`). Without it EVERY bag photo
	// is blocked and silently falls back to the roaster mark — which
	// is exactly what issue 61 reported as "photos can't be viewed".
	// A blob: URL is same-origin and minted by this app's own code, so
	// allowing it grants no reach an attacker did not already have
	// under `script-src`.
	// `https:` covers roaster logos, which are arbitrary user- or
	// Visualizer-supplied URLs (`Roaster.imageUrl`, and the favicon
	// fallback in `RoasterAvatar`) and so cannot be enumerated.
	// Plaintext http: stays blocked.
	'img-src': ['self', 'data:', 'blob:', 'https:'],
	// 'self' covers the wasm/app bundle and same-origin (incl. the
	// dev server). `https:` is required by the user-configurable
	// webhook feature, which POSTs shot/connection events to an
	// ARBITRARY user-supplied URL (settings.webhookUrl) — those
	// hosts can't be enumerated, so the scheme is allowed wholesale.
	// It also subsumes Visualizer (OAuth token exchange + shot sync,
	// https://visualizer.coffee). Tradeoff: an XSS could exfiltrate
	// over any HTTPS host (including unwrapped tokens), and a stored
	// roaster `imageUrl` under img-src can act as a tracking beacon;
	// accepted for a no-server, local-first app with no script-injection
	// sink and no third-party scripts beyond the pinned GIS client
	// (review 05 notes). Plaintext http: stays blocked.
	'connect-src': ['self', 'https:'],
	'base-uri': ['self']
};
