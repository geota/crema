import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { sveltekit } from '@sveltejs/kit/vite';
import { SvelteKitPWA } from '@vite-pwa/sveltekit';
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

export default defineConfig(({ command }) => ({
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
		// Subset the Phosphor icon font to only the icons the app references,
		// regenerating `src/lib/icons/` (gitignored) before the build/dev graph is
		// read. The full `@phosphor-icons/web` set is ~440 KiB woff2 + ~130 KiB CSS
		// for ~1530 icons; the app uses <10 %. A used-set fingerprint makes warm
		// restarts a no-op. Loaded via a dynamic absolute-path import so the
		// subsetter (and its WASM harfbuzz dep) is never bundled into the config.
		{
			name: 'phosphor-subset',
			enforce: 'pre',
			async buildStart() {
				const url = pathToFileURL(path.resolve('scripts/subset-phosphor.mjs')).href;
				const { generatePhosphorSubset } = await import(/* @vite-ignore */ url);
				await generatePhosphorSubset();
			}
		},
		tailwindcss(),
		sveltekit(),
		// The PWA plugin only matters for production builds (it generates the
		// service worker + manifest). In dev it does nothing useful AND its
		// resolved config (the `workbox.globPatterns` against the source tree)
		// contributes to Vite's `configHash`, so every time files matching
		// those globs are added or removed in `src/` the hash shifts and
		// `pnpm dev` pays a 30–75 s `optimizeDeps` re-bundle. Skip it in dev.
		...(command === 'build' ? [SvelteKitPWA({
			registerType: 'autoUpdate',
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
					// Placeholder art — a coffee-toned square with a "C" arc; final
					// art is a later polish item.
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
						src: 'icon-maskable-512.png',
						sizes: '512x512',
						type: 'image/png',
						purpose: 'maskable'
					}
				]
			},
			workbox: {
				// Precache the app shell + the wasm core (`**/*.wasm` caches the
				// de1-wasm binary for offline / instant-load) + the Phosphor icon
				// fonts (`woff2`). The icons are SUBSET to only those the app uses
				// (scripts/subset-phosphor.mjs) — ~41 KiB across three weights vs the
				// full ~440 KiB — so precaching is cheap and renders icons instantly +
				// offline on a cold load. Only LOCAL woff2 match: the design fonts
				// (Newsreader / Hanken / JetBrains) are cross-origin Google Fonts,
				// runtime-cached below. The subsetter's woff2-only @font-face also
				// stops Vite emitting the ~7 MiB of Phosphor `.svg` sprite fallbacks
				// the old PERF1 `globIgnores` excluded — gone at the source now.
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
		})] : [])
	]
}));
