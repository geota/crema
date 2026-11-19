/**
 * The service-worker tests (`tests/browser/pwa.spec.ts`, geota/crema#172):
 * the production build's offline shell, wasm precache and update prompt.
 * `pnpm test:pwa`, after `pnpm build`. Apart from `playwright.config.ts`
 * (the history store's W0 tests), which builds and serves its own harness;
 * this spec serves `build/` itself.
 *
 * Chromium (desktop Chrome, Android's engine) and WebKit (Safari, iOS).
 */
import { defineConfig } from '@playwright/test';

export default defineConfig({
	testDir: 'tests/browser',
	testMatch: ['pwa.spec.ts', 'upgrade.spec.ts'],
	timeout: 90_000,
	fullyParallel: true,
	retries: 0,
	reporter: [['list']],
	use: { trace: 'retain-on-failure', serviceWorkers: 'allow' },
	projects: [
		{ name: 'chromium', use: { browserName: 'chromium' } },
		{ name: 'webkit', use: { browserName: 'webkit' } }
	]
});
