/**
 * Vitest global setup. Node exposes `localStorage` as a getter that returns
 * undefined without `--localstorage-file`, and that shadows jsdom's Storage on
 * `globalThis`. Install a real in-memory Storage as a data property so the
 * stores' bare `localStorage` reads/writes work; tests clear it in `beforeEach`.
 */
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
