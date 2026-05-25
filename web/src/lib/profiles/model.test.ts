/**
 * `$lib/profiles/model.test` — node:test smoke test for the
 * wasm-bridged profile-ID generator (`newProfileId`).
 *
 * Profile-ID generation lives in the Rust core
 * (`de1_domain::new_profile_id`) and is exposed through the wasm
 * bridge as the top-level export `newProfileId`. This test bypasses
 * the SvelteKit `$lib` alias (node can't resolve it) and exercises
 * the wasm export directly — mirroring the pattern in
 * `$lib/visualizer/shot-sync.test.ts`.
 *
 * Runs via Node's built-in test runner:
 *
 * ```
 * cd web && node \
 *   --experimental-strip-types \
 *   --experimental-detect-module \
 *   --test src/lib/profiles/model.test.ts
 * ```
 */

import assert from 'node:assert/strict';
import { describe, it, before } from 'node:test';
import { default as initWasm, newProfileId } from '../wasm/de1_wasm.js';

/**
 * The canonical UUID v7 form — 8-4-4-4-12 lowercase hex with the
 * version nibble `7` and a `{8,9,a,b}` variant nibble. Mirrors the
 * regex the Rust unit tests use.
 */
const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

// The wasm bundle is built with `--target web`, so its default
// initializer calls `fetch(<url to .wasm>)`. Node's `fetch` does not
// support `file://` URLs, so we read the .wasm bytes off disk and hand
// them to the initializer directly — mirroring `shot-sync.test.ts`.
before(async () => {
	const { readFile } = await import('node:fs/promises');
	const wasmUrl = new URL('../wasm/de1_wasm_bg.wasm', import.meta.url);
	const bytes = await readFile(wasmUrl);
	await initWasm({ module_or_path: bytes });
});

describe('newProfileId (wasm bridge)', () => {
	it('returns a well-formed UUID v7', () => {
		const id = newProfileId();
		assert.match(id, UUID_V7);
	});

	it('produces unique values across many draws', () => {
		// Two back-to-back calls in the same millisecond still differ
		// in their 74 random bits; pin that across a larger run.
		const seen = new Set<string>();
		for (let i = 0; i < 100; i++) seen.add(newProfileId());
		assert.equal(seen.size, 100);
	});

	it('sorts lexicographically by creation time', () => {
		// The v7 48-bit timestamp prefix is monotonic across calls
		// (random low bits break same-ms ties), so successive IDs
		// always satisfy `a <= b`. Lock that contract — debug + log
		// readability relies on it.
		let prev = newProfileId();
		for (let i = 0; i < 50; i++) {
			const next = newProfileId();
			assert.ok(prev <= next, `${prev} should sort <= ${next}`);
			prev = next;
		}
	});
});
