import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import fs from 'node:fs';
import path from 'node:path';
import { sveltekit } from '@sveltejs/kit/vite';
// Under SvelteKit 3, `withPwa` is the plugin's only entry point: it wraps
// `sveltekit()` itself and builds the service worker inside the adapter's
// `adapt`, after the client bundle is written (geota/crema#172).
import { withPwa } from '@vite-pwa/sveltekit';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';
import { CSP_DIRECTIVES } from './src/lib/security/csp.ts';

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

type KitConfig = NonNullable<Parameters<typeof sveltekit>[0]>;
type PwaOptions = NonNullable<Parameters<typeof withPwa>[1]>;

function kitConfig(): KitConfig {
	return {
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
			directives: CSP_DIRECTIVES
		}
	};
}

/**
 * The service worker and web app manifest (`@vite-pwa/sveltekit` 2.x, on
 * `@unplugin-pwa/core`). `web/tests/browser/pwa.spec.ts` checks the built
 * worker: the precache, offline start and the update prompt.
 */
function pwaOptions(): PwaOptions {
	return {
		// `prompt`, not `autoUpdate`: the auto client reloads the page the moment
		// a new build activates, which would drop the DE1 link mid-shot. With
		// `prompt` the new worker waits; `#lib/pwa/register.ts` offers the reload
		// only while the machine is idle (and registers the worker at all —
		// SvelteKit never runs the HTML hook the plugin's auto-inject relies on).
		registerType: 'prompt',
		// `register.ts` does the registration, so the plugin must not emit (and
		// precache) its own unused `registerSW.js` (review 03 P3-2).
		injectRegister: false,
		// SPA mode for the precache: adapter-static's fallback page is the app
		// shell for every route, so it goes in the precache (revisioned from
		// _app/version.json) and the worker answers any navigation with it —
		// without this, nothing precaches the page and the app cannot open
		// offline even with the worker registered.
		kit: { adapterFallback: 'index.html', spa: true },
		// The wasm bundles exceed Workbox's 2 MiB default cache limit. In 2.x this
		// is a top-level option (it is dropped from `generateSW`), and a file over
		// it fails the build instead of silently missing from the precache.
		maximumFileSizeToCacheInBytes: 8 * 1024 * 1024,
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
		// Workbox's `generateSW` options (2.x renamed `workbox` to `generateSW`).
		generateSW: {
			// `prompt` strategy: the new worker waits until the shell posts
			// SKIP_WAITING (see registerType above), then claims the open tabs so
			// their `controllerchange` fires and the shell reloads them once.
			skipWaiting: false,
			clientsClaim: true,
			// Drop precaches left by older Workbox versions. The 1.x plugin (and
			// the fork) set this by default; 2.x leaves Workbox's default (off).
			cleanupOutdatedCaches: true,
			// The precache, globbed from `.svelte-kit/output/` (`client/` is the
			// built app; the plugin strips the prefix from each URL). Setting
			// `modifyURLPrefix` (empty: no rewrites) is how the plugin is told the
			// patterns are complete: without it, it appends a `prerendered/**` glob,
			// and this SPA prerenders nothing, so the strict glob check (`globStrict`,
			// on by default, kept on) would fail the build on that empty pattern.
			// Each pattern below must match at least one file.
			//
			// The app shell: scripts, styles, icons and the manifest. Icons in the UI
			// are inline SVG (phosphor-svelte) bundled into the JS chunks, so there's
			// no icon font. The design fonts (Newsreader / Hanken / JetBrains) are
			// cross-origin Google Fonts, runtime-cached below; `woff2` only matters
			// if they are ever self-hosted. The fallback page (`index.html`) is
			// added by `kit.spa` above.
			modifyURLPrefix: {},
			globPatterns: [
				'client/**/*.{js,css,html,ico,png,svg,webmanifest,woff2}',
				// The wasm cores (de1-wasm and the history store), for offline /
				// instant load. A pattern of their own, so a build that loses them
				// fails here rather than shipping a worker without them.
				'client/**/*.wasm'
			],
			// PERF4 (offline half): the design fonts load from Google Fonts, which
			// the precache can't reach (cross-origin) — so the app rendered
			// font-less offline. Runtime-cache the stylesheet (SWR) + the woff2
			// binaries (CacheFirst, opaque-cacheable) so a return / offline visit
			// has them. This is the standard Vite PWA Google-Fonts recipe;
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
	};
}

export default defineConfig(({ command }) => ({
	define: {
		__APP_VERSION__: JSON.stringify(pkgVersion)
	},
	// Workers build as ES modules: every Worker is created with
	// `{ type: 'module' }`, and the history Worker (#310 W1) imports the
	// wasm-bindgen ES module that `pnpm wasm:history` writes to
	// `src/lib/history-db/wasm/`, which the default `iife` format can't split.
	worker: {
		format: 'es'
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
		// The PWA plugin only matters for production builds (it generates the
		// service worker + manifest). In dev it does nothing useful AND its
		// resolved config (the glob patterns against the source tree)
		// contributes to Vite's `configHash`, so every time files matching
		// those globs are added or removed in `src/` the hash shifts and
		// `pnpm dev` pays a 30–75 s `optimizeDeps` re-bundle. Skip it in dev:
		// plain `sveltekit()` there, `withPwa` (which wraps it) for builds.
		...(command === 'build' ? withPwa(kitConfig(), pwaOptions()) : [sveltekit(kitConfig())])
	]
}));
