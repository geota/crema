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
		})
		// Custom domain at the apex → base path stays '/'.
	}
};

export default config;
