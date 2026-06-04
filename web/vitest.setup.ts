/**
 * Vitest global setup. Node exposes `localStorage` as a getter that returns
 * undefined without `--localstorage-file`, and that shadows jsdom's Storage on
 * `globalThis`. Install a real in-memory Storage as a data property so the
 * stores' bare `localStorage` reads/writes work; tests clear it in `beforeEach`.
 */

import { readFileSync } from 'node:fs';
import path from 'node:path';
import { initSync } from '$lib/wasm/de1_wasm';

/**
 * Instantiate the core's wasm bridge for the whole suite.
 *
 * Some modules read core-sourced data through wasm exports — e.g. `De1Uuids`
 * (`$lib/ble/de1-uuids`) calls `de1Uuids()` on first access — and the GATT
 * UUIDs now live in the Rust core, not hardcoded in the shell. The app gets
 * there via `loadCore()` at boot; tests have no boot, so we load the wasm here
 * (synchronously, from the build output on disk) so those reads resolve.
 *
 * `initSync` is memoised (no-ops once `wasm` is set) and `isolate: true` gives
 * each test file its own module registry, so this runs once per file. cwd is
 * `web/` under vitest (mirrors `vitest.config.ts`'s `path.resolve`).
 */
initSync({ module: readFileSync(path.resolve('src/lib/wasm/de1_wasm_bg.wasm')) });
class MemStorage implements Storage {
	private map = new Map<string, string>();
	get length(): number {
		return this.map.size;
	}
	clear(): void {
		this.map.clear();
	}
	getItem(key: string): string | null {
		return this.map.has(key) ? (this.map.get(key) as string) : null;
	}
	key(index: number): string | null {
		return Array.from(this.map.keys())[index] ?? null;
	}
	removeItem(key: string): void {
		this.map.delete(key);
	}
	setItem(key: string, value: string): void {
		this.map.set(key, String(value));
	}
}

Object.defineProperty(globalThis, 'localStorage', {
	value: new MemStorage(),
	configurable: true,
	writable: true
});
