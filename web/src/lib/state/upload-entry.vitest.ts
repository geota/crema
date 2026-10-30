/**
 * AC-F6.6 (with F7): the shell never uploads a profile itself. It sets the
 * desired profile (`set_desired_profile`: library profile + Quick Controls
 * overrides) and the core's profile sync builds every byte and decides when to
 * send them. The bridge exports no upload at all, and the one shell call site
 * of the entry point is `CremaApp.setDesiredProfile`.
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { CremaBridge } from '#lib/wasm/de1_wasm.js';

const SRC = path.resolve('src');

/** Every shell source file (generated wasm glue + typeshare bindings and tests excluded). */
function shellSources(dir = SRC): string[] {
	return readdirSync(dir).flatMap((name) => {
		const full = path.join(dir, name);
		if (statSync(full).isDirectory()) return name === 'wasm' ? [] : shellSources(full);
		if (!/\.(ts|svelte)$/.test(name) || /\.(vitest|test)\.ts$/.test(name)) return [];
		if (name === 'crema-core.ts') return [];
		return [full];
	});
}

/** The files (relative to src/) containing `needle`. */
function filesWith(needle: RegExp): string[] {
	return shellSources()
		.filter((f) => needle.test(readFileSync(f, 'utf-8')))
		.map((f) => path.relative(SRC, f))
		.sort();
}

describe('one upload entry point (AC-F6.6)', () => {
	it('f6_6 the bridge exports no profile upload, only the desired profile', () => {
		const methods = Object.getOwnPropertyNames(CremaBridge.prototype);
		expect(methods).toContain('set_desired_profile');
		expect(methods.filter((m) => /^upload.*profile/.test(m))).toEqual([]);
	});

	it('f6_6 only the core wrapper calls the bridge, and only the app calls the wrapper', () => {
		expect(filesWith(/set_desired_profile|upload_crema_profile|upload_profile\b/)).toEqual([
			'lib/core/index.ts'
		]);
		expect(filesWith(/core\.setDesiredProfile\(/)).toEqual(['lib/state/app.svelte.ts']);
		// No shell code hand-builds upload bytes from a wire profile any more.
		expect(filesWith(/\buploadProfile\b|uploadCremaProfile|uploadEffectiveProfile/)).toEqual([]);
	});
});

describe('nothing about the DE1 profile is persisted (AC-F7.6)', () => {
	it('f7_6 no shell code reads or writes a "DE1 holds" fingerprint', () => {
		expect(filesWith(/lastFingerprint|activeProfileFingerprint|LAST_FINGERPRINT_KEY/)).toEqual([]);
	});
});
