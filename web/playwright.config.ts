/**
 * The browser tests (#310 PLAN W0): the history wasm on real OPFS through
 * `sahpool`, in a dedicated Worker, in real browsers. `pnpm test:browser`.
 *
 * Projects:
 * - `chromium`: desktop Chrome, headless (Playwright's Chromium).
 * - `webkit`: Playwright's WebKit, standing in for Safari.
 * - `bench`: `bench.spec.ts` in Chromium, after the other two.
 * - `android-chrome`: Chrome on an Android device or emulator, over CDP.
 *   Only with `W0_ANDROID=1`; `tests/browser/README.md` says how to set it up.
 *
 * Each test gets a persistent profile of its own on disk (a temp dir), so
 * OPFS is real files and no test sees another's. On Android, where the
 * profile is Chrome's own, tests keep apart by pool generation instead.
 *
 * The harness and the bench are a separate Vite build
 * (`tests/browser/vite.config.ts`), served on :4318.
 */
import { defineConfig } from '@playwright/test';

const android = process.env.W0_ANDROID === '1';

export default defineConfig({
	testDir: 'tests/browser',
	testMatch: '*.spec.ts',
	// The service-worker tests need the app's production build: `pnpm test:pwa`.
	testIgnore: 'pwa.spec.ts',
	// The durability runs are long; each test sets its own budget too.
	timeout: 120_000,
	fullyParallel: true,
	// Each test is a browser of its own; more at once only makes timings noisy.
	workers: process.env.CI ? 2 : 3,
	retries: 0,
	reporter: [['list'], ['json', { outputFile: 'test-results/w0.json' }]],
	use: {
		baseURL: 'http://127.0.0.1:4318',
		trace: 'retain-on-failure'
	},
	webServer: {
		command:
			'vite build -c tests/browser/vite.config.ts --logLevel warn && vite preview -c tests/browser/vite.config.ts',
		url: 'http://127.0.0.1:4318/tests/browser/harness/index.html',
		reuseExistingServer: false,
		timeout: 120_000
	},
	// The bench runs last, alone and one size at a time: its first-page lines
	// are gated, and the durability runs' crashes beside it make timings noise.
	projects: android
		? [{ name: 'android-chrome', use: { browserName: 'chromium' } }]
		: [
				{ name: 'chromium', use: { browserName: 'chromium' }, testIgnore: ['bench.spec.ts', 'w2.spec.ts', 'pwa.spec.ts'] },
				{ name: 'webkit', use: { browserName: 'webkit' }, testIgnore: ['bench.spec.ts', 'w2.spec.ts', 'pwa.spec.ts'] },
				{
					name: 'bench',
					use: { browserName: 'chromium' },
					testMatch: ['bench.spec.ts', 'w2.spec.ts'],
					fullyParallel: false,
					dependencies: ['chromium', 'webkit']
				}
			]
});
