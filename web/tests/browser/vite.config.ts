/**
 * Builds the W0 browser-test harness and the B1 browser benchmark (#310 PLAN
 * W0, B1) as two static pages, apart from the SvelteKit app:
 * `tests/browser/harness/index.html` and `bench/history-db.html`. Both load
 * the production history wasm (`pnpm wasm:history`) in a dedicated module
 * Worker, as the app's Worker will (W1).
 *
 *   vite build -c tests/browser/vite.config.ts   # → tests/browser/.build/
 *   vite preview -c tests/browser/vite.config.ts # serves it on :4318
 *
 * `pnpm test:browser` does both through Playwright's `webServer`.
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

const web = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

export default defineConfig({
	root: web,
	envDir: false,
	publicDir: false,
	worker: { format: 'es' },
	build: {
		outDir: path.join(web, 'tests/browser/.build'),
		emptyOutDir: true,
		target: 'es2022',
		rollupOptions: {
			input: {
				harness: path.join(web, 'tests/browser/harness/index.html'),
				w1: path.join(web, 'tests/browser/w1/index.html'),
				w2: path.join(web, 'tests/browser/w2/index.html'),
				bench: path.join(web, 'bench/history-db.html')
			}
		}
	},
	preview: { port: 4318, strictPort: true, host: '127.0.0.1' },
	server: { port: 4318, strictPort: true, fs: { allow: [path.resolve(web, '..')] } }
});
