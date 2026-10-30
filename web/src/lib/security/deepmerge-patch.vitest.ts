/**
 * deepmerge 4.3.1 (build-time only: vite-plugin-svelte and, through
 * vite-plugin-pwa / workbox-build, @rollup/plugin-node-resolve merge config
 * with it) lets a `__proto__` key set the merged object's prototype
 * (GHSA-gcqx-w48m-w2h4). There is no patched release, so
 * patches/deepmerge@4.3.1.patch fixes it. These tests load the copy the build
 * resolves and fail if the patch stops applying.
 */
import { createRequire } from 'node:module';
import { afterEach, describe, expect, it } from 'vitest';

type Merge = (target: unknown, source: unknown) => Record<string, any>;

// deepmerge isn't a direct dependency, so resolve it from one of its parents.
const require = createRequire(import.meta.url);
const deepmerge: Merge = createRequire(require.resolve('@sveltejs/vite-plugin-svelte'))(
	'deepmerge'
);

afterEach(() => {
	delete (Object.prototype as Record<string, unknown>).polluted;
});

describe('patched deepmerge', () => {
	it('does not let a __proto__ key pollute', () => {
		const merged = deepmerge({}, JSON.parse('{"__proto__":{"polluted":1}}'));
		expect(Object.getPrototypeOf(merged)).toBe(Object.prototype);
		expect(merged.polluted).toBeUndefined();
		expect(({} as Record<string, unknown>).polluted).toBeUndefined();
	});

	it('does not poison a nested object that replaces a null placeholder', () => {
		const merged = deepmerge(
			{ session: null },
			JSON.parse('{"session":{"__proto__":{"admin":true},"id":1}}')
		);
		expect(Object.getPrototypeOf(merged.session)).toBe(Object.prototype);
		expect(merged.session.admin).toBeUndefined();
		expect(merged.session.id).toBe(1);
	});

	it('skips __proto__, constructor and prototype on a null-prototype target', () => {
		const target = Object.assign(Object.create(null), { debug: false });
		const merged = deepmerge(
			target,
			JSON.parse('{"__proto__":{"admin":true},"constructor":{"prototype":{"x":1}},"prototype":{}}')
		);
		expect(Object.getPrototypeOf(merged)).toBe(Object.prototype);
		expect(merged.admin).toBeUndefined();
		expect(Object.keys(merged)).toEqual(['debug']);
	});

	it('still merges nested objects and concatenates arrays', () => {
		const merged = deepmerge(
			{ server: { port: 5173, fs: { allow: ['src'] } }, plugins: ['a'] },
			{ server: { host: true, fs: { allow: ['static'] } }, plugins: ['b'] }
		);
		expect(merged).toEqual({
			server: { port: 5173, host: true, fs: { allow: ['src', 'static'] } },
			plugins: ['a', 'b']
		});
	});
});
