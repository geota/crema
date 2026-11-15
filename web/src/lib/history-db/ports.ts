/**
 * `#lib/history-db/ports.js` — the browser APIs the history store uses, as the
 * small interfaces its fakes implement: a `BroadcastChannel`, the Web Locks
 * manager. Kept here, apart from the P3 store's, so the Worker's bundle and the
 * browser tests' type-check pull in nothing else of the app.
 */

export interface ChannelLike {
	postMessage(message: unknown): void;
	close(): void;
	onmessage: ((event: { data: unknown }) => void) | null;
}

export interface LockManagerLike {
	request(
		name: string,
		options: { mode?: 'shared' | 'exclusive'; ifAvailable?: boolean; signal?: AbortSignal },
		callback: (lock: unknown) => unknown
	): Promise<unknown>;
	query?(): Promise<{ held?: { name?: string }[] }>;
}
