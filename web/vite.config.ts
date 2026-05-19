import { sveltekit } from '@sveltejs/kit/vite';
import { SvelteKitPWA } from '@vite-pwa/sveltekit';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

export default defineConfig(({ command }) => ({
	plugins: [
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
					// art is a later polish item (see docs/09-web-shell.md step 7).
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
				// Precache the app shell *and* the wasm core: `**/*.wasm` is added
				// to the default glob so the de1-wasm binary is cached for offline
				// / instant-load. `.svg` covers the icon sources.
				globPatterns: ['**/*.{js,css,html,ico,png,svg,webmanifest,wasm}'],
				// The wasm bundle exceeds Workbox's 2 MiB default cache limit.
				maximumFileSizeToCacheInBytes: 8 * 1024 * 1024
			}
		})] : [])
	]
}));
