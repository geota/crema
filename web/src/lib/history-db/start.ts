/**
 * `#lib/history-db/start.js` — the app's history store (#310 PLAN W1). The
 * shell starts it at boot; nothing reads it yet (W2 does). This build is
 * stage 1: the owner's Worker keeps `history-index.db` in `crema-index`, and
 * never installs `crema-db`.
 *
 * Where a browser has no Worker, `BroadcastChannel` or Web Locks it doesn't
 * start, and the history stays on the P3 store, as on a browser without OPFS
 * sync access handles (owner question 5).
 */
import type { LockManagerLike } from './ports.js';
import type { ChannelLike } from './ports.js';
import { HistoryDbClient, type PageEvents, type WorkerLike } from './client.js';
import { HISTORY_JOURNAL } from './journal.js';
import { INDEX_FILE } from './names.js';

let client: HistoryDbClient | null = null;

function supported(): boolean {
	return (
		typeof Worker !== 'undefined' &&
		typeof BroadcastChannel !== 'undefined' &&
		typeof navigator !== 'undefined' &&
		!!navigator.locks
	);
}

function pageEvents(): PageEvents {
	return {
		addEventListener(type, listener) {
			(type === 'visibilitychange' ? document : window).addEventListener(type, listener as EventListener);
		},
		removeEventListener(type, listener) {
			(type === 'visibilitychange' ? document : window).removeEventListener(type, listener as EventListener);
		},
		visibility: () => document.visibilityState
	};
}

/** Starts this tab's history store, once. Null where the browser can't run it. */
export function startHistoryDb(): HistoryDbClient | null {
	if (client || !supported()) return client;
	client = new HistoryDbClient({
		stage: 1,
		expect: { role: 'index', file: INDEX_FILE },
		target: { pool: 'index', generation: 0, file: INDEX_FILE, role: 'index', create: true, journal: HISTORY_JOURNAL },
		build: __APP_VERSION__,
		makeWorker: () =>
			new Worker(new URL('./worker.ts', import.meta.url), {
				type: 'module',
				name: 'crema-history-db'
			}) as unknown as WorkerLike,
		channel: (name) => new BroadcastChannel(name) as unknown as ChannelLike,
		locks: navigator.locks as unknown as LockManagerLike,
		storage: localStorage,
		events: pageEvents()
	});
	client.start();
	return client;
}

/** This tab's history store, if it started. */
export function historyDb(): HistoryDbClient | null {
	return client;
}

/**
 * For a factory reset (S10): close, pause and release, and never take the
 * database again in this tab. Safe to call when it never started.
 */
export async function stopHistoryDb(): Promise<void> {
	await client?.stop();
}
