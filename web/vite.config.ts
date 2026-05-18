import { sveltekit } from '@sveltejs/kit/vite';
import { SvelteKitPWA } from '@vite-pwa/sveltekit';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit(),
		SvelteKitPWA({
			// Minimal stub — full manifest, icons and Workbox tuning land in a
			// later step (see docs/09-web-shell.md, build sequence step 7).
			registerType: 'autoUpdate',
			manifest: {
				name: 'Crema',
				short_name: 'Crema',
				description: 'A native-feeling web shell for the DE1 espresso machine.',
				theme_color: '#1e1b16',
				background_color: '#1e1b16',
				display: 'standalone',
				start_url: '/'
			}
		})
	]
});
