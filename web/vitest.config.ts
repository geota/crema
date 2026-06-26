import fs from 'node:fs';
import path from 'node:path';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vitest/config';

/**
 * Vitest config for store-coupled service tests (docs/53 T-27, pulled forward).
 *
 * The existing `node --experimental-strip-types --test 'src/**​/*.test.ts'`
 * runner can't resolve the `$lib` alias or compile `.svelte.ts` runes, so the
 * store-coupled services (ShotSync / BeanSync / UploadQueue / Webhooks) were
 * verified only by hand. This config gives them a home: the svelte plugin
 * compiles runes, `$lib` resolves, jsdom supplies `localStorage` / `document`,
 * and Effect's own `TestContext` / `TestClock` (no `@effect/vitest` needed —
 * it conflicts with the vite-8-required vitest 4) drive time-based behavior.
 *
 * Scoped to `*.vitest.ts` so it does NOT pick up the node:test `*.test.ts`
 * files (those import `node:test` and would fail here). The two runners
 * coexist until a full migration; `pnpm test` runs both.
 */
// `__APP_VERSION__` (the backup-header version) is injected by vite.config.ts's
// `define` in the app build; mirror it here so vitest can run code that reads it
// — e.g. `buildBackupJsonl` (review #06 F35 / #07).
const pkgVersion: string = JSON.parse(
	fs.readFileSync(path.resolve('./package.json'), 'utf-8')
).version;

export default defineConfig({
	define: { __APP_VERSION__: JSON.stringify(pkgVersion) },
	plugins: [svelte({ compilerOptions: { hmr: false } })],
	resolve: {
		alias: { $lib: path.resolve('./src/lib') },
		// Resolve the browser entry points of `.svelte.ts` rune modules.
		conditions: ['browser']
	},
	test: {
		environment: 'jsdom',
		setupFiles: ['./vitest.setup.ts'],
		include: ['src/**/*.vitest.ts'],
		// Each test file in its own context so the shared module-singleton stores
		// + localStorage don't leak across files.
		isolate: true
	}
});
