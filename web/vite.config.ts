import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import fs from 'node:fs';
import path from 'node:path';
import { sveltekit } from '@sveltejs/kit/vite';
// `@katama-labs/vite-pwa-sveltekit` is the pending upstream SvelteKit 3 support
// for `@vite-pwa/sveltekit` (vite-pwa/sveltekit#111), published by its author.
// The official 1.x plugin peers Kit 1–2 and under Kit 3 silently emits no
// sw.js. Switch back once upstream ships Kit 3 support — geota/crema#172; the
// vite-pwa-sveltekit-watch workflow comments there when it does.
import { SvelteKitPWA } from '@katama-labs/vite-pwa-sveltekit';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

/**
 * Resolve an HTTPS cert pair for `pnpm dev`. Returns `undefined` (Vite falls
 * back to HTTP) when the certs aren't present, so a fresh checkout still
 * runs without ceremony — only the Visualizer OAuth flow needs HTTPS
 * locally, and even there only if the user is signing in.
 *
 * Generate with `mkcert localhost 127.0.0.1` inside `web/` (one-time;
 * requires `brew install mkcert && mkcert -install`). The `.pem` files
 * are gitignored.
 */
function devHttpsCert(): { key: Buffer; cert: Buffer } | undefined {
	const keyPath = path.resolve('./localhost+1-key.pem');
	const certPath = path.resolve('./localhost+1.pem');
	if (!fs.existsSync(keyPath) || !fs.existsSync(certPath)) return undefined;
	return { key: fs.readFileSync(keyPath), cert: fs.readFileSync(certPath) };
}

// App version for the backup header (`__APP_VERSION__`), sourced from
// package.json so it can't silently drift from the real release (review #06 F35).
const pkgVersion: string = JSON.parse(
	fs.readFileSync(path.resolve('./package.json'), 'utf-8')
).version;

export default defineConfig(({ command }) => ({
	define: {
		__APP_VERSION__: JSON.stringify(pkgVersion)
	},
	server:
		command === 'serve'
			? {
				// HTTPS only when the mkcert pair exists; otherwise plain HTTP.
				// The `serve` command is `pnpm dev` (vs `build`); production
				// deploys to adapter-static + Cloudflare Pages, which handles
				// HTTPS independently.
				https: devHttpsCert()
			}
			: undefined,
	plugins: [
		tailwindcss(),
		sveltekit({
			preprocess: vitePreprocess(),
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
					// over any HTTPS host; acceptable for a no-server, local-first
					// app with no third-party scripts. Plaintext http: stays blocked.
					'connect-src': ['self', 'https:'],
					'base-uri': ['self']
				}
			}
		}),

		// The PWA plugin only matters for production builds (it generates the
		// service worker + manifest). In dev it does nothing useful AND its
		// resolved config (the `workbox.globPatterns` against the source tree)
		// contributes to Vite's `configHash`, so every time files matching
		// those globs are added or removed in `src/` the hash shifts and
		// `pnpm dev` pays a 30–75 s `optimizeDeps` re-bundle. Skip it in dev.
		...command === 'build'
			? [
				SvelteKitPWA({
					// `prompt`, not `autoUpdate`: the auto client reloads the page the moment
			// a new build activates, which would drop the DE1 link mid-shot. With
			// `prompt` the new worker waits; `#lib/pwa/register.ts` offers the reload
			// only while the machine is idle (and registers the worker at all —
			// SvelteKit never runs the HTML hook the plugin's auto-inject relies on).
			registerType: 'prompt',
			// SPA mode for the precache: adapter-static's fallback page is the app
			// shell for every route, so it goes in the precache (revisioned from
			// _app/version.json) and the worker answers any navigation with it —
			// without this, nothing precaches the page and the app cannot open
			// offline even with the worker registered.
			kit: { adapterFallback: 'index.html', spa: true },
					// Full web app manifest. `theme_color` / `background_color` match the
					// daisyUI `coffee` theme's base so the splash and chrome blend in.
					manifest: {
						name: 'Crema',
						short_name: 'Crema',
						description: 'A native-feeling web shell for the DE1 espresso machine.',
						theme_color: '#20161e',
						background_color: '#20161e',
						display: 'standalone',
						start_url: '/',
						icons: [
							// Crema brand mark — font-free, generated from brand/crema-mark.svg
							// (brand/generate_mark.py). "any" = the disc mark (transparent
							// corners); "maskable" = full-bleed copper square + centred C.
							{
								src: 'icon-192.png',
								sizes: '192x192',
								type: 'image/png',
								purpose: 'any'
							},
							{
								src: 'icon-512.png',
								sizes: '512x512',
								type: 'image/png',
								purpose: 'any'
							},
							{
								src: 'icon-maskable-192.png',
								sizes: '192x192',
								type: 'image/png',
								purpose: 'maskable'
							},
							{
								src: 'icon-maskable-512.png',
								sizes: '512x512',
								type: 'image/png',
								purpose: 'maskable'
							}
						]
					},
					workbox: {
				// `prompt` strategy: the new worker waits until the shell posts
				// SKIP_WAITING (see registerType above), then claims the open tabs so
				// their `controllerchange` fires and the shell reloads them once.
				skipWaiting: false,
				clientsClaim: true,
						// Precache the app shell + the wasm core (`**/*.wasm` caches the
						// de1-wasm binary for offline / instant-load). Icons are inline SVG
						// (phosphor-svelte) bundled into the JS chunks, so there's no icon
						// font to precache — they render instantly + offline by construction.
						// The `woff2` glob now only matches design fonts if ever self-hosted;
						// today those (Newsreader / Hanken / JetBrains) are cross-origin
						// Google Fonts, runtime-cached below.
						globPatterns: ['**/*.{js,css,html,ico,png,webmanifest,wasm,woff2}'],
						// The wasm bundle exceeds Workbox's 2 MiB default cache limit.
						maximumFileSizeToCacheInBytes: 8 * 1024 * 1024,
						// PERF4 (offline half): the design fonts load from Google Fonts, which
						// the precache can't reach (cross-origin) — so the app rendered
						// font-less offline. Runtime-cache the stylesheet (SWR) + the woff2
						// binaries (CacheFirst, opaque-cacheable) so a return / offline visit
						// has them. This is the standard vite-plugin-pwa Google-Fonts recipe;
						// fully self-hosting woff2 subsets (to also drop the first-paint
						// render-blocking on a COLD visit) remains a follow-up polish.
						runtimeCaching: [
							{
								urlPattern: ({ url }: { url: URL }) => url.origin === 'https://fonts.googleapis.com',
								handler: 'StaleWhileRevalidate',
								options: { cacheName: 'google-fonts-stylesheets' }
							},
							{
								urlPattern: ({ url }: { url: URL }) => url.origin === 'https://fonts.gstatic.com',
								handler: 'CacheFirst',
								options: {
									cacheName: 'google-fonts-webfonts',
									expiration: { maxEntries: 32, maxAgeSeconds: 60 * 60 * 24 * 365 },
									cacheableResponse: { statuses: [0, 200] }
								}
							}
						]
					}
				})
			]
			: []
	]
}));
