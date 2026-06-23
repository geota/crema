import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	preprocess: vitePreprocess(),
	kit: {
		// SPA mode: a static SPA with a fallback page. The app is client-only
		// (wasm + Web Bluetooth), so every route is served from index.html and
		// the SvelteKit router takes over in the browser.
		adapter: adapter({
			pages: 'build',
			assets: 'build',
			fallback: 'index.html',
			precompress: false,
			strict: true
		}),
		// Custom domain at the apex → base path stays '/'.

		// Content-Security-Policy for the app shell. SvelteKit emits this as a
		// <meta http-equiv> tag in the (static) fallback page and, in `hash`
		// mode, appends the sha256 of its own inline bootstrap script to
		// `script-src` — recomputed every build, so it can never drift the way
		// a hardcoded hash in static/_headers would. `wasm-unsafe-eval` is
		// required to compile the de1 wasm core.
		//
		// `frame-ancestors` is deliberately NOT here: it is ignored inside a
		// <meta> CSP and must be a real response header, so it lives in
		// static/_headers (served by Cloudflare Pages) instead.
		csp: {
			mode: 'hash',
			directives: {
				'default-src': ['self'],
				// SvelteKit appends the inline-bootstrap hash here automatically.
				// `accounts.google.com` serves the Google Identity Services (GIS)
				// client library used by the Drive backup OAuth (token model).
				'script-src': ['self', 'wasm-unsafe-eval', 'https://accounts.google.com'],
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
				'img-src': ['self', 'data:'],
				// 'self' covers the wasm/app bundle and same-origin (incl. the
				// dev server). `https:` is required by the user-configurable
				// webhook feature, which POSTs shot/connection events to an
				// ARBITRARY user-supplied URL (settings.webhookUrl) — those
				// hosts can't be enumerated, so the scheme is allowed wholesale.
				// It also subsumes Visualizer (OAuth token exchange + shot sync,
				// https://visualizer.coffee). Tradeoff: an XSS could exfiltrate
				// over any HTTPS host; acceptable for a no-server, local-first
				// app with no third-party scripts. Plaintext http: stays blocked.
				'connect-src': ['self', 'https:'],
				'base-uri': ['self']
			}
		}
	}
};

export default config;
