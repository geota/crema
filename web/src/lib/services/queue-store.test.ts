/**
 * `$lib/services/queue-store.test` — node:test for the pure upload-queue
 * persistence (docs/53 T-14/T-16).
 *
 * `queue-store.ts` is the durable layer the `UploadQueue` service wraps and the
 * seam `ShotSync` enqueues through — and it's pure (only `localStorage` via
 * `utils/storage`), so unlike the store-coupled services it IS node:test-able
 * with a small in-memory `localStorage` polyfill. These lock in the behavior
 * T-16 routes the live shot/bean queue through:
 *  - the T-14 attempts semantics: `enqueueEntry` is set-like and does NOT bump
 *    attempts (the drain owns the counter via `persistRetry`);
 *  - the exponential backoff schedule + 60s cap;
 *  - dequeue / isPending / clear bookkeeping.
 *
 * Run: `cd web && node --experimental-strip-types --experimental-detect-module
 *   --test src/lib/services/queue-store.test.ts`
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

// In-memory localStorage polyfill (node has no DOM). `utils/storage` reads
// `globalThis.localStorage` at call time, so installing it before the tests run
// is enough — `queue-store` touches storage only inside its functions.
class MemStorage {
	private map = new Map<string, string>();
	getItem(k: string): string | null {
		return this.map.has(k) ? (this.map.get(k) as string) : null;
	}
	setItem(k: string, v: string): void {
		this.map.set(k, String(v));
	}
	removeItem(k: string): void {
		this.map.delete(k);
	}
	clear(): void {
		this.map.clear();
	}
}
// Node exposes `localStorage` as a getter that returns undefined unless
// `--localstorage-file` is passed, so a plain assignment can't replace it —
// `defineProperty` swaps in a data property the module's bare `localStorage`
// reads.
Object.defineProperty(globalThis, 'localStorage', {
	value: new MemStorage(),
	configurable: true,
	writable: true
});

import {
	backoffMs,
	clearQueue,
	dequeueEntry,
	enqueueEntry,
	isPendingId,
	MAX_ATTEMPTS,
	persistRetry,
	readQueue
} from './queue-store.ts';

const QUEUE_KEY = 'crema.visualizer.uploadQueue.v1';

/** Wipe the polyfilled store before every test (declared per-suite so the hook
 *  reliably cascades under node:test). */
const reset = () => (globalThis as { localStorage: Storage }).localStorage.clear();

describe('queue-store: enqueueEntry', () => {
	it('creates a fresh entry at attempts 0', () => {
		reset();
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create', error: 'boom' });
		const entries = readQueue().entries;
		assert.equal(entries.length, 1);
		const e = entries[0];
		assert.equal(e.entity, 'shot');
		assert.equal(e.id, 's1');
		assert.equal(e.op, 'create');
		assert.equal(e.attempts, 0);
		assert.equal(e.lastError, 'boom');
		assert.ok(e.enqueuedAt > 0);
		assert.ok(e.nextAttemptAt > 0);
	});

	it('is set-like on entity+id+op and does NOT bump attempts (T-14)', () => {
		reset();
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create', error: 'first' });
		// Simulate a drain having bumped the counter.
		persistRetry(readQueue().entries[0], 2, 'mid');
		assert.equal(readQueue().entries[0].attempts, 2);
		// A re-enqueue of the same tuple must NOT reset or bump attempts — it
		// only refreshes lastError / visualizerId. The drain owns the counter.
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create', error: 'second' });
		const entries = readQueue().entries;
		assert.equal(entries.length, 1, 'still one entry (set-like)');
		assert.equal(entries[0].attempts, 2, 'attempts untouched by re-enqueue');
		assert.equal(entries[0].lastError, 'second', 'lastError refreshed');
	});

	it('refreshes visualizerId on an existing entry without adding a row', () => {
		reset();
		enqueueEntry({ entity: 'shot', id: 's1', op: 'delete' });
		enqueueEntry({ entity: 'shot', id: 's1', op: 'delete', visualizerId: 'viz-9' });
		const entries = readQueue().entries;
		assert.equal(entries.length, 1);
		assert.equal(entries[0].visualizerId, 'viz-9');
	});

	it('treats a different op as a distinct entry', () => {
		reset();
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		enqueueEntry({ entity: 'shot', id: 's1', op: 'delete', visualizerId: 'viz-1' });
		assert.equal(readQueue().entries.length, 2);
	});
});

describe('queue-store: persistRetry', () => {
	it('bumps attempts to `made` and schedules nextAttemptAt by backoff', () => {
		reset();
		enqueueEntry({ entity: 'bean', id: 'b1', op: 'delete', visualizerId: 'v' });
		const before = Date.now();
		persistRetry(readQueue().entries[0], 1, 'net');
		const e = readQueue().entries[0];
		assert.equal(e.attempts, 1);
		assert.equal(e.lastError, 'net');
		// made=1 → backoff(1) = 2000ms.
		assert.ok(e.nextAttemptAt >= before + backoffMs(1) - 50);
		assert.ok(e.nextAttemptAt <= Date.now() + backoffMs(1) + 50);
	});

	it('is a no-op when the entry was dequeued meanwhile', () => {
		reset();
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		const entry = readQueue().entries[0];
		dequeueEntry('shot', 's1', 'create');
		// Re-running persistRetry against the now-absent entry must not resurrect it.
		persistRetry(entry, 1, 'late');
		assert.equal(readQueue().entries.length, 0);
	});
});

describe('queue-store: backoffMs', () => {
	it('doubles from 1s and caps at 60s', () => {
		reset();
		assert.equal(backoffMs(0), 1000);
		assert.equal(backoffMs(1), 2000);
		assert.equal(backoffMs(2), 4000);
		assert.equal(backoffMs(5), 32000);
		assert.equal(backoffMs(6), 60000); // 64000 capped
		assert.equal(backoffMs(10), 60000);
	});

	it('MAX_ATTEMPTS is the documented 3', () => {
		reset();
		assert.equal(MAX_ATTEMPTS, 3);
	});
});

describe('queue-store: dequeue / isPending / clear', () => {
	it('dequeueEntry removes only the matching tuple', () => {
		reset();
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		enqueueEntry({ entity: 'shot', id: 's2', op: 'create' });
		dequeueEntry('shot', 's1', 'create');
		const ids = readQueue().entries.map((e) => e.id);
		assert.deepEqual(ids, ['s2']);
	});

	it('isPendingId matches any op for the entity+id', () => {
		reset();
		enqueueEntry({ entity: 'shot', id: 's1', op: 'delete', visualizerId: 'v' });
		assert.equal(isPendingId('shot', 's1'), true);
		assert.equal(isPendingId('shot', 'nope'), false);
		assert.equal(isPendingId('bean', 's1'), false);
	});

	it('clearQueue empties the store', () => {
		reset();
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		clearQueue();
		assert.equal(readQueue().entries.length, 0);
	});
});

describe('queue-store: readQueue defensiveness', () => {
	it('returns empty on a malformed persisted blob', () => {
		reset();
		(globalThis as { localStorage: Storage }).localStorage.setItem(
			QUEUE_KEY,
			JSON.stringify({ entries: 'not-an-array' })
		);
		assert.deepEqual(readQueue().entries, []);
	});
});
