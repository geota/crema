/**
 * AC-F6.6: the shell has exactly one way to upload a profile — the core's
 * `upload_crema_profile`, which builds every byte from (library profile, Quick
 * Controls overrides). Nothing else in the shell can send a raw or hand-edited
 * profile: the bridge no longer exports a raw `upload_profile`, and the one
 * call site of the entry point is `CremaApp.uploadEffectiveProfile`.
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
	it('f6_6 the bridge exports no raw profile upload', () => {
		const methods = Object.getOwnPropertyNames(CremaBridge.prototype);
		expect(methods).toContain('upload_crema_profile');
		expect(methods.filter((m) => /^upload.*profile/.test(m))).toEqual([
			'upload_crema_profile'
		]);
	});

	it('f6_6 only the core wrapper calls the bridge upload, and only the app calls the wrapper', () => {
		expect(filesWith(/upload_crema_profile|upload_profile\b/)).toEqual(['lib/core/index.ts']);
		expect(filesWith(/\.uploadCremaProfile\(/)).toEqual(['lib/state/app.svelte.ts']);
		// No shell code hand-builds upload bytes from a wire profile any more.
		expect(filesWith(/\buploadProfile\b/)).toEqual([]);
	});
});
