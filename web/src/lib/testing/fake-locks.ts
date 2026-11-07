/**
 * `#lib/testing/fake-locks` — a Web Locks manager for tests (jsdom has none):
 * shared and exclusive modes, `ifAvailable`, and a FIFO queue, enough for the
 * restore's tab rules. Not a full implementation.
 */
import type { LockManagerLike } from '#lib/restore/tab-safety.js';

type Mode = 'shared' | 'exclusive';

interface Waiter {
	name: string;
	mode: Mode;
	start: () => void;
}

export class FakeLocks implements LockManagerLike {
	readonly held: { name: string; mode: Mode }[] = [];
	readonly #queue: Waiter[] = [];

	#free(name: string, mode: Mode): boolean {
		const mine = this.held.filter((h) => h.name === name);
		return mode === 'shared' ? mine.every((h) => h.mode === 'shared') : mine.length === 0;
	}

	request(
		name: string,
		options: { mode?: Mode; ifAvailable?: boolean },
		callback: (lock: unknown) => unknown
	): Promise<unknown> {
		const mode = options.mode ?? 'exclusive';
		const queued = this.#queue.some((w) => w.name === name);
		if (!this.#free(name, mode) || queued) {
			if (options.ifAvailable) return Promise.resolve(callback(null));
			return new Promise((resolve) => {
				this.#queue.push({ name, mode, start: () => resolve(this.#grant(name, mode, callback)) });
			});
		}
		return this.#grant(name, mode, callback);
	}

	#grant(name: string, mode: Mode, callback: (lock: unknown) => unknown): Promise<unknown> {
		const entry = { name, mode };
		this.held.push(entry);
		return Promise.resolve(callback({ name, mode })).finally(() => {
			this.held.splice(this.held.indexOf(entry), 1);
			this.#drain();
		});
	}

	#drain(): void {
		while (this.#queue.length > 0 && this.#free(this.#queue[0].name, this.#queue[0].mode)) {
			this.#queue.shift()!.start();
		}
	}

	async query(): Promise<{ held: { name: string; mode: Mode }[] }> {
		return { held: [...this.held] };
	}

	/** Hold `name` until the returned function runs, as another tab would. */
	async hold(name: string, mode: Mode = 'exclusive'): Promise<() => void> {
		let release!: () => void;
		const granted = new Promise<void>((g) => {
			void this.request(name, { mode }, () => {
				g();
				return new Promise<void>((r) => (release = r));
			});
		});
		await granted;
		return () => release();
	}
}
