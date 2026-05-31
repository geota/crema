/**
 * `$lib/profiles/model.vitest` — vitest for the wasm-bridged profile-ID
 * generator (`newProfileId`). Migrated from `model.test.ts` (T-27).
 *
 * Profile-ID generation lives in the Rust core (`de1_domain::new_profile_id`)
 * and is exposed through the wasm bridge as the top-level export `newProfileId`.
 * The bundle is init'd once via {@link initTestWasm} (bytes off disk — the
 * `--target web` fetch path doesn't work under vitest). The UUID v7 contract
 * (format + uniqueness + monotonic sort) is the Rust unit tests' contract,
 * mirrored here at the wasm boundary.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { newProfileId } from '../wasm/de1_wasm.js';
import { initTestWasm } from '../wasm/test-init.ts';

/** The canonical UUID v7 form — 8-4-4-4-12 lowercase hex with version nibble
 *  `7` and a `{8,9,a,b}` variant nibble. Mirrors the Rust unit-test regex. */
const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

beforeAll(initTestWasm);

describe('newProfileId (wasm bridge)', () => {
	it('returns a well-formed UUID v7', () => {
		expect(newProfileId()).toMatch(UUID_V7);
	});

	it('produces unique values across many draws', () => {
		// Two back-to-back calls in the same millisecond still differ in their 74
		// random bits; pin that across a larger run.
		const seen = new Set<string>();
		for (let i = 0; i < 100; i++) seen.add(newProfileId());
		expect(seen.size).toBe(100);
	});

	it('sorts lexicographically by creation time', () => {
		// The v7 48-bit timestamp prefix is monotonic across calls (random low
		// bits break same-ms ties), so successive IDs always satisfy `a <= b`.
		let prev = newProfileId();
		for (let i = 0; i < 50; i++) {
			const next = newProfileId();
			expect(prev <= next, `${prev} should sort <= ${next}`).toBe(true);
			prev = next;
		}
	});
});
