/**
 * `$lib/testing/test-init` — initialise the `de1_wasm` bundle for vitest.
 *
 * The bundle is built with wasm-bindgen `--target web`, so its default
 * initializer fetches `de1_wasm_bg.wasm` by URL — which neither Node nor jsdom
 * can `fetch` over `file://`. So we read the `.wasm` bytes off disk and hand
 * them to the initializer directly (`{ module_or_path: bytes }`), exactly as the
 * old `node:test` suites did. Idempotent — safe to `beforeAll` in every wasm-
 * touching vitest file. NOT a test file (no `.test.ts` / `.vitest.ts` suffix).
 *
 * Lives here, NOT under `$lib/wasm/`: that dir is the wasm-pack `--out-dir` and
 * is gitignored (CI regenerates it fresh via `pnpm wasm`), so a helper placed
 * there is untracked and vanishes in CI — svelte-check then can't resolve it.
 */

import initWasm from '$lib/wasm/de1_wasm.js';

let initialised = false;

export async function initTestWasm(): Promise<void> {
	if (initialised) return;
	const { readFile } = await import('node:fs/promises');
	const { resolve } = await import('node:path');
	// Resolve from the package root, not `import.meta.url`: under vitest's vite
	// transform `import.meta.url` isn't a `file://` URL, so `new URL(…, it)` +
	// `readFile` rejects ("URL must be of scheme file"). vitest runs from `web/`.
	const bytes = await readFile(resolve(process.cwd(), 'src/lib/wasm/de1_wasm_bg.wasm'));
	await initWasm({ module_or_path: bytes });
	initialised = true;
}
